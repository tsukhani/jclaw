import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.KeptSkillFiles;
import services.KeptSkillFiles.Reading;
import services.SkillSecrets;

/**
 * How a kept file's text gives one secret or setting its value (JF-352): a plain assignment for that name, or the whole
 * text when it holds none, and a refusal for a value only a shell could read, so the file is kept for the operator.
 */
class KeptSkillFilesTest extends UnitTest {

    private static Reading read(String text, String name) {
        return KeptSkillFiles.reading(text, name, "credentials/key.env");
    }

    private static String refusal(String text, String name) {
        return assertThrows(SkillSecrets.Refused.class, () -> read(text, name)).reason();
    }

    @Test
    void aBase64KeyWithPaddingIsOneValueNotAnAssignment() {
        assertEquals(new Reading("dGVzdGtleQ==", true), read("dGVzdGtleQ==\n", "GAMMA_API_KEY"));
    }

    @Test
    void aMixedCasePemWhosePaddedLineLooksLikeAnAssignmentIsOneValue() {
        var pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nAbCd1234XyZ=\n-----END PRIVATE KEY-----\n";

        assertEquals(new Reading(pem.strip(), true), read(pem, "PRIVATE_KEY"));
    }

    @Test
    void aLowerCaseIniNameIsFoundForTheNameAskedFor() {
        var ini = "[default]\naws_access_key_id = AKIAFAKEJF352\naws_secret_access_key = fake/secret+jf352\n";

        assertEquals(new Reading("AKIAFAKEJF352", false), read(ini, "AWS_ACCESS_KEY_ID"));
        assertEquals(new Reading("fake/secret+jf352", false), read(ini, "AWS_SECRET_ACCESS_KEY"));
    }

    @Test
    void aLowerCaseNameThatIsNotAskedForLeavesTheTextOneValue() {
        assertEquals(new Reading("some_key=fake-value", true), read("some_key=fake-value\n", "API_KEY"));
    }

    @Test
    void theLastLineForExactlyTheNameGivesTheValue() {
        var text = "JIRA_URL=https://first.example.org\nJIRA_URL=https://jira.example.org\nJIRA_URL_OLD=https://old.example.org\n";

        assertEquals(new Reading("https://jira.example.org", false), read(text, "JIRA_URL"));
    }

    @Test
    void exportQuotesAndATrailingCommentAreLeftOut() {
        assertEquals(new Reading("x#y", false), read("export SITE_URL='x#y'   # the site\n", "SITE_URL"));
        assertEquals(new Reading("a \"b\"", false), read("SITE_URL=\"a \\\"b\\\"\"  # quoted\n", "SITE_URL"));
        assertEquals(new Reading("/srv/site", false), read("SITE_PATH=/srv/site   # the path\n", "SITE_PATH"));
        assertEquals(new Reading("a#b", false), read("SITE_PATH=a#b\n", "SITE_PATH"));
    }

    @Test
    void aDollarIsTakenAsItIsOnlyInSingleQuotesOrEscaped() {
        assertEquals(new Reading("$HOME/x", false), read("SITE_PATH='$HOME/x'\n", "SITE_PATH"));
        assertEquals(new Reading("cost $5", false), read("SITE_NOTE=\"cost \\$5\"\n", "SITE_NOTE"));
        assertEquals(new Reading("cost$5", false), read("SITE_NOTE=cost\\$5\n", "SITE_NOTE"));
    }

    @Test
    void aDollarAShellWouldExpandIsRefused() {
        var expected = "The kept file credentials/key.env gives API_URL a value with $ outside single quotes, which only a"
                + " shell would expand: type the value instead.";

        assertEquals(expected, refusal("API_URL=$HOST/api\n", "API_URL"));
        assertEquals(expected, refusal("API_URL=\"https://${HOST}/api\"\n", "API_URL"));
    }

    @Test
    void aQuoteNotClosedOnItsLineIsRefused() {
        var text = "PRIVATE_KEY=\"-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----\"\n";

        assertEquals("The kept file credentials/key.env gives PRIVATE_KEY a quoted value with no closing quote on its"
                + " line, which JClaw does not read: type the value instead.", refusal(text, "PRIVATE_KEY"));
    }

    @Test
    void anAssignmentFileWithNoLineForTheNameIsRefused() {
        assertEquals("The kept file credentials/key.env has no line JIRA_URL=….", refusal("OTHER=1\n", "JIRA_URL"));
    }

    @Test
    void anEarlierLineAShellWouldExpandDoesNotStopTheLastOne() {
        assertEquals(new Reading("https://x.example.org", false),
                read("API_URL=$HOST\nAPI_URL=https://x.example.org\n", "API_URL"));
    }
}
