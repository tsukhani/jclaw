import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.jetbrains.jetCheck.PropertyFalsified;

import java.util.function.Consumer;

/** The checker call, generators and quoting the {@code *PropertyTest} classes share. */
final class PropertyInputs {

    /**
     * Hex-ish names salted with separators, whitespace, a NUL and non-ASCII. A plain string
     * generator almost never emits {@code /} or a dot, which would leave the interesting branches
     * of {@code LpdClient.safeToken} and {@code Filenames.extensionOf} unvisited while their
     * properties still passed.
     */
    static final Generator<String> HOSTILE_NAMES = Generator.stringsOf(
            IntDistribution.uniform(0, 200),
            Generator.charsFrom("abcdef0123456789/\\.-_ \t\né中" + (char) 0));

    private PropertyInputs() {}

    /**
     * Runs {@code property} on {@code iterations} generated inputs, printing nothing. jetCheck never
     * repeats an input and throws {@code CannotSatisfyCondition} once it cannot find a new one, so
     * keep {@code iterations} well under the number of distinct inputs, and loop over a small domain
     * instead. jetCheck keeps no failure between runs, so the error carries the replay data
     * {@code rechecking} takes: a rerun draws new inputs and can pass with the bug still there.
     */
    static <T> void check(int iterations, Generator<T> inputs, Consumer<T> property) {
        try {
            PropertyChecker.customized().silent().withIterationCount(iterations).forAll(inputs, input -> {
                property.accept(input);
                return true;
            });
        } catch (PropertyFalsified e) {
            var minimal = e.getFailure().getMinimalCounterexample();
            var cause = minimal.getExceptionCause();
            var replay = "\nReplay: PropertyChecker.customized().rechecking(\"" + minimal.getSerializedData() + "\")";
            // The fork's PlayJUnitExtension reports a failure's root cause, so an error that wrapped
            // the cause would lose the replay line: this one has none and wears the cause's stack.
            var error = new AssertionError((cause == null ? e.getMessage() : cause.getMessage()) + replay);
            if (cause != null) error.setStackTrace(cause.getStackTrace());
            throw error;
        }
    }

    /** ASCII letters only, {@code min} to {@code max} of them. */
    static Generator<String> letters(int min, int max) {
        return Generator.stringsOf(IntDistribution.uniform(min, max), Generator.asciiLetters());
    }

    /** Escapes control characters, quotes and backslashes, so a counterexample stays legible in the XML report. */
    static String quote(String s) {
        var sb = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp != '"' && cp != '\\') sb.appendCodePoint(cp);
            else sb.append('\\').append('u').append(String.format("%04x", cp));
        });
        return sb.append('"').toString();
    }
}
