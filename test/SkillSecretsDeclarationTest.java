import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.SkillSecrets;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JF-353: a skill declares its secrets in a fenced block of its SKILL.md, and skill-creator tells agents to write it. */
class SkillSecretsDeclarationTest extends UnitTest {

    private static boolean found(String block) {
        return SkillSecrets.holdsMoreThanDeclarations(block);
    }

    @Test
    void theFirstTopLevelSecretsBlockIsTheDeclaration() {
        var split = SkillSecrets.splitDeclaration("""
                # Demo

                ```bash
                curl -H "X-Key: $API_KEY" https://api.example.com/
                ```

                ## Secrets

                ```secrets
                API_KEY=api.example.com   # The key
                ```

                ```secrets
                LATER=later.example.com   # a second block declares nothing
                ```
                """);
        assertEquals("API_KEY=api.example.com   # The key\n", split.block());
        assertEquals("""
                # Demo

                ```bash
                curl -H "X-Key: $API_KEY" https://api.example.com/
                ```

                ## Secrets

                ```secrets
                ```

                ```secrets
                LATER=later.example.com   # a second block declares nothing
                ```

                """, split.rest());
    }

    /** A guide that shows the format wraps it in a longer fence: that is an example, not a declaration. */
    @Test
    void aBlockInsideAnotherFenceDeclaresNothing() {
        var guide = """
                Write the block like this:

                ````markdown
                ```secrets
                API_KEY=api.example.com   # The key
                ```
                ````
                """;
        assertEquals(new SkillSecrets.Split(null, guide), SkillSecrets.splitDeclaration(guide));
    }

    @Test
    void aBlockOfAnotherLanguageOrOneNeverClosedDeclaresNothing() {
        var other = "```secrets-example\nAPI_KEY=api.example.com\n```\n";
        assertEquals(new SkillSecrets.Split(null, other), SkillSecrets.splitDeclaration(other));
        var open = "# Demo\n\n```secrets\nAPI_KEY=api.example.com\n";
        assertEquals(new SkillSecrets.Split(null, open), SkillSecrets.splitDeclaration(open));
        assertEquals("API_KEY=api.example.com\n",
                SkillSecrets.splitDeclaration("~~~ secrets\nAPI_KEY=api.example.com\n~~~\n").block());
    }

    @Test
    void aBlockThatOnlyDeclaresHoldsNothingElse() {
        assertEquals(false, found("""
                # Each line names one secret and an example host. No value goes here.

                GAMMA_API_KEY=public-api.gamma.app          # A Gamma API key (starts with sk-gamma-), from Settings
                export NAS_PASS=nas.example.com:5001, backup.example.com   # The DSM account's password itself
                PROXY_PASS=localhost:8443                   # Optional. The reverse proxy's password
                JIRA_PERSONAL_TOKEN=jira.example.com        # A personal access token, sent as a Bearer token
                BARE=
                """));
    }

    @Test
    void aKeyWrittenWhereTheHostBelongsIsFound() {
        assertEquals(true, found("SONARR_API_KEY=0123456789abcdef0123456789abcdef   # pasted by mistake\n"));
        assertEquals(true, found("OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz0123456789\n"));
        assertEquals(true, found("NAS_PASS=https://admin:hunter2hunter2@nas.example.com   # a sign-in in the address\n"));
        assertEquals(true, found("NAS_PASS=a1b2c3d4e5f6a7b8c9d0e1f2a3b4.example.com   # a key dressed as a host\n"));
        assertEquals(false, found("NAS_PASS=nas01.backup-site-number-two.example.com   # a long but plain host\n"));
    }

    /** What promotion writes back is exactly what the parser reads: names, hosts and descriptions, nothing else. */
    @Test
    void aRenderedBlockReadsBackAsTheSameDeclarations() {
        var declared = java.util.List.of(
                new SkillSecrets.Declared("API_KEY", "The key", java.util.List.of("api.example.com"), false),
                new SkillSecrets.Declared("NAS_PASS", "", java.util.List.of("nas.example.com:5001", "backup.example.com"), false),
                new SkillSecrets.Declared("PROXY_PASS", SkillSecrets.keepOptional("", true), java.util.List.of(), true));
        var block = SkillSecrets.declarationBlock(declared, java.util.List.of());
        assertEquals("""
                ```secrets
                API_KEY=api.example.com   # The key
                NAS_PASS=nas.example.com:5001, backup.example.com
                PROXY_PASS=   # Optional
                ```""", block);
        assertEquals(declared, SkillSecrets.declaredIn(block + "\n"));
    }

    @Test
    void aKeyInACommentOrOnALineThatDeclaresNothingIsFound() {
        assertEquals(true, found("GITHUB_KEY=api.github.com   # for example ghp_abcdefghijklmnopqrstuvwxyz0123456789\n"));
        assertEquals(true, found("# the old key was sk-live-4f3c2a1b9e8d7c6b5a4f3c2a\nAPI_KEY=api.example.com\n"));
        assertEquals(true, found("password: hunter2hunter2\n"));
        assertEquals(true, found("# password: hunter2hunter2\nAPI_KEY=api.example.com\n"), "a comment is judged whole");
        assertEquals(true, found("API_KEY=api.example.com   # password: hunter2hunter2\n"), "so is a description");
    }

