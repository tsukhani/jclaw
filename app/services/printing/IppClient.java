package services.printing;

import com.hp.jipp.encoding.Attribute;
import com.hp.jipp.encoding.AttributeGroup;
import com.hp.jipp.encoding.EnumType;
import com.hp.jipp.encoding.IntRangeType;
import com.hp.jipp.encoding.IntType;
import com.hp.jipp.encoding.IppInputStream;
import com.hp.jipp.encoding.IppPacket;
import com.hp.jipp.encoding.KeywordType;
import com.hp.jipp.encoding.Tag;
import com.hp.jipp.encoding.UntypedEnum;
import com.hp.jipp.model.Operation;
import com.hp.jipp.model.Types;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
import utils.HttpFactories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IPP print backend (JCLAW-911) — the primary one, and the only one that can
 * tell us what happened.
 *
 * <p>RFC 8010 frames an IPP request as a binary attribute packet followed
 * immediately by the document bytes, POSTed as {@code application/ipp}. HP JIPP
 * encodes and decodes the packet; the HTTP hop rides JClaw's shared OkHttp
 * client, per the tree-wide rule that outbound HTTP goes through
 * {@link HttpFactories} (pinned by ArchitectureTest).
 *
 * <p>The reason this is tried first is not speed — it is that IPP answers. A
 * successful Print-Job returns a job id and a status code, so the tool can report
 * "queued as job 42" rather than "the bytes went somewhere". The other two
 * backends cannot make that statement.
 */
public final class IppClient {

    private static final MediaType IPP = MediaType.get("application/ipp");

    /** RFC 8011 requires every request to declare its charset; IPP only defines this one. */
    private static final String CHARSET = "utf-8";

