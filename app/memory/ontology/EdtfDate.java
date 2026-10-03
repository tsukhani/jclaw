package memory.ontology;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * One EDTF date in the subset schema v3 stores (JCLAW-1361): {@code YYYY}, {@code YYYY-MM}, {@code YYYY-MM-DD}, a
 * season {@code YYYY-21..24} (spring, summer, autumn, winter; meteorological, northern hemisphere) or a quarter
 * {@code YYYY-33..36}, each optionally approximate with a trailing {@code ~}. Unspecified digits ({@code X}),
 * uncertainty ({@code ?}), per-component qualifiers and every other two-digit code are refused.
 *
 * @param month 1–12 for MONTH and DAY, else 0
 * @param day 1–31 for DAY, else 0
 * @param code 21–24 for SEASON, 33–36 for QUARTER, else 0
 */
public record EdtfDate(int year, Precision precision, int month, int day, int code, boolean approximate) {

    public enum Precision { YEAR, MONTH, DAY, SEASON, QUARTER }

    private static final Pattern SHAPE = Pattern.compile("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?(~)?");

    public EdtfDate {
        if (year < 0 || year > 9999) throw new IllegalArgumentException("year out of range: " + year);
        switch (precision) {
            case YEAR -> require(month == 0 && day == 0 && code == 0, "a year carries no month, day or code");
            case MONTH -> require(month >= 1 && month <= 12 && day == 0 && code == 0, "bad month " + month);
            case DAY -> {
                require(month >= 1 && month <= 12 && code == 0, "bad month " + month);
                require(day >= 1 && day <= YearMonth.of(year, month).lengthOfMonth(), "bad day " + day);
            }
            case SEASON -> require(code >= 21 && code <= 24 && month == 0 && day == 0, "bad season code " + code);
            case QUARTER -> require(code >= 33 && code <= 36 && month == 0 && day == 0, "bad quarter code " + code);
        }
    }

    public static EdtfDate ofYear(int year, boolean approximate) {
        return new EdtfDate(year, Precision.YEAR, 0, 0, 0, approximate);
    }

    public static EdtfDate ofMonth(YearMonth month, boolean approximate) {
        return new EdtfDate(month.getYear(), Precision.MONTH, month.getMonthValue(), 0, 0, approximate);
    }

    public static EdtfDate ofDay(LocalDate date, boolean approximate) {
        return new EdtfDate(date.getYear(), Precision.DAY, date.getMonthValue(), date.getDayOfMonth(), 0, approximate);
    }

    /** {@code season} 1 spring … 4 winter; winter {@code year} runs from December of that year. */
    public static EdtfDate ofSeason(int year, int season) {
        return new EdtfDate(year, Precision.SEASON, 0, 0, 20 + season, false);
    }

    /** {@code quarter} 1–4. */
    public static EdtfDate ofQuarter(int year, int quarter) {
        return new EdtfDate(year, Precision.QUARTER, 0, 0, 32 + quarter, false);
    }

    /** @throws IllegalArgumentException on anything outside the subset */
    public static EdtfDate parse(String text) {
        var m = SHAPE.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("not an EDTF date in the subset: " + text);
        int year = Integer.parseInt(m.group(1));
        boolean approximate = m.group(4) != null;
        var second = m.group(2);
        var third = m.group(3);
        if (second == null) return ofYear(year, approximate);
        int two = Integer.parseInt(second);
        if (third != null) return new EdtfDate(year, Precision.DAY, two, Integer.parseInt(third), 0, approximate);
        if (two >= 1 && two <= 12) return new EdtfDate(year, Precision.MONTH, two, 0, 0, approximate);
        if (two >= 21 && two <= 24) return new EdtfDate(year, Precision.SEASON, 0, 0, two, approximate);
        if (two >= 33 && two <= 36) return new EdtfDate(year, Precision.QUARTER, 0, 0, two, approximate);
        throw new IllegalArgumentException("unsupported EDTF code " + second + " in " + text);
    }

    /** The first day the date covers, widened by its approximation. */
    public LocalDate lo() {
        var lo = exactLo();
        return approximate ? lo.minus(widening(), widenUnit()) : lo;
    }

    /** The first day after the date, exclusive, widened by its approximation. */
    public LocalDate hi() {
        var hi = exactHi();
        return approximate ? hi.plus(widening(), widenUnit()) : hi;
    }

    /** The same date {@code years} later; the month, day and code are untouched, so Feb 29 to a common year throws. */
    public EdtfDate plusYears(int years) {
        return new EdtfDate(year + years, precision, month, day, code, approximate);
    }

    @Override
    public String toString() {
        var out = new StringBuilder("%04d".formatted(year));
        switch (precision) {
            case YEAR -> { }
            case MONTH -> out.append("-%02d".formatted(month));
            case DAY -> out.append("-%02d-%02d".formatted(month, day));
            case SEASON, QUARTER -> out.append('-').append(code);
        }
        if (approximate) out.append('~');
        return out.toString();
    }

    private LocalDate exactLo() {
        return switch (precision) {
            case YEAR -> LocalDate.of(year, 1, 1);
            case MONTH -> LocalDate.of(year, month, 1);
            case DAY -> LocalDate.of(year, month, day);
            case SEASON -> LocalDate.of(year, 3 * (code - 21) + 3, 1);
            case QUARTER -> LocalDate.of(year, 3 * (code - 33) + 1, 1);
        };
    }

    private LocalDate exactHi() {
        return switch (precision) {
            case YEAR -> exactLo().plusYears(1);
            case MONTH, SEASON, QUARTER -> exactLo().plusMonths(precision == Precision.MONTH ? 1 : 3);
            case DAY -> exactLo().plusDays(1);
        };
    }

    /** ±1 year, ±1 month, ±3 days (a week), ±3 months for a season or quarter. */
    private int widening() {
        return switch (precision) {
            case YEAR, MONTH -> 1;
            case DAY, SEASON, QUARTER -> 3;
        };
    }

    private ChronoUnit widenUnit() {
        return switch (precision) {
            case YEAR -> ChronoUnit.YEARS;
            case MONTH, SEASON, QUARTER -> ChronoUnit.MONTHS;
            case DAY -> ChronoUnit.DAYS;
        };
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
