package services.search;

/**
 * Public bridge into {@link MessageSearch}'s package-private
 * {@code setRepositoryForTest}. JClaw's tests live in the default
 * package, so they can't call the package-private setter directly;
 * this class lives in the {@code services.search} package and
 * re-exposes the setter as a public method.
 *
 * <p>Production code never touches this class. The compiler can't
 * forbid that — Java has no "tests-only" visibility — but the name
 * makes accidental production use obvious.
 */
public final class MessageSearchTestHooks {

    private MessageSearchTestHooks() {}

    /** Install or clear the active repository. Pass {@code null} to clear. */
    public static void setRepository(MessageSearchRepository override) {
        MessageSearch.setRepositoryForTest(override);
    }
}
