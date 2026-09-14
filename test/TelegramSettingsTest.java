import channels.TelegramSettings;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Write-time rules for the Telegram behaviour keys edited on the Channels > Telegram page. */
class TelegramSettingsTest extends UnitTest {

    @Test
    void choiceKeysTakeOnlyTheirValuesInAnyCase() {
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.REPLY_TO_MODE, "ALL"));
        assertNotNull(TelegramSettings.rejectionFor(TelegramSettings.REPLY_TO_MODE, "sometimes"));
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.LINK_PREVIEW, "off"));
        assertNotNull(TelegramSettings.rejectionFor(TelegramSettings.ACK_REACTION, "true"));
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.REACTIONS_NOTIFY, "own"));
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.NOTIFIER_POLICY, "silent"));
        assertNotNull(TelegramSettings.rejectionFor(TelegramSettings.KEYBOARD_SCOPE, "everywhere"));
    }

    @Test
    void everyActionToggleTakesOnlyTrueOrFalse() {
        for (var action : List.of("reply", "edit", "delete", "react", "poll", "pin")) {
            var key = TelegramSettings.ACTIONS_PREFIX + action;
            assertNull(TelegramSettings.rejectionFor(key, "false"), key);
            assertNotNull(TelegramSettings.rejectionFor(key, "on"), key);
        }
    }

    @Test
    void numericKeysEnforceTheirFloors() {
        assertNotNull(TelegramSettings.rejectionFor(TelegramSettings.NOTIFIER_COOLDOWN_MS, "0"));
        assertNotNull(TelegramSettings.rejectionFor(TelegramSettings.COALESCE_THRESHOLD, "0"));
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.COALESCE_WINDOW_MS, "0"));
        assertNotNull(TelegramSettings.rejectionFor(TelegramSettings.FORWARD_COALESCE_WINDOW_MS, "-1"));
    }

    @Test
    void aWakeWordThatDoesNotCompileIsNamedInTheRefusal() {
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.MENTION_PATTERNS, "(?i)\\bjarvis\\b, hey bot"));
        assertNull(TelegramSettings.rejectionFor(TelegramSettings.MENTION_PATTERNS, ""));
        var rejected = TelegramSettings.rejectionFor(TelegramSettings.MENTION_PATTERNS, "ok, (unclosed");
        assertNotNull(rejected);
        assertTrue(rejected.contains("(unclosed"), rejected);
    }

    @Test
    void theWebhookHardeningKeysAreNotJudgedHere() {
        // They are read from application.conf; a Config DB row for one changes nothing.
        assertNull(TelegramSettings.rejectionFor("telegram.webhook.max-body-bytes", "abc"));
    }
}
