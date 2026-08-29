package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import services.WorkspaceFiles;
import services.printing.DiscoveredPrinter;
import services.printing.JobAttributes;
import services.printing.PrintDispatcher;
import services.printing.PrintProtocol;
import services.printing.PrinterDefaults;
import services.printing.PrinterDiscovery;
import utils.HttpKeys;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Network printing (JCLAW-911). Discovers printers over mDNS and sends jobs
 * without CUPS, {@code lp}, or any OS print subsystem — everything is JVM-native,
 * so the tool behaves the same on macOS, Linux and Windows and works in a
 * container that has no printing stack at all.
 *
 * <p>Printing is irreversible in a way most tools are not: paper comes out of a
 * device in someone's room, and there is no undo. So the destination is always a
 * human's choice, never the tool's: {@code print} uses an explicit printer, or
 * the default the operator saved under Settings → Printers, and refuses when
 * neither exists. A saved default is still a human choice — made once, in
 * Settings — which is the distinction that matters here, as opposed to inferring
 * a target from whatever happened to answer a discovery browse.
 *
 * <p>It also reports honestly when the backend it fell back to cannot confirm
 * the job printed, or could not carry the requested job options (see
 * {@link PrintDispatcher.Outcome}).
 */
public class PrinterTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "printer";

    // Each name appears in three places — actions(), the schema enum, the dispatch
    // switch — which is what makes drift between them silent.
    private static final String ACTION_DISCOVER = "discover";
    private static final String ACTION_PRINT = "print";
    private static final String ACTION_STATUS = "status";
    private static final String ACTION_CANCEL = "cancel";

    private static final String ARG_ACTION = "action";
    private static final String ARG_PRINTER = "printer";
    private static final String ARG_JOB_ID = "jobId";
    private static final String ARG_PATH = "path";
    private static final String ARG_TEXT = "text";
    private static final String ARG_SIDES = "sides";
    private static final String ARG_COLOR = "color";
    private static final String ARG_MEDIA = "media";

    /** Upper bound on a job we will read into memory and push to a printer. */
    private static final long MAX_DOCUMENT_BYTES = 64L * 1024 * 1024;

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String category() {
        return "Utilities";
    }

    @Override
    public String icon() {
        return "printer";
    }

    @Override
    public boolean parallelSafe() {
        // Discovery and status are read-only, but print is not: two jobs racing to
        // the same device interleave at the printer, not here. Serialized because
        // the failure mode is physical and unrecoverable.
        return false;
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_DISCOVER, "Find printers on the local network over mDNS/Bonjour"),
                new ToolAction(ACTION_PRINT, "Send a workspace file or literal text to a printer"),
                new ToolAction(ACTION_STATUS, "Report a printer's state, and a job's state when given a job id"),
                new ToolAction(ACTION_CANCEL, "Cancel a queued print job by id"));
    }

    @Override
    public String summary() {
        return "Discover network printers and print documents to them (no CUPS required).";
    }

    @Override
    public String description() {
        return "Discover printers on the local network and print to them. Actions: "
                + "'discover' lists printers found over mDNS; 'print' sends a workspace file "
                + "(path) or literal text to a named printer; 'status' reports printer/job state; "
                + "'cancel' cancels a job. When a default printer is saved, 'print' already uses "
                + "it — call 'print' directly rather than discovering first, which costs seconds "
                + "and finds what is already configured. Discover only when there is no default, "
                + "when 'print' reports the default is not answering, or when the user asks for a "
                + "different printer; never guess a target, because printing cannot be undone. "
                + "Leave sides, color and media unset unless the user asked for them; the "
                + "printer's own settings are right more often than a guess, and a wrong one "
                + "wastes paper.";
    }

    @Override
    public Map<String, Object> parameters() {
        // Map.ofEntries, not Map.of: the property set passed ten entries when the
        // job-template attributes landed, and Map.of has no eleven-pair overload.
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.<String, Object>ofEntries(
                        Map.entry(ARG_ACTION, prop(SchemaKeys.STRING,
                                List.of(ACTION_DISCOVER, ACTION_PRINT, ACTION_STATUS, ACTION_CANCEL),
                                "Which operation to perform.")),
                        Map.entry(ARG_PRINTER, prop(SchemaKeys.STRING, null,
                                "Printer name or host, as reported by 'discover'. Required for "
                                        + "'print'. A bare hostname or IP is also accepted when "
                                        + "mDNS is unavailable.")),
                        Map.entry("host", prop(SchemaKeys.STRING, null,
                                "Explicit printer host, bypassing discovery. Use when the network "
                                        + "blocks mDNS but the address is known.")),
                        Map.entry("port", prop(SchemaKeys.INTEGER, null,
                                "Explicit port. Defaults to the protocol's standard port "
                                        + "(631 IPP, 9100 raw, 515 LPD).")),
                        Map.entry("protocol", prop(SchemaKeys.STRING,
                                List.of("IPP", "IPPS", "RAW", "LPD"),
                                "Force a protocol instead of auto-selecting. Rarely needed.")),
                        Map.entry(ARG_PATH, prop(SchemaKeys.STRING, null,
                                "Workspace-relative path of the document to print (PDF, PostScript "
                                        + "or plain text). Mutually exclusive with 'text'.")),
                        Map.entry(ARG_TEXT, prop(SchemaKeys.STRING, null,
                                "Literal text to print. Mutually exclusive with 'path'.")),
                        Map.entry(ARG_JOB_ID, prop(SchemaKeys.INTEGER, null,
                                "Job id, as returned by 'print'. Required for 'cancel'.")),
                        // These three are omitted far more often than they are set, and
                        // every one of them is a way to break a job that would otherwise
                        // have printed — so each says when to set it rather than what to
                        // set it to. An earlier version listed example values here and
                        // the model reliably chose one, sending Letter to a Legal-
                        // registered tray (E59) and duplex to a printer with no duplexer.
                        Map.entry(ARG_SIDES, prop(SchemaKeys.STRING, JobAttributes.SIDES_VALUES,
                                "Set only when the user asks for double-sided; omitted means the "
                                        + "printer's own default. Requesting duplex on a printer "
                                        + "without one gets the job substituted or refused. "
                                        + "IPP printers only — other backends cannot carry it.")),
                        Map.entry(ARG_COLOR, prop(SchemaKeys.STRING, JobAttributes.COLOR_MODE_VALUES,
                                "Set only when the user asks for colour or black-and-white; "
                                        + "omitted means the printer's own default. IPP printers only.")),
                        Map.entry(ARG_MEDIA, prop(SchemaKeys.STRING, null,
                                "Set only when the user names a paper size or tray. Omitting it is "
                                        + "the correct default: JClaw reads the media the printer "
                                        + "reports as loaded and declares that, whereas a guessed "
                                        + "size that disagrees with the tray is refused and no page "
                                        + "comes out. IPP printers only."))),
                SchemaKeys.REQUIRED, List.of(ARG_ACTION));
    }

    /** One JSON-Schema property. {@code enumValues} null when the value is free-form. */
    private static Map<String, Object> prop(String type, List<String> enumValues, String description) {
        if (enumValues == null) {
            return Map.of(SchemaKeys.TYPE, type, SchemaKeys.DESCRIPTION, description);
        }
        return Map.of(SchemaKeys.TYPE, type, SchemaKeys.ENUM, enumValues,
                SchemaKeys.DESCRIPTION, description);
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException _) {
            return "Error: arguments were not a JSON object.";
        }
        var action = str(args, ARG_ACTION);
        if (action == null) {
            return "Error: missing required 'action' argument "
                    + "(one of: discover, print, status, cancel).";
        }
        try {
            return switch (action.toLowerCase()) {
                case ACTION_DISCOVER -> discover();
                case ACTION_PRINT -> print(args, agent);
                case ACTION_STATUS -> status(args);
                case ACTION_CANCEL -> cancel(args, agent);
                default -> "Error: unknown action '" + action
                        + "'. Valid actions: discover, print, status, cancel.";
            };
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ─── Actions ───

    private static String discover() {
        var printers = PrinterDiscovery.discover();
        if (printers.isEmpty()) {
            return "No printers found on the local network. mDNS is link-local, so this also "
                    + "reports empty when the host has no multicast route (many containers and "
                    + "VPNs). If the printer's address is known, pass it as 'host'.";
        }
        var sb = new StringBuilder("Found ").append(printers.size()).append(" printer(s):\n");
        for (var p : printers) {
            sb.append("- ").append(p.name())
                    .append(" — ").append(p.host()).append(':').append(p.port())
                    .append(" (").append(p.protocol()).append(')');
            var pdl = p.capabilities().get("pdl");
            if (pdl != null) {
                sb.append(" formats: ").append(pdl);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String print(JsonObject args, Agent agent) throws IOException {
        var target = resolveTarget(args);
        if (target == null) {
            return "Error: 'print' needs a 'printer' (from discover) or an explicit 'host', "
                    + "or a default printer saved under Settings -> Printers.";
        }
        // Only when the default was used — an explicitly named target is the operator's
        // call and gets attempted as given. A default outlives the DHCP lease it was
        // saved under, and reaching a dead one costs a 60s IPP timeout before falling
        // back to a backend that cannot confirm anything printed.
        if (str(args, "host") == null && str(args, ARG_PRINTER) == null
                && !PrinterDiscovery.reachable(target.host(), target.port())) {
            return defaultNotAnswering(target);
        }

        var path = str(args, ARG_PATH);
        var text = str(args, ARG_TEXT);
        if (path != null && text != null) {
            return "Error: pass either 'path' or 'text', not both.";
        }

        byte[] document;
        String documentFormat;
        String jobName;
        if (path != null) {
            var root = WorkspaceFiles.workspacePath(agent.name);
            var resolved = WorkspacePathGuard.resolveContained(root, path);
            if (resolved == null) {
                return "Error: '" + path + "' resolves outside the agent workspace.";
            }
            if (!Files.isRegularFile(resolved)) {
                return "Error: no such file in the workspace: " + path;
            }
            var size = Files.size(resolved);
            if (size > MAX_DOCUMENT_BYTES) {
                return "Error: '%s' is %d bytes, over the %d-byte print limit."
                        .formatted(path, size, MAX_DOCUMENT_BYTES);
            }
            document = Files.readAllBytes(resolved);
            documentFormat = formatFor(path);
            jobName = resolved.getFileName().toString();
        } else if (text != null) {
            document = text.getBytes(StandardCharsets.UTF_8);
            documentFormat = "text/plain";
            jobName = "jclaw-text";
        } else {
            return "Error: 'print' needs either 'path' (a workspace file) or 'text'.";
        }

        if (document.length == 0) {
            return "Error: refusing to print an empty document.";
        }

        // Per-call arguments win; anything omitted falls back to the operator's
        // saved job options, then to the printer's own defaults. Merged per field
        // rather than all-or-nothing so asking for one override (say monochrome)
        // does not silently discard a configured duplex default.
        var saved = PrinterDefaults.load();
        var job = new JobAttributes(
                firstNonNull(str(args, ARG_SIDES), saved.sides()),
                firstNonNull(str(args, ARG_COLOR), saved.color()),
                firstNonNull(str(args, ARG_MEDIA), saved.media()));
        var invalid = job.validationError();
        if (invalid != null) {
            // Rejected here rather than at the printer, which would answer with an
            // IPP status that names no offending value.
            return "Error: " + invalid;
        }

        var outcome = PrintDispatcher.print(target, jobName, agent.name, documentFormat,
                document, job, saved.options());
        var verdict = new StringBuilder(outcome.verified()
                ? "Printed via " + outcome.protocol() + " — " + outcome.detail()
                // Said plainly because the model will otherwise report this as a
                // confirmed print, which it is not.
                : "Sent via " + outcome.protocol() + " — " + outcome.detail()
                        + ". NOTE: this backend cannot confirm the document printed; "
                        + "check the printer if confirmation matters.");
        if (!outcome.skipped().isEmpty()) {
            // The job worked, but not the way it was asked to. Without this the
            // operator sees a clean success and never learns their preferred
            // backend is broken — until the fallback breaks too and printing stops
            // with no history of the first failure.
            verdict.append(" (Tried first, without success: ")
                    .append(String.join("; ", outcome.skipped())).append(".)");
        }
        if (outcome.droppedAttributes() != null) {
            // The job printed, but not the way it was asked for. Silence here means
            // the operator finds out from the paper.
            verdict.append(" WARNING: ").append(outcome.droppedAttributes())
                    .append(" could NOT be applied — ").append(outcome.protocol())
                    .append(" carries no job attributes, so the printer used its own defaults. ")
                    .append("Use an IPP-capable printer if these matter.");
        }
        return verdict.toString();
    }

    private static String status(JsonObject args) throws IOException {
        var target = resolveTarget(args);
        if (target == null) {
            return "Error: 'status' needs a 'printer' (from discover) or an explicit 'host', "
                    + "or a default printer saved under Settings -> Printers.";
        }
        return PrintDispatcher.status(target, intOrNull(args, ARG_JOB_ID));
    }

    private static String cancel(JsonObject args, Agent agent) throws IOException {
        var jobId = intOrNull(args, ARG_JOB_ID);
        if (jobId == null) {
            return "Error: 'cancel' needs a numeric 'jobId' (returned by 'print').";
        }
        var target = resolveTarget(args);
        var uri = target == null ? null : target.ippUri();
        return "Cancel requested for job " + jobId + ": "
                + PrintDispatcher.cancel(jobId, uri, agent.name);
    }

    // ─── Helpers ───

    /**
     * Work out which printer the caller means: an explicit host wins, otherwise the
     * name is matched against a fresh discovery. Returns null when neither was given.
     *
     * <p>Discovery is re-run rather than cached — see {@link PrinterDiscovery} for
     * why a stale address is worse than a slow lookup.
     */
    private static DiscoveredPrinter resolveTarget(JsonObject args) {
        var protocol = PrintProtocol.parse(str(args, "protocol"));
        var host = str(args, "host");
        if (host != null) {
            return PrinterDiscovery.direct(host, intOrNull(args, "port"), protocol);
        }
        var name = str(args, ARG_PRINTER);
        if (name == null) {
            // Fall back to the operator's default from Settings → Printers. This is
            // not the tool guessing: the distinction that matters is whether a human
            // chose the destination, and this one was chosen once in Settings rather
            // than inferred per call. With no default saved, print still refuses.
            var saved = PrinterDefaults.load();
            if (saved.isUnset()) {
                return null;
            }
            return PrinterDiscovery.direct(saved.host(), saved.port(),
                    PrintProtocol.parse(saved.protocol()));
        }
        var hits = PrinterDiscovery.matching(PrinterDiscovery.discover(Duration.ofSeconds(2)), name);
        if (hits.isEmpty()) {
            // Treat an unmatched name as a hostname rather than failing: the operator
            // may know the address on a network where mDNS is blocked.
            return PrinterDiscovery.direct(name, intOrNull(args, "port"), protocol);
        }
        return hits.getFirst();
    }

    /**
     * The saved default did not answer. Browse and hand back what is actually on the
     * network, so the operator picks a replacement in one turn instead of being told
     * to go and discover for themselves.
     */
    private static String defaultNotAnswering(DiscoveredPrinter stale) {
        var where = "%s at %s:%d".formatted(stale.name(), stale.host(), stale.port());
        var found = PrinterDiscovery.discover();
        if (found.isEmpty()) {
            return "Error: the default printer (" + where + ") is not answering, and no "
                    + "other printer answered an mDNS browse. Check it is powered on and "
                    + "on this network, or save a new default under Settings -> Printers.";
        }
        var alternatives = new StringBuilder();
        for (var p : found) {
            alternatives.append("\n- ").append(p.name()).append(" — ").append(p.host())
                    .append(':').append(p.port()).append(" (").append(p.protocol()).append(')');
        }
        return "Error: the default printer (" + where + ") is not answering. These are on "
                + "the network now — ask the user which to use, then pass it as 'host':"
                + alternatives;
    }

    /** MIME type from the filename, or null to let the printer sniff. */
    public static String formatFor(String path) {
        var lower = path.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".ps")) return "application/postscript";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        // Must track what PrintRenderer.readImage decodes: it routes on the "image/"
        // prefix and otherwise falls through to its text branch, and the negotiator's
        // native pass-through matches on this exact string.
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return "image/tiff";
        if (lower.endsWith(".webp")) return "image/webp";
        // Deliberately not a guess: a conforming printer treats octet-stream as
        // "sniff it yourself", which beats asserting a format that is wrong.
        return HttpKeys.APPLICATION_OCTET_STREAM;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String str(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        var v = args.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer intOrNull(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException _) {
            return null;
        }
    }
}
