package controllers;

import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.printing.IppClient;
import services.printing.JobAttributes;
import services.printing.PrintProtocol;
import services.printing.PrinterDefaults;
import services.printing.PrinterDiscovery;
import utils.ApiResponses;

import java.util.LinkedHashMap;
import java.util.List;

import static utils.GsonHolder.GSON;

/**
 * Printer discovery and the operator's default-printer choice (JCLAW-911).
 *
 * <p>Backs Settings → Printers. Discovery is a live mDNS browse on every call —
 * see {@link PrinterDiscovery} for why nothing is cached — so the panel shows
 * what is reachable now rather than what was reachable when the page loaded.
 */
@With(AuthCheck.class)
public class ApiPrintersController extends Controller {

    private static final String CATEGORY = "printer";
    private static final String KEY_OPTIONS = "options";

    /**
     * @param name         advertised printer name
     * @param host         resolved address
     * @param port         advertised port
     * @param protocol     the protocol it was advertised under
     * @param formats      the {@code pdl} TXT record (supported document formats), or null
     * @param isDefault    whether this printer is the saved default
     */
    public record PrinterEntry(String name, String host, int port, String protocol,
                               String formats, boolean isDefault) {}

    /** GET /api/printers — live mDNS browse, with the saved default flagged. */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PrinterEntry.class))))
    public static void discover() {
        var saved = PrinterDefaults.load();
        var entries = PrinterDiscovery.discover().stream()
                .map(p -> new PrinterEntry(p.name(), p.host(), p.port(), p.protocol().name(),
                        p.capabilities().get("pdl"),
                        saved.matches(p.host(), p.port())))
                .toList();
        renderJSON(GSON.toJson(entries));
    }

    /** GET /api/printers/default — the saved default printer and job options. */
    public static void getDefault() {
        renderJSON(GSON.toJson(PrinterDefaults.load()));
    }

    /**
     * Reachability of the saved default, as its own call so the Printers panel can
     * render immediately and fill the badge in when the probe returns.
     *
     * @param configured false when no default is saved, which is not a fault
     * @param reachable  whether anything answered at the saved address
     */
    public record DefaultStatus(boolean configured, boolean reachable, String host, int port) {}

    /** GET /api/printers/default/status — is the saved default still answering? */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DefaultStatus.class)))
    @Operation(summary = "Probe whether the saved default printer answers at its address")
    public static void defaultStatus() {
        var saved = PrinterDefaults.load();
        if (saved.isUnset()) {
            renderJSON(GSON.toJson(new DefaultStatus(false, false, null, 0)));
        }
        var protocol = PrintProtocol.parse(saved.protocol());
        var port = saved.port() > 0 ? saved.port() : protocol.defaultPort();
        renderJSON(GSON.toJson(new DefaultStatus(
                true, PrinterDiscovery.reachable(saved.host(), port), saved.host(), port)));
    }

    /**
     * PUT /api/printers/default — save the default printer and its job options.
     *
     * <p>Body mirrors {@link PrinterDefaults}. An empty {@code host} clears the
     * default entirely, which is the only way back to "no default" — leaving a
     * half-cleared default pointing at a printer that has been unplugged is worse
     * than having none.
     */
    @SuppressWarnings("java:S2259")
    public static void saveDefault() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var host = str(body, "host");
        if (host == null) {
            PrinterDefaults.clear();
            ApiResponses.ok("cleared", true);
            return;
        }

        // Options arrive as an IPP attribute → value map, whatever the printer
        // offered. Named fields here would mean a code change every time a vendor
        // exposes something new, which is the opposite of reading capabilities.
        var options = new LinkedHashMap<String, String>();
        if (body.has(KEY_OPTIONS) && body.get(KEY_OPTIONS).isJsonObject()) {
            for (var entry : body.getAsJsonObject(KEY_OPTIONS).entrySet()) {
                var value = entry.getValue().isJsonNull() ? null : entry.getValue().getAsString().trim();
                if (value != null && !value.isEmpty()) {
                    options.put(entry.getKey(), value);
                }
            }
        }

        // Validate only the attributes JClaw itself interprets. The rest are the
        // printer's vocabulary — it announced them, and it is the authority on
        // whether they are valid, so second-guessing here would reject values a
        // device legitimately offers.
        var interpreted = new JobAttributes(options.get(PrinterDefaults.OPT_SIDES),
                options.get(PrinterDefaults.OPT_COLOR), options.get(PrinterDefaults.OPT_MEDIA));
        var invalid = interpreted.validationError();
        if (invalid != null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, invalid);
            return;
        }

        var saved = new PrinterDefaults.Defaults(
                str(body, "name"), host,
                body.has("port") && !body.get("port").isJsonNull() ? body.get("port").getAsInt() : 0,
                str(body, "protocol"), options);
        PrinterDefaults.save(saved);
        renderJSON(GSON.toJson(saved));
    }

    /**
     * @param options     one entry per job option the printer announced, in a
     *                    stable order; empty when it would not say
     * @param protocols   the transports the tool speaks — never printer-specific
     * @param mediaReady  the paper physically loaded, so the UI can mark it
     * @param fromPrinter true when {@code options} came from the device
     */
    public record JobOptionsResponse(List<IppClient.JobOption> options, List<String> protocols,
                                     String mediaReady, boolean fromPrinter) {}

    /**
     * GET /api/printers/options — the job options this printer offers.
     *
     * <p>Discovered from the device, not declared here: whatever it announces as
     * {@code <x>-supported} becomes a select. A printer with trays and output bins
     * surfaces those; one that only does {@code sides=one-sided} offers exactly
     * that and nothing an operator could save and have rejected.
     *
     * <p>Without a reachable {@code host} the response carries no options and
     * {@code fromPrinter=false}, which the UI renders as "pick a printer first"
     * rather than as an empty form.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = JobOptionsResponse.class)))
    public static void options(String host, Integer port, String protocol) {
        var protocols = List.of("IPP", "IPPS", "RAW", "LPD");
        if (host == null || host.isBlank()) {
            renderJSON(GSON.toJson(new JobOptionsResponse(List.of(), protocols, null, false)));
        }

        var printer = PrinterDiscovery.direct(host, port, PrintProtocol.parse(protocol));
        List<IppClient.JobOption> discovered;
        String mediaReady = null;
        try {
            discovered = IppClient.jobOptions(printer.ippUri());
            mediaReady = IppClient.rasterCapabilities(printer.ippUri()).mediaReady();
        } catch (Exception e) {
            // Unreachable, or not an IPP printer. An empty list with
            // fromPrinter=false is honest; inventing options would offer settings
            // this device may reject.
            EventLogger.warn(CATEGORY, "Could not read job options from %s: %s"
                    .formatted(host, e.getMessage()));
            discovered = List.of();
        }
        renderJSON(GSON.toJson(new JobOptionsResponse(discovered, protocols, mediaReady,
                !discovered.isEmpty())));
    }

    private static String str(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            return null;
        }
        var v = body.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }
}