    /**
     * IPP request ids need only be unique within a connection, but a monotonic
     * counter makes a packet capture readable across jobs when several are in
     * flight. Wrapping at overflow is harmless.
     */
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);

    /** Outcome of a Print-Job. */
    public record PrintResult(boolean accepted, Integer jobId, String state, String message) {}

    private IppClient() {}

    /**
     * Submit a Print-Job.
     *
     * @param printerUri     {@code ipp://host:port/path} of the target printer
     * @param jobName        job name, shown on the printer's display
     * @param user           requesting user recorded on the job
     * @param documentFormat MIME type of {@code document} (e.g. {@code application/pdf});
     *                       when null the printer is left to sniff it, which is what
     *                       {@code application/octet-stream} means to a conforming printer
     * @param document       the bytes to print
     */
    public static PrintResult print(String printerUri, String jobName, String user,
                                    String documentFormat, byte[] document,
                                    JobAttributes job) throws IOException {
        return print(printerUri, jobName, user, documentFormat, document, job, Map.of());
    }

    /**
     * Submit a Print-Job carrying arbitrary job-template attributes.
     *
     * @param options IPP attribute → value for everything the printer announced;
     *                the three in {@code job} are folded in and take precedence,
     *                since they are what the raster was actually rendered for
     */
    public static PrintResult print(String printerUri, String jobName, String user,
                                    String documentFormat, byte[] document,
                                    JobAttributes job,
                                    Map<String, String> options) throws IOException {
        var operation = new ArrayList<Attribute<?>>();
        operation.add(Types.attributesCharset.of(CHARSET));
        operation.add(Types.attributesNaturalLanguage.of("en"));
        operation.add(Types.printerUri.of(URI.create(printerUri)));
        operation.add(Types.requestingUserName.of(user));
        operation.add(Types.jobName.of(jobName));
        if (documentFormat != null && !documentFormat.isBlank()) {
            operation.add(Types.documentFormat.of(documentFormat));
        }

        // Job-template attributes go in their OWN group, not alongside the
        // operation attributes. RFC 8011 §4.2 separates "how to address this
        // request" from "how to print this job", and a printer that finds `sides`
        // in the operation group is entitled to reject the whole request.
        var groups = new ArrayList<AttributeGroup>();
        groups.add(AttributeGroup.groupOf(Tag.operationAttributes, operation));

        // Every option the operator chose, by IPP attribute name. Built generically
        // rather than from a fixed list of three: the options came from the
        // printer, so anything it announced must be sendable — otherwise choosing
        // a tray in Settings changes nothing, which is worse than not offering it.
        var template = new ArrayList<Attribute<?>>();
        for (var option : effectiveOptions(job, options).entrySet()) {
            template.add(toJobAttribute(option.getKey(), option.getValue()));
        }
        if (!template.isEmpty()) {
            groups.add(AttributeGroup.groupOf(Tag.jobAttributes, template));
        }

        var packet = new IppPacket(Operation.printJob, REQUEST_ID.getAndIncrement(),
                groups.toArray(new AttributeGroup[0]));

        var response = exchange(printerUri, packet, document);
        var status = response.getStatus();
        var jobId = response.getValue(Tag.jobAttributes, Types.jobId);
        var jobState = response.getValue(Tag.jobAttributes, Types.jobState);
        // IPP status codes below 0x0100 are the successful family (successful-ok and
        // its warning variants). Anything at or above is a genuine rejection —
        // treating a warning as failure would fail jobs that actually printed.
        var accepted = status.getCode() < 0x0100;
        return new PrintResult(accepted, jobId,
                jobState == null ? null : jobState.getName(),
                status.getName());
    }

    /**
     * The operator's options, with the three the print path itself resolved
     * layered on top — those match the rendered raster and must win over a stale
     * saved value.
     */
    private static Map<String, String> effectiveOptions(
            JobAttributes job, Map<String, String> options) {
        var merged = new LinkedHashMap<String, String>(
                options == null ? Map.of() : options);
        if (job != null) {
            if (job.sides() != null) merged.put("sides", job.sides());
            if (job.colorMode() != null) merged.put("print-color-mode", job.colorMode());
            if (job.media() != null) merged.put("media", job.media());
        }
        return merged;
    }

    /**
     * Job-template attributes IPP carries with the <em>enum</em> tag (0x23) rather
     * than integer (0x21).
     *
     * <p>The distinction is invisible in the value — {@code print-quality=5} looks
     * like an integer — and it is not cosmetic. Sending print-quality as an integer
     * got job 11 accepted with
     * {@code successful-ok-ignored-or-substituted-attributes}: the printer parsed
     * the request, did not recognize the attribute as its enum, and quietly
     * dropped it. A silently ignored option is worse than a rejected one.
     *
     * <p>Identified from the printer's own {@code -supported} types, which report
     * {@code (1setOf enum)} for these and {@code (1setOf keyword)} or
     * {@code rangeOfInteger} for the rest.
     */
    private static final Set<String> ENUM_ATTRIBUTES = Set.of(
            "print-quality", "orientation-requested", "finishings", "printer-resolution");

    /**
     * Build one job-template attribute by name, with the tag IPP expects.
     *
     * <p>Three shapes: enum-tagged codes, plain integers (copies), and keywords.
     * Getting the tag wrong does not fail loudly — the printer accepts the job and
     * ignores the attribute, which is exactly how a setting appears to do nothing.
     */
    private static Attribute<?> toJobAttribute(String name, String value) {
        var numeric = value.matches("\\d+");
        if (numeric && ENUM_ATTRIBUTES.contains(name)) {
            return new EnumType<>(name, UntypedEnum::new)
                    .of(new UntypedEnum(Integer.parseInt(value)));
        }
        if (numeric) {
            return new IntType(name).of(Integer.parseInt(value));
        }
        return new KeywordType(name).of(value);
    }

    /**
     * The document formats this printer says it accepts, lowercased.
     *
     * <p>Asked over IPP rather than read from the mDNS {@code pdl} TXT record
     * because the two disagree: the Canon's TXT record and its
     * {@code document-format-supported} attribute list different sets, and the
     * IPP attribute is the one the Print-Job operation is actually validated
     * against. Empty when the printer will not say.
     */
    public static Set<String> supportedFormats(String printerUri) throws IOException {
        var packet = new IppPacket(Operation.getPrinterAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of(CHARSET),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri))));
        var response = exchange(printerUri, packet, null);
        var formats = response.getStrings(Tag.printerAttributes, Types.documentFormatSupported);
        if (formats.isEmpty()) {
            return Set.of();
        }
        return formats.stream().map(f -> f.toLowerCase().trim())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * How this printer wants PWG raster: resolution and color space.
     *
     * @param dpi       the resolution it declares, or 0 when it does not say
     * @param grayscale true when {@code sgray_8} is offered — a third the bytes
     *                  of sRGB, and this class of printer has a small spool
     * @param types     the raw {@code pwg-raster-document-type-supported} keywords
     */
    public record RasterCapabilities(int dpi, boolean grayscale, Set<String> types,
                                     String mediaReady) {
        /** What to assume when the printer will not say. */
        public static final RasterCapabilities UNKNOWN =
                new RasterCapabilities(0, false, Set.of(), null);
    }

    /**
     * Read the printer's PWG raster preferences.
     *
     * <p>Not optional detail. Guessing 300 DPI sRGB against a device that
     * declares 600 DPI and offers sgray_8 got the job accepted and then filled
     * the printer's spool — it stopped with an alarm and emitted nothing.
     */
    public static RasterCapabilities rasterCapabilities(String printerUri) throws IOException {
        var packet = new IppPacket(Operation.getPrinterAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of(CHARSET),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri))));
        var response = exchange(printerUri, packet, null);

        var types = response.getStrings(Tag.printerAttributes,
                Types.pwgRasterDocumentTypeSupported);
        var typeSet = types.stream().map(t -> t.toLowerCase().trim())
                .collect(Collectors.toUnmodifiableSet());

        var resolutions = response.getStrings(Tag.printerAttributes,
                Types.pwgRasterDocumentResolutionSupported);
        var dpi = 0;
        for (var r : resolutions) {
            // Rendered as e.g. "600dpi" or "600x600dpi"; take the first number.
            var digits = r.replaceAll("[^0-9x].*$", "").split("x")[0];
            if (!digits.isEmpty()) {
                dpi = Integer.parseInt(digits);
                break;
            }
        }
        // What is actually in the tray. Load-bearing, not informational: this
        // printer had Legal loaded, every job said A4, and it answered the
        // mismatch with printer-state-reasons=spool-area-full and stopped —
        // an error that reads like a size problem and is a paper problem.
        var ready = response.getStrings(Tag.printerAttributes, Types.mediaReady);
        var mediaReady = ready.isEmpty() ? null : ready.getFirst().trim();

        return new RasterCapabilities(dpi, typeSet.contains("sgray_8"), typeSet, mediaReady);
    }

    /**
     * One job option this printer announced, ready to render as a select or,
     * when it carries a range, a number input.
     *
     * @param name         the IPP job-template attribute name, e.g. {@code sides}
     * @param label        a human label for the UI
     * @param values       the values this printer accepts, from {@code <name>-supported};
     *                     empty for a numeric range
     * @param min          lower bound when this is a range, else null
     * @param max          upper bound when this is a range, else null
     * @param defaultValue what it uses when a job omits the attribute, or null
     */
    public record JobOption(String name, String label, List<OptionValue> values,
                            Integer min, Integer max, String defaultValue) {

        /** True when this is a number input rather than a select. */
        public boolean isRange() {
            return min != null && max != null;
        }
    }

    /**
     * One selectable value: what to show, and what to actually send.
     *
     * <p>They differ for enum-typed attributes. JIPP renders those for humans —
     * {@code print-quality} comes back as {@code "normal(4)"} and
     * {@code orientation-requested} as {@code "portrait(3)"} — but IPP carries the
     * integer. Offering the display string as the value would have the operator
     * save {@code print-quality=normal(4)}, which no printer accepts.
     *
     * @param value what goes on the wire
     * @param label what the operator reads
     */
    public record OptionValue(String value, String label) {}

    /** {@code name(code)} → the code; anything else is its own value. */
    private static final Pattern ENUM_DISPLAY =
            Pattern.compile("^(.*)\\((\\d+)\\)$");

    public static OptionValue toOptionValue(String rendered) {
        var m = ENUM_DISPLAY.matcher(rendered);
        if (m.matches()) {
            return new OptionValue(m.group(2), m.group(1).trim());
        }
        return new OptionValue(rendered, rendered);
    }

    /**
     * IPP job-template attributes worth offering an operator, mapped to labels.
     *
     * <p>An allow-list rather than "everything ending in -supported", because that
     * suffix is also used for things no one sets per job — {@code operations-supported},
     * {@code ipp-versions-supported}, {@code charset-supported}. Those are protocol
     * facts, not print options, and a form full of them would be worse than the
     * three hardcoded selects this replaced.
     *
     * <p>Which of these actually appear is entirely the printer's choice: it is
     * asked what it announces, not told what to have. A device offering
     * {@code output-bin} gets an output-bin select without anything here changing.
     */
    private static final Map<String, String> JOB_TEMPLATE_LABELS =
            LinkedHashMap.newLinkedHashMap(12);

    static {
        JOB_TEMPLATE_LABELS.put("media", "Paper");
        JOB_TEMPLATE_LABELS.put("media-type", "Paper type");
        JOB_TEMPLATE_LABELS.put("media-source", "Tray");
        JOB_TEMPLATE_LABELS.put("sides", "Sides");
        JOB_TEMPLATE_LABELS.put("print-color-mode", "Colour");
        JOB_TEMPLATE_LABELS.put("print-quality", "Quality");
        JOB_TEMPLATE_LABELS.put("printer-resolution", "Resolution");
        JOB_TEMPLATE_LABELS.put("orientation-requested", "Orientation");
        JOB_TEMPLATE_LABELS.put("output-bin", "Output bin");
        JOB_TEMPLATE_LABELS.put("print-scaling", "Scaling");
        JOB_TEMPLATE_LABELS.put("number-up", "Pages per sheet");
        JOB_TEMPLATE_LABELS.put("finishings", "Finishing");
    }

    /**
     * Job-template attributes IPP expresses as an integer range rather than a list.
     *
     * <p>Separate because they cannot be a select: {@code copies-supported} is
     * "1-99", not a set of values, and enumerating it would be a ninety-nine item
     * dropdown. The printer still decides the bounds.
     */
    private static final Map<String, String> RANGE_LABELS =
            Map.of("copies", "Copies", "job-priority", "Priority");

    private static final String SUPPORTED = "-supported";

    /**
     * Every job option this printer announces, discovered rather than assumed.
     *
     * <p>Walks the printer-attributes group looking for {@code <x>-supported},
     * which is IPP's own convention for "here are the values I accept for job
     * attribute {@code <x>}". The printer decides what appears; JClaw only decides
     * which attribute names are meaningful to an operator.
     *
     * <p>The alternative — a fixed set of selects with dynamic values — cannot
     * represent a printer that offers trays, output bins or quality levels, and
     * silently hides them.
     */
    public static List<JobOption> jobOptions(String printerUri) throws IOException {
        var packet = new IppPacket(Operation.getPrinterAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of(CHARSET),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri))));
        var response = exchange(printerUri, packet, null);
        var group = response.get(Tag.printerAttributes);
        if (group == null) {
            return List.of();
        }

        // Index by name once: the group is a flat list and defaults are looked up
        // per option, so scanning it repeatedly would be quadratic on a printer
        // that returns a couple of hundred attributes.
        var byName = new HashMap<String, Attribute<?>>();
        for (var attribute : group) {
            byName.put(attribute.getName(), attribute);
        }

        var options = new ArrayList<JobOption>();
        // JOB_TEMPLATE_LABELS order, not the printer's, so the form is stable
        // across devices instead of reshuffling with each vendor's attribute order.
        for (var entry : JOB_TEMPLATE_LABELS.entrySet()) {
            var supported = byName.get(entry.getKey() + SUPPORTED);
            if (supported == null) {
                continue;
            }
            var values = supported.strings();
            if (values == null || values.isEmpty()) {
                continue;
            }
            var defaults = byName.get(entry.getKey() + "-default");
            var defaultValue = defaults == null || defaults.strings().isEmpty()
                    ? null : toOptionValue(defaults.strings().getFirst()).label();
            options.add(new JobOption(entry.getKey(), entry.getValue(),
                    values.stream().map(IppClient::toOptionValue).toList(),
                    null, null, defaultValue));
        }

        // Ranges last: they render as number inputs and read as a different kind
        // of control, so grouping them keeps the form scannable.
        for (var entry : RANGE_LABELS.entrySet()) {
            var range = group.getValue(new IntRangeType(
                    entry.getKey() + SUPPORTED));
            if (range == null) {
                continue;
            }
            var defaults = byName.get(entry.getKey() + "-default");
            var defaultValue = defaults == null || defaults.strings().isEmpty()
                    ? null : defaults.strings().getFirst();
            options.add(new JobOption(entry.getKey(), entry.getValue(), List.of(),
                    range.getFirst(), range.getLast(), defaultValue));
        }
        return List.copyOf(options);
    }

    /** Query a printer's own state (Get-Printer-Attributes). */
    public static String printerState(String printerUri) throws IOException {
        var packet = new IppPacket(Operation.getPrinterAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of(CHARSET),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri))));
        var response = exchange(printerUri, packet, null);
        var state = response.getValue(Tag.printerAttributes, Types.printerState);
        var reasons = response.getStrings(Tag.printerAttributes, Types.printerStateReasons);
        var text = state == null ? "unknown" : state.getName();
        return reasons.isEmpty() ? text : text + " (" + String.join(", ", reasons) + ")";
    }

    /** Query one job's state (Get-Job-Attributes). */
    public static String jobState(String printerUri, int jobId) throws IOException {
        var packet = new IppPacket(Operation.getJobAttributes, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of(CHARSET),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri)),
                        Types.jobId.of(jobId)));
        var response = exchange(printerUri, packet, null);
        var state = response.getValue(Tag.jobAttributes, Types.jobState);
        return state == null ? "unknown" : state.getName();
    }

    /** Cancel a job (Cancel-Job). Returns the IPP status name. */
    public static String cancel(String printerUri, int jobId, String user) throws IOException {
        var packet = new IppPacket(Operation.cancelJob, REQUEST_ID.getAndIncrement(),
                AttributeGroup.groupOf(Tag.operationAttributes,
                        Types.attributesCharset.of(CHARSET),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(URI.create(printerUri)),
                        Types.jobId.of(jobId),
                        Types.requestingUserName.of(user)));
        var response = exchange(printerUri, packet, null);
        return response.getStatus().getName();
    }

    /**
     * POST an IPP packet (optionally followed by document bytes) and decode the reply.
     *
     * <p>The {@code ipp://} scheme is an IPP-level addressing convention, not a
     * transport — the actual hop is HTTP(S), so the scheme is rewritten here. Getting
     * this wrong produces an OkHttp "unexpected url" that reads like a malformed
     * printer address rather than a client bug.
     */
    private static IppPacket exchange(String printerUri, IppPacket packet, byte[] document)
            throws IOException {
        var httpUrl = printerUri.replaceFirst("^ipps://", "https://").replaceFirst("^ipp://", "http://");

        var body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return IPP;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                // Stream straight into the sink rather than buffering the whole
                // job: a print document is arbitrarily large and this path is the
                // one that would OOM on a 200 MB PDF.
                packet.write(sink.outputStream());
                if (document != null) {
                    sink.write(document);
                }
            }
        };

        var request = new Request.Builder().url(httpUrl).post(body).build();
        try (var response = HttpFactories.general().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("IPP request failed: HTTP " + response.code());
            }
            try (var in = new IppInputStream(response.body().byteStream())) {
                return in.readPacket();
            }
        }
    }

    /** Encode a packet to bytes. Exposed for tests, which assert the wire form without a printer. */
    static byte[] encode(IppPacket packet) throws IOException {
        var out = new ByteArrayOutputStream();
        packet.write(out);
        return out.toByteArray();
    }
}
