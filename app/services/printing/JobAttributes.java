package services.printing;

import com.hp.jipp.model.PrintColorMode;
import com.hp.jipp.model.Sides;

import java.util.ArrayList;
import java.util.List;

/**
 * IPP job-template attributes for one print job (JCLAW-911) — how to print,
 * as opposed to what.
 *
 * <p><b>These are IPP-only.</b> Raw socket and LPD have no way to express them:
 * both hand the printer an undifferentiated byte stream, so a duplex request
 * that falls back to port 9100 prints single-sided. That is why
 * {@link PrintDispatcher} reports when a fallback dropped them rather than
 * letting the job come out quietly wrong — the operator asked for two-sided and
 * would otherwise have to notice from the paper.
 *
 * @param sides     RFC 8011 {@code sides} keyword, or null to leave at the
 *                  printer's default
 * @param colorMode RFC 8011 {@code print-color-mode} keyword, or null for the default
 * @param media     {@code media} keyword or name — a size ({@code iso_a4_210x297mm})
 *                  or, on many printers, an input tray name. Null for the default
 */
public record JobAttributes(String sides, String colorMode, String media) {

    /** Nothing specified — the printer's own defaults apply throughout. */
    public static final JobAttributes DEFAULTS = new JobAttributes(null, null, null);

    /** The {@code sides} keywords RFC 8011 defines. */
    public static final List<String> SIDES_VALUES =
            List.of(Sides.oneSided, Sides.twoSidedLongEdge, Sides.twoSidedShortEdge);

    /**
     * The {@code print-color-mode} keywords offered when we have no printer to
     * ask. Three, because they are unambiguous to a model choosing between
     * "color" and "black and white" — a menu of eight is not more useful here.
     *
     * <p>This is a UI default, NOT the validation vocabulary. See
     * {@link #ALL_COLOR_MODES}.
     */
    public static final List<String> COLOR_MODE_VALUES =
            List.of(PrintColorMode.color, PrintColorMode.monochrome, PrintColorMode.auto);

    /**
     * Every {@code print-color-mode} keyword RFC 8011 defines, used for validation.
     *
     * <p>Distinct from {@link #COLOR_MODE_VALUES} because a printer may legitimately
     * offer modes outside the friendly three: the Canon advertises
     * {@code auto-monochrome}. Once Settings lists what the device reports, an
     * operator can pick one of those — and validating against the short list would
     * reject a value the printer itself just offered.
     */
    public static final List<String> ALL_COLOR_MODES = List.of(
            PrintColorMode.auto, PrintColorMode.autoMonochrome, PrintColorMode.biLevel,
            PrintColorMode.color, PrintColorMode.highlight, PrintColorMode.monochrome,
            PrintColorMode.processBiLevel, PrintColorMode.processMonochrome);

    /** True when nothing was requested, so no job-attributes group is emitted at all. */
    public boolean isEmpty() {
        return sides == null && colorMode == null && media == null;
    }

    /** Human-readable list of what was requested, for the tool's reply. */
    public String describe() {
        var parts = new ArrayList<String>();
        if (sides != null) parts.add("sides=" + sides);
        if (colorMode != null) parts.add("color=" + colorMode);
        if (media != null) parts.add("media=" + media);
        return String.join(", ", parts);
    }

    /**
     * Validate against the RFC keyword sets.
     *
     * <p>Checked here rather than left to the printer because an unsupported
     * keyword comes back as {@code client-error-attributes-or-values-not-supported}
     * — an IPP status that tells the model nothing about which value was wrong.
     * {@code media} is deliberately unvalidated: its vocabulary is open (PWG size
     * names plus vendor tray names), so a closed list here would reject valid input.
     *
     * @return the reason it is invalid, or null when it is fine
     */
    public String validationError() {
        if (sides != null && !SIDES_VALUES.contains(sides)) {
            return "invalid 'sides' value '" + sides + "'; expected one of " + SIDES_VALUES;
        }
        if (colorMode != null && !ALL_COLOR_MODES.contains(colorMode)) {
            return "invalid 'color' value '" + colorMode + "'; expected one of " + ALL_COLOR_MODES;
        }
        return null;
    }
}
