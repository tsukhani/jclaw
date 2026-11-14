/** A summary in the five sections the summary prompt asks for (JF-427 stores no other), for a stub model to return. */
public final class SummaryFixture {

    private SummaryFixture() {}

    /**
     * Words only a summary's or a whole merge's instruction holds: a stub tells a summary call by these, never by a
     * section heading, which every stored summary now carries into the next turn's prompt.
     */
    public static final String SUMMARY_REQUEST_MARK = "Write these five sections";

    /** Every section "None." but the last, Next steps, which ends on {@code body}, so a test can still match its end. */
    public static String asSummary(String body) {
        return "### Goals and constraints\nNone.\n\n### Decisions\nNone.\n\n### Identifiers and facts\nNone."
                + "\n\n### Open questions and errors\nNone.\n\n### Next steps\n" + body;
    }

    /** {@link #asSummary} escaped for a stub that writes it between the quotes of a JSON string. */
    public static String asSummaryInJson(String body) {
        var quoted = new com.google.gson.Gson().toJson(asSummary(body));
        return quoted.substring(1, quoted.length() - 1);
    }
}
