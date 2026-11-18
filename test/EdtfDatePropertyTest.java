import memory.ontology.EdtfDate;
import memory.ontology.EdtfInterval;
import org.jetbrains.jetCheck.Generator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

class EdtfDatePropertyTest extends UnitTest {

    /** Years, months, days and season or quarter codes, each exact or approximate. */
    private static final Generator<EdtfDate> EDTF_DATES = edtfDates();

    private static Generator<EdtfDate> edtfDates() {
        var years = Generator.integers(0, 9999);
        var approximate = Generator.booleans();
        return Generator.anyOf(List.of(
                Generator.from(env -> EdtfDate.ofYear(env.generate(years), env.generate(approximate))),
                Generator.from(env -> EdtfDate.ofMonth(
                        YearMonth.of(env.generate(years), env.generate(Generator.integers(1, 12))),
                        env.generate(approximate))),
                Generator.from(env -> EdtfDate.ofDay(
                        LocalDate.of(0, 1, 1).plusDays(env.generate(Generator.integers(0, 9999 * 365))),
                        env.generate(approximate))),
                Generator.from(env -> {
                    int year = env.generate(years);
                    int code = env.generate(Generator.integers(1, 8));
                    boolean approx = env.generate(approximate);
                    return EdtfDate.parse("%04d-%d%s".formatted(year, code <= 4 ? 20 + code : 28 + code, approx ? "~" : ""));
                })));
    }

    // 500 iterations: a parse and a format each, microseconds.
    @Test
    void anEdtfDateAndItsSingleIntervalRoundTrip() {
        PropertyInputs.check(500, EDTF_DATES, date -> {
            assertEquals(date, EdtfDate.parse(date.toString()), () -> "date round trip diverged for " + date);
            var interval = EdtfInterval.of(date);
            assertEquals(interval, EdtfInterval.parse(interval.toString()), () -> "interval round trip diverged for " + date);
        });
    }

    // 500 iterations: two LocalDate computations each.
    @Test
    void anEdtfDateCoversAtLeastOneDay() {
        PropertyInputs.check(500, EDTF_DATES, date -> assertTrue(date.lo().isBefore(date.hi()),
                () -> date + " lo " + date.lo() + " is not before hi " + date.hi()));
    }
}