    @Test
    void aKeyWrittenAsTheNameIsFound() {
        assertEquals(true, found("AKIAIOSFODNN7EXAMPLE=api.example.com   # an AWS key id is a valid name\n"));
        assertEquals(true, found("K9F3A7B2C8D1E6F0A4B5C3D2E1F0=api.example.com\n"));
        assertEquals(false, found("A_VERY_LONG_BUT_PLAIN_SECRET_NAME_FOR_THE_SERVICE=api.example.com\n"));
    }

    /** JF-357: a setting line declares a value that is not secret; it is no secret, and its example is free text. */
    @Test
    void aSettingLineDeclaresASettingNotASecret() {
        var skillMd = """
                ```secrets
                SONARR_API_KEY=sonarr.example.com              # Sonarr's API key
                setting SONARR_URL=https://sonarr.example.com:8989/sonarr   # Sonarr's address, with https://
                setting SONARR_USER=admin                       # Optional. The sign-in user
                ```
                """;
        assertEquals(java.util.List.of(new SkillSecrets.Declared("SONARR_API_KEY", "Sonarr's API key",
                java.util.List.of("sonarr.example.com"), false)), SkillSecrets.declaredIn(skillMd));
        assertEquals(java.util.List.of(
                new SkillSecrets.DeclaredSetting("SONARR_URL", "Sonarr's address, with https://",
                        "https://sonarr.example.com:8989/sonarr", false),
                new SkillSecrets.DeclaredSetting("SONARR_USER", "Optional. The sign-in user", "admin", true)),
                SkillSecrets.settingsIn(skillMd));
        assertEquals(false, found(SkillSecrets.splitDeclaration(skillMd).block()));
    }

    /** The keyword is written by whoever writes SKILL.md: a name that reads as a password is a secret regardless. */
    @Test
    void aSettingLineWhoseNameReadsAsACredentialDeclaresASecret() {
        var skillMd = """
                ```secrets
                setting NAS_PASS=nas.example.com:5001        # The password, mislabelled
                setting SONARR_API_KEY=sonarr.example.com    # The key, mislabelled
                setting JIRA_PERSONAL_TOKEN=jira.example.com
                setting SITE_SESSION_ID=x
                setting TRAKT_CLIENT_ID=0123abcd              # An app id, a setting
                setting NAS_USER=admin
                ```
                """;
        assertEquals(java.util.List.of("NAS_PASS", "SONARR_API_KEY", "JIRA_PERSONAL_TOKEN", "SITE_SESSION_ID"),
                SkillSecrets.declaredIn(skillMd).stream().map(SkillSecrets.Declared::name).toList());
        assertEquals(java.util.List.of("nas.example.com:5001"), SkillSecrets.declaredIn(skillMd).getFirst().exampleHosts());
        assertEquals(java.util.List.of("TRAKT_CLIENT_ID", "NAS_USER"),
                SkillSecrets.settingsIn(skillMd).stream().map(SkillSecrets.DeclaredSetting::name).toList());
    }

    @Test
    void aKeyWrittenAsASettingsExampleIsFound() {
        assertEquals(true, found("setting SITE_URL=https://example.com/?apikey=0123456789abcdef0123456789abcdef\n"));
        assertEquals(true, found("setting APP_ID=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6\n"));
        assertEquals(true, found("setting SITE_URL=https://example.com   # password: hunter2hunter2\n"));
    }

    @Test
    void aRenderedBlockWithSettingsReadsBackAsTheSameDeclarations() {
        var secrets = java.util.List.of(new SkillSecrets.Declared("API_KEY", "The key", java.util.List.of("api.example.com"), false));
        var settings = java.util.List.of(
                new SkillSecrets.DeclaredSetting("SITE_URL", "The address", "https://example.com/app", false),
                new SkillSecrets.DeclaredSetting("SITE_USER", "Optional", "", true));
        var block = SkillSecrets.declarationBlock(secrets, settings);
        assertEquals("""
                ```secrets
                API_KEY=api.example.com   # The key
                setting SITE_URL=https://example.com/app   # The address
                setting SITE_USER=   # Optional
                ```""", block);
        assertEquals(secrets, SkillSecrets.declaredIn(block + "\n"));
        assertEquals(settings, SkillSecrets.settingsIn(block + "\n"));
    }

    /**
     * The block's language lives in code and in the guide an agent follows: renamed in one alone, no panel fills.
     * The guide's own SKILL.md must hold no top-level block, or skill-creator itself would need secrets.
     */
    @Test
    void skillCreatorTellsAgentsToWriteTheBlockThePanelReadsAndDeclaresNothingItself() throws Exception {
        Path skill = Path.of(Play.applicationPath.getAbsolutePath(), "skills", "skill-creator");
        assertTrue(Files.readString(skill.resolve("SKILL.md")).contains("`" + SkillSecrets.DECLARATION_BLOCK + "`"),
                "SKILL.md names the block's language");
        assertTrue(Files.readString(skill.resolve("references/secrets.md")).contains("```" + SkillSecrets.DECLARATION_BLOCK + "\n"),
                "the reference shows the block");
        assertEquals(java.util.List.of(), SkillSecrets.declared(skill));
        assertEquals(java.util.List.of(), SkillSecrets.declaredSettings(skill));
        var reference = Files.readString(skill.resolve("references/secrets.md"));
        assertTrue(reference.contains("setting WEATHER_BASE_URL="), "the reference shows a setting line");
        assertTrue(reference.contains("`" + utils.WorkspacePathGuard.SKILL_STATE_DIR + "/{skill-name}/`"),
                "the reference names the folder the tools fence");
    }
}
