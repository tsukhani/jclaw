import channels.ChannelTransport;
import channels.TelegramWebhookRegistrar;
import channels.TelegramWebhookRegistrar.WebhookApi;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-339: the {@link TelegramWebhookRegistrar} decision logic, exercised with
 * an injected {@link WebhookApi} so nothing hits the real Telegram API and the
 * funnel base is supplied directly (no {@code tailscale} CLI dependency).
 */
class TelegramWebhookRegistrarTest extends UnitTest {

    static final class FakeApi implements WebhookApi {
        final List<String> set = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean setResult = true;

        @Override public boolean setWebhook(String token, String url, String secret) {
            set.add(token + "|" + url + "|" + secret);
            return setResult;
        }

        @Override public boolean deleteWebhook(String token) {
            deleted.add(token);
            return true;
        }
    }

    private static final String BASE = "https://host.taildcc9a6.ts.net";

    @Test
    void webhookUrlEmbedsIdAndSecret() {
        assertEquals("https://h.ts.net/api/webhooks/telegram/7/abc",
                TelegramWebhookRegistrar.webhookUrl("https://h.ts.net", 7L, "abc"));
    }

    @Test
    void enabledWebhookWithSecretAndFunnelRegisters() {
        var api = new FakeApi();
        TelegramWebhookRegistrar.apply(7L, "tok", "sek", ChannelTransport.WEBHOOK, true, BASE, api);
        assertEquals(List.of("tok|" + BASE + "/api/webhooks/telegram/7/sek|sek"), api.set);
        assertTrue(api.deleted.isEmpty(), "should not deregister an active webhook binding");
    }

    @Test
    void pollingBindingDeregisters() {
        var api = new FakeApi();
        TelegramWebhookRegistrar.apply(7L, "tok", "sek", ChannelTransport.POLLING, true, BASE, api);
        assertEquals(List.of("tok"), api.deleted);
        assertTrue(api.set.isEmpty(), "POLLING must not register a webhook");
    }

    @Test
    void disabledWebhookBindingDeregisters() {
        // A disabled WEBHOOK binding must clear its webhook so it stops receiving.
        var api = new FakeApi();
        TelegramWebhookRegistrar.apply(7L, "tok", "sek", ChannelTransport.WEBHOOK, false, BASE, api);
        assertEquals(List.of("tok"), api.deleted);
        assertTrue(api.set.isEmpty());
    }

    @Test
    void funnelOfflineSkipsRegistrationWithoutDeleting() {
        // No public base → can't form a URL → skip + warn, but DON'T deregister
        // (a brief funnel blip shouldn't tear down an existing registration).
        var api = new FakeApi();
        TelegramWebhookRegistrar.apply(7L, "tok", "sek", ChannelTransport.WEBHOOK, true, null, api);
        assertTrue(api.set.isEmpty());
        assertTrue(api.deleted.isEmpty());
    }

    @Test
    void missingSecretSkipsRegistration() {
        var api = new FakeApi();
        TelegramWebhookRegistrar.apply(7L, "tok", "   ", ChannelTransport.WEBHOOK, true, BASE, api);
        assertTrue(api.set.isEmpty());
        assertTrue(api.deleted.isEmpty());
    }

    @Test
    void registerOneReflectsApiResult() {
        var api = new FakeApi();
        api.setResult = false;
        assertFalse(TelegramWebhookRegistrar.registerOne(7L, "tok", "sek", BASE, api));
        api.setResult = true;
        assertTrue(TelegramWebhookRegistrar.registerOne(7L, "tok", "sek", BASE, api));
    }
}
