package services.printing;

import services.EventLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks a print backend and sends the job (JCLAW-911).
 *
 * <p>Order is IPP → raw socket → LPD, and it is an ordering by <em>how much the
 * backend can tell us</em>, not by speed. IPP returns a job id and a status; raw
 * socket returns nothing but a closed connection; LPD returns a single ack byte.
 * Falling back therefore trades away the ability to answer "did it print?", which
 * is why the fallbacks are last and why {@link Outcome#verified()} exists.
 *
 * <p>Each attempt's failure is collected rather than thrown, so a job that
 * exhausts every backend reports all three reasons at once. One line saying
 * "IPP refused, 9100 refused connection, LPD timed out" is diagnosable; three
 * separate failures across three tool calls are not.
 */
public final class PrintDispatcher {

    private static final String CATEGORY = "printer";
    private static final int TIMEOUT_MS = 15_000;

    /** Queue name used for LPD. Daemons overwhelmingly accept one of these two. */
    private static final String LPD_QUEUE = "lp";

    /**
     * Job id → printer URI for jobs submitted this process, so {@code cancel(jobId)}
     * works without the caller re-supplying the printer. Bounded and in-memory by
     * design: it is a convenience index, not a record of truth, and a restart
     * legitimately forgets it — the printer, not JClaw, owns job state.
     */
    private static final int RECENT_JOBS_MAX = 64;
    private static final Map<Integer, String> RECENT_JOBS =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > RECENT_JOBS_MAX;
                }
            });

    /**
     * What happened to a print request.
     *
     * @param protocol the backend that accepted the job
     * @param jobId    printer-assigned job id — IPP only; null for the blind backends
     * @param state    printer-reported job state, or null when the backend cannot report
     * @param verified whether the printer actually confirmed acceptance. False for
     *                 raw socket and LPD, where a successful write proves only that
     *                 the bytes left this machine
     * @param droppedAttributes job attributes that were requested but could not be
     *                 applied, because the backend that accepted the job cannot
     *                 express them. Null when nothing was lost. Only raw socket and
     *                 LPD produce this — both take an undifferentiated byte stream,
     *                 so a duplex request that falls back to them prints one-sided
     * @param skipped  why each earlier backend did not take the job, in the order
     *                 they were tried. Empty when the first choice worked. Reported
     *                 even on success: a job that silently "worked" via the third
     *                 backend hides that the first two are broken, and the operator
     *                 only finds out when the fallback stops working too
     * @param detail   human-readable summary for the model and the operator
     */
    public record Outcome(PrintProtocol protocol, Integer jobId, String state,
                          boolean verified, String droppedAttributes,
                          List<String> skipped, String detail) {

        public Outcome {
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    private PrintDispatcher() {}

    /**
     * Send {@code document} to {@code printer}, trying each backend the printer can
     * plausibly speak until one accepts.
     *
     * @throws IOException when every backend failed; the message names each failure
     */
    public static Outcome print(DiscoveredPrinter printer, String jobName, String user,
                                String documentFormat, byte[] document,
                                JobAttributes job) throws IOException {
        return print(printer, jobName, user, documentFormat, document, job, Map.of());
    }

    /**
     * Send with the operator's full option set.
     *
     * @param options every IPP job attribute the operator chose, by name. Carried
     *                whole rather than as three named fields, because the options
     *                were discovered from the printer — a tray the device
     *                announced has to be sendable or choosing it does nothing
     */
    public static Outcome print(DiscoveredPrinter printer, String jobName, String user,
                                String documentFormat, byte[] document,
                                JobAttributes job, Map<String, String> options)
            throws IOException {
        var failures = new ArrayList<String>();
        var requested = job == null ? JobAttributes.DEFAULTS : job;

        for (var protocol : backendOrder(printer)) {
            try {
                var outcome = attempt(protocol, printer, jobName, user, documentFormat,
                        document, requested, options == null ? Map.of() : options);
                if (outcome != null) {
                    // Carry why the earlier backends declined, so a fallback
                    // success does not read as a clean first-choice success.
                    outcome = new Outcome(outcome.protocol(), outcome.jobId(), outcome.state(),
                            outcome.verified(), outcome.droppedAttributes(), failures, outcome.detail());
                    if (outcome.jobId() != null) {
                        RECENT_JOBS.put(outcome.jobId(), printer.ippUri());
                    }
                    EventLogger.info(CATEGORY, "Printed to %s via %s: %s"
                            .formatted(printer.name(), protocol, outcome.detail()));
                    return outcome;
                }
                failures.add(protocol + ": printer rejected the job");
            } catch (IOException e) {
                failures.add(protocol + ": " + e.getMessage());
            }
        }
        throw new IOException("No print backend accepted the job — " + String.join("; ", failures));
    }

    /**
     * Backends to try, most-informative first.
     *
     * <p>The printer's advertised protocol goes first when it is known: a device
     * that announced itself over {@code _pdl-datastream} is telling us it wants
     * port 9100, and starting with IPP there just buys a guaranteed timeout before
     * the fallback. Everything else follows in capability order.
     */
    static List<PrintProtocol> backendOrder(DiscoveredPrinter printer) {
        var order = new ArrayList<PrintProtocol>();
        order.add(printer.protocol());
        for (var p : List.of(PrintProtocol.IPP, PrintProtocol.RAW, PrintProtocol.LPD)) {
            if (!order.contains(p)) {
                order.add(p);
            }
        }
        return order;
    }

    /**
     * Formats the printer admits to, or empty when it will not say.
     *
     * <p>A failure here is not a print failure: an unreachable Get-Printer-Attributes
     * just means we negotiate blind and send the source as-is, which is exactly the
     * behavior from before negotiation existed.
     */
    private static Set<String> supportedFormats(DiscoveredPrinter printer) {
        try {
            var formats = IppClient.supportedFormats(printer.ippUri());
            if (!formats.isEmpty()) {
                return formats;
            }
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not read supported formats from %s: %s"
                    .formatted(printer.name(), e.getMessage()));
        }
        // Fall back to what mDNS advertised, which needs no round trip.
        return PrintFormatNegotiator.advertisedFormats(printer);
    }

    /** Everything a byte-stream backend cannot carry, for the warning. */
    private static String describeDropped(JobAttributes job, Map<String, String> options) {
        var parts = new ArrayList<String>();
        if (!job.isEmpty()) {
            parts.add(job.describe());
        }
        // Options beyond the three JClaw interprets are equally lost on raw/LPD;
        // reporting only sides and color would understate what was dropped.
        for (var e : options.entrySet()) {
            if (!e.getKey().equals("sides") && !e.getKey().equals("print-color-mode")
                    && !e.getKey().equals("media")) {
                parts.add(e.getKey() + "=" + e.getValue());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** The printer's PWG raster preferences, or UNKNOWN when it will not say. */
    private static IppClient.RasterCapabilities rasterCapabilities(DiscoveredPrinter printer) {
        try {
            return IppClient.rasterCapabilities(printer.ippUri());
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not read raster capabilities from %s: %s"
                    .formatted(printer.name(), e.getMessage()));
            return IppClient.RasterCapabilities.UNKNOWN;
        }
    }

    /** One backend attempt. Returns null when the backend ran but the printer refused. */
    private static Outcome attempt(PrintProtocol protocol, DiscoveredPrinter printer,
                                   String jobName, String user, String documentFormat,
                                   byte[] document, JobAttributes job,
                                   Map<String, String> options) throws IOException {
        // Only IPP carries job-template attributes; the byte-stream backends drop
        // whatever was asked for. Recorded so the caller can say so out loud.
        var dropped = protocol == PrintProtocol.IPP || protocol == PrintProtocol.IPPS
                ? null : describeDropped(job, options);

        switch (protocol) {
            case IPP, IPPS -> {
                // Ask what this printer takes, then send something it takes.
                // Sending the source with its own MIME type is what produced
                // client-error-document-format-not-supported on a printer whose
                // list is octet-stream / jpeg / urf / pwg-raster.
                var prepared = PrintFormatNegotiator.prepare(document, documentFormat,
                        supportedFormats(printer), job, rasterCapabilities(printer));
                // Declare the media the raster was actually rendered for. Sending
                // Legal-sized pixels while the request says A4 is the mismatch that
                // stopped this printer in the first place.
                var effective = prepared.media() == null ? job
                        : new JobAttributes(job.sides(), job.colorMode(), prepared.media());
                var result = IppClient.print(printer.ippUri(), jobName, user,
                        prepared.format(), prepared.document(), effective, options);
                if (!result.accepted()) {
                    // Carry the IPP status name, which is the whole point of IPP
                    // being tried first. "client-error-document-format-not-supported"
                    // tells an operator to convert the file;
                    // "printer-stopped" tells them to go look at the device. Collapsing
                    // both to "rejected" throws away the only actionable part.
                    throw new IOException("printer rejected the job — " + result.message()
                            + " (sent as " + prepared.format() + ")");
                }
                // effective, not job: the negotiator overrides media to whatever the
                // printer says is loaded, so reporting the request hides the override
                // exactly when it mattered.
                var applied = effective.isEmpty() ? "" : " with " + effective.describe();
                var conversion = prepared.explanation() == null
                        ? "" : " — " + prepared.explanation();
                return new Outcome(protocol, result.jobId(), result.state(), true, null, List.of(),
                        "accepted as job " + result.jobId() + applied
                                + " (" + result.message() + ")" + conversion);
            }
            case RAW -> {
                var port = printer.protocol() == PrintProtocol.RAW
                        ? printer.port() : PrintProtocol.RAW.defaultPort();
                RawSocketClient.print(printer.host(), port, document, TIMEOUT_MS);
                return new Outcome(protocol, null, null, false, dropped, List.of(),
                        "streamed %d bytes to %s:%d — this backend returns no confirmation, "
                                .formatted(document.length, printer.host(), port)
                                + "so delivery is confirmed but printing is not");
            }
            case LPD -> {
                var port = printer.protocol() == PrintProtocol.LPD
                        ? printer.port() : PrintProtocol.LPD.defaultPort();
                LpdClient.print(printer.host(), port, LPD_QUEUE, jobName, user, document, TIMEOUT_MS);
                return new Outcome(protocol, null, null, false, dropped, List.of(),
                        "queued on %s:%d via LPD queue '%s' — the daemon acknowledged receipt "
                                .formatted(printer.host(), port, LPD_QUEUE)
                                + "but reports nothing about printing");
            }
        }
        return null;
    }

    /** Printer state, plus the job's own state when {@code jobId} is supplied. */
    public static String status(DiscoveredPrinter printer, Integer jobId) throws IOException {
        var printerState = IppClient.printerState(printer.ippUri());
        if (jobId == null) {
            return "printer " + printer.name() + ": " + printerState;
        }
        return "printer " + printer.name() + ": " + printerState
                + "; job " + jobId + ": " + IppClient.jobState(printer.ippUri(), jobId);
    }

    /**
     * Cancel a job. {@code printerUri} may be null for a job submitted by this
     * process, in which case it is recovered from the recent-jobs index.
     *
     * @throws IOException if the job is unknown and no printer was supplied
     */
    public static String cancel(int jobId, String printerUri, String user) throws IOException {
        var uri = printerUri != null && !printerUri.isBlank() ? printerUri : RECENT_JOBS.get(jobId);
        if (uri == null) {
            throw new IOException("Job " + jobId + " was not submitted by this instance — "
                    + "supply the printer host so the cancel can be addressed.");
        }
        return IppClient.cancel(uri, jobId, user);
    }

    /** Test seam: drop the recent-jobs index so tests don't leak state into each other. */
    static void clearRecentJobsForTest() {
        RECENT_JOBS.clear();
    }

    /** Test seam: register a job→printer mapping without performing a print. */
    static void rememberJobForTest(int jobId, String printerUri) {
        RECENT_JOBS.put(jobId, printerUri);
    }
}
