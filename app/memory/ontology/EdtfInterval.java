package memory.ontology;

/**
 * An EDTF interval over {@link EdtfDate} (JCLAW-1361): {@code start/end}, where either side may be unknown (empty) or
 * open ({@code ..}) and at least one is a date, or the slashless single-date form. The only open start is
 * {@code ../YYYY-MM-DD}, the scope of a "never" up to a day.
 */
public record EdtfInterval(Endpoint start, Endpoint end, boolean single) {

    /** One side of an interval. */
    public sealed interface Endpoint permits Point, Unknown, Open {}

    public record Point(EdtfDate date) implements Endpoint {
        @Override
        public String toString() {
            return date.toString();
        }
    }

    /** The empty side: the bound exists but is not known. */
    public record Unknown() implements Endpoint {
        @Override
        public String toString() {
            return "";
        }
    }

    /** {@code ..}: the interval has no bound on this side. */
    public record Open() implements Endpoint {
        @Override
        public String toString() {
            return "..";
        }
    }

    public static final Unknown UNKNOWN = new Unknown();
    public static final Open OPEN = new Open();

    public EdtfInterval {
        if (single) {
            if (!(start instanceof Point) || !start.equals(end)) {
                throw new IllegalArgumentException("a single-date interval is one date");
            }
        } else {
            if (!(start instanceof Point) && !(end instanceof Point)) {
                throw new IllegalArgumentException("an interval needs at least one date");
            }
            if (start instanceof Open && !(end instanceof Point(var d)
                    && d.precision() == EdtfDate.Precision.DAY && !d.approximate())) {
                throw new IllegalArgumentException("an open start needs an exact day end");
            }
            if (start instanceof Point(var s) && end instanceof Point(var e) && !s.lo().isBefore(e.hi())) {
                throw new IllegalArgumentException("start " + s + " is after end " + e);
            }
        }
    }

    public static EdtfInterval of(EdtfDate date) {
        var p = new Point(date);
        return new EdtfInterval(p, p, true);
    }

    public static EdtfInterval between(Endpoint start, Endpoint end) {
        return new EdtfInterval(start, end, false);
    }

    /** @throws IllegalArgumentException on anything outside the subset */
    public static EdtfInterval parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) return of(EdtfDate.parse(text));
        if (text.indexOf('/', slash + 1) >= 0) throw new IllegalArgumentException("more than one slash: " + text);
        return between(endpoint(text.substring(0, slash)), endpoint(text.substring(slash + 1)));
    }

    private static Endpoint endpoint(String side) {
        if (side.isEmpty()) return UNKNOWN;
        if (side.equals("..")) return OPEN;
        return new Point(EdtfDate.parse(side));
    }

    @Override
    public String toString() {
        return single ? start.toString() : start + "/" + end;
    }
}
