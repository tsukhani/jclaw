package services.printing;

import services.ConfigService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The operator's default printer and job options (JCLAW-911), stored in the
 * Config DB under the {@code printer.default.*} namespace.
 *
 * <p>Exists so an agent does not have to be told the address every time. That is
 * not the tool guessing a target — the distinction the printer tool cares about
 * is whether a <em>human</em> chose the destination, and a saved default is a
 * human choice made once in Settings rather than inferred per call.
 *
 * <p>The host is stored, not the mDNS name. Names are how printers advertise
 * themselves and change with firmware updates and vendor whims; the address is
 * what a job is actually sent to. The name is kept alongside for display only.
 */
public final class PrinterDefaults {

    static final String KEY_NAME = "printer.default.name";
    static final String KEY_HOST = "printer.default.host";
    static final String KEY_PORT = "printer.default.port";
    static final String KEY_PROTOCOL = "printer.default.protocol";
    /**
     * Prefix for per-option keys: {@code printer.default.option.<ipp-attribute>}.
     *
     * <p>One key per IPP attribute rather than three named columns, because the
     * options a printer offers are the printer's business — a device with trays
     * and output bins needs somewhere to put those, and adding a column per
     * vendor feature does not scale.
     */
    static final String KEY_OPTION_PREFIX = "printer.default.option.";

    /** Job-template attributes the print path itself reads. */
    public static final String OPT_SIDES = "sides";
    public static final String OPT_COLOR = "print-color-mode";
    public static final String OPT_MEDIA = "media";

    /**
     * @param name     display name, or null
     * @param host     address jobs are sent to; null means no default is configured
     * @param port     port, or 0 to use the protocol's standard port
     * @param protocol forced protocol, or null to auto-select
     * @param options  IPP job attribute → value, for whatever this printer offers.
     *                  Empty means "use the printer's own defaults throughout"
     */
    public record Defaults(String name, String host, int port, String protocol,
                           Map<String, String> options) {

        public Defaults {
            options = options == null ? Map.of() : Map.copyOf(options);
        }

        /** Convenience for the three the print path itself consumes. */
        public String sides() {
            return options.get(OPT_SIDES);
        }

        public String color() {
            return options.get(OPT_COLOR);
        }

        public String media() {
            return options.get(OPT_MEDIA);
        }

        /** True when no default printer has been chosen. */
        public boolean isUnset() {
            return host == null || host.isBlank();
        }

        /** True when this default points at the given discovered printer. */
        public boolean matches(String otherHost, int otherPort) {
            if (isUnset()) {
                return false;
            }
            // Port 0 means "whatever the protocol's standard is", so a saved default
            // with no explicit port still matches the printer it was chosen from.
            return host.equals(otherHost) && (port == 0 || port == otherPort);
        }

        /** The subset the raster/IPP path consumes directly. */
        public JobAttributes jobAttributes() {
            return new JobAttributes(sides(), color(), media());
        }
    }

    /** No default configured. */
    public static final Defaults NONE = new Defaults(null, null, 0, null, Map.of());

    private PrinterDefaults() {}

    /** Read the saved default, or {@link #NONE} when unset. */
    public static Defaults load() {
        var host = blankToNull(ConfigService.get(KEY_HOST));
        if (host == null) {
            return NONE;
        }
        return new Defaults(
                blankToNull(ConfigService.get(KEY_NAME)),
                host,
                parsePort(ConfigService.get(KEY_PORT)),
                blankToNull(ConfigService.get(KEY_PROTOCOL)),
                loadOptions());
    }

    /** Every saved {@code printer.default.option.*} row. */
    private static Map<String, String> loadOptions() {
        var options = new LinkedHashMap<String, String>();
        for (var row : ConfigService.listAll()) {
            if (row.key != null && row.key.startsWith(KEY_OPTION_PREFIX)) {
                var value = blankToNull(row.value);
                if (value != null) {
                    options.put(row.key.substring(KEY_OPTION_PREFIX.length()), value);
                }
            }
        }
        return options;
    }

    /**
     * Invert what {@link #set} writes: it stores {@code ""} for an absent value,
     * so reading must turn that back into null.
     *
     * <p>Without this, clearing one job option leaves {@code ""} in config, which
     * {@link JobAttributes#validationError()} then rejects as an invalid keyword —
     * so a printer that had duplex switched off would refuse every subsequent job
     * with "invalid 'sides' value ''". Caught by cross-test pollution rather than
     * by the round-trip test, which only ever set real values or cleared all of them.
     */
    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Persist the default printer and its job options. */
    public static void save(Defaults d) {
        set(KEY_NAME, d.name());
        set(KEY_HOST, d.host());
        set(KEY_PORT, d.port() > 0 ? String.valueOf(d.port()) : null);
        set(KEY_PROTOCOL, d.protocol());

        // Sweep the three named columns this namespace used before options became
        // a map. They are dead the moment an option row exists, and left in place
        // they surface as unmanaged-config-key noise forever. Cheap enough to run
        // on every save that a dedicated migration would be the heavier option.
        for (var legacy : List.of("sides", "color", "media")) {
            ConfigService.delete("printer.default." + legacy);
        }

        // Drop every existing option row before writing the new set. Merging
        // instead would strand options from a previously-selected printer — a
        // tray setting from an office laser silently riding along to a home inkjet
        // that has no such tray. Deleted rather than blanked so the Settings
        // unmanaged-keys diagnostic does not fill with empty printer options.
        for (var stale : List.copyOf(loadOptions().keySet())) {
            ConfigService.delete(KEY_OPTION_PREFIX + stale);
        }
        for (var option : d.options().entrySet()) {
            set(KEY_OPTION_PREFIX + option.getKey(), option.getValue());
        }
    }

    /** Remove the default entirely, so the tool goes back to requiring an explicit target. */
    public static void clear() {
        save(NONE);
    }

    /** Write, or blank the key when the value is absent — no stale halves left behind. */
    private static void set(String key, String value) {
        ConfigService.set(key, value == null ? "" : value);
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            // A hand-edited config row should not break printing; the protocol's
            // standard port is the safe reading of "not a number".
            return 0;
        }
    }
}
