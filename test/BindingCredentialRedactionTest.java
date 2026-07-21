import channels.ChannelTransport;
import com.fasterxml.jackson.annotation.JsonIgnore;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;

/**
 * JCLAW-730 regression: binding credential fields must not serialize in the
 * clear, and the Telegram {@code effectiveWebhookUrl} projection must never
 * embed the webhook secret.
 *
 * <p>Two guards are pinned:
 * <ul>
 *   <li>every plaintext credential field carries {@code @JsonIgnore} (the
 *       Jackson-path guard) and exposes a masked accessor as the sanctioned
 *       surfacing method; and</li>
 *   <li>{@code ApiTelegramBindingsController.BindingView.effectiveWebhookUrl}
 *       drops the {@code /{secret}} tail — the one place a secret used to leak
 *       into a returned body.</li>
 * </ul>
 *
 * <p>Note: Gson (the app's primary serializer) is guarded by the hand-written
 * {@code BindingView} projection, not by {@code @JsonIgnore}; the projection's
 * secret-elision is exercised by {@code ApiTelegramBindingsControllerTest}.
 */
class BindingCredentialRedactionTest extends UnitTest {

    // ── @JsonIgnore is present on every plaintext credential field ──

    @Test
    void telegramCredentialFieldsAreJsonIgnored() throws Exception {
        assertJsonIgnored(TelegramBinding.class, "botToken");
        assertJsonIgnored(TelegramBinding.class, "webhookSecret");
    }

    @Test
    void slackCredentialFieldsAreJsonIgnored() throws Exception {
        assertJsonIgnored(SlackBinding.class, "botToken");
        assertJsonIgnored(SlackBinding.class, "signingSecret");
        assertJsonIgnored(SlackBinding.class, "appToken");
    }

    @Test
    void whatsappCredentialFieldsAreJsonIgnored() throws Exception {
        assertJsonIgnored(WhatsAppBinding.class, "accessToken");
        assertJsonIgnored(WhatsAppBinding.class, "appSecret");
        assertJsonIgnored(WhatsAppBinding.class, "verifyToken");
    }

    private static void assertJsonIgnored(Class<?> type, String field) throws Exception {
        var f = type.getField(field);
        assertTrue(f.isAnnotationPresent(JsonIgnore.class),
                type.getSimpleName() + "." + field + " must be @JsonIgnore");
    }

    // ── Masked accessors never surface the raw secret ──

    @Test
    void telegramMaskedAccessorsHideTheSecret() {
        var b = new TelegramBinding();
        b.botToken = "123456:ABCDEFGHIJ";
        b.webhookSecret = "sekret-value-xyz";
        assertEquals("1234****", b.maskedBotToken());
        assertFalse(b.maskedWebhookSecret().contains("value-xyz"),
                "masked webhook secret must not expose the tail");
        assertTrue(b.maskedWebhookSecret().endsWith("****"));
    }

    @Test
    void slackMaskedAccessorsHideTheSecret() {
        var b = new SlackBinding();
        b.botToken = "xoxb-abcdefgh";
        b.signingSecret = "signing-abcdefgh";
        b.appToken = "xapp-abcdefgh";
        assertFalse(b.maskedBotToken().contains("abcdefgh"));
        assertFalse(b.maskedSigningSecret().contains("abcdefgh"));
        assertFalse(b.maskedAppToken().contains("abcdefgh"));
    }

    @Test
    void whatsappMaskedAccessorsHideTheSecret() {
        var b = new WhatsAppBinding();
        b.accessToken = "EAAtoken-abcdefgh";
        b.appSecret = "appsecret-abcdefgh";
        b.verifyToken = "verify-abcdefgh";
        assertFalse(b.maskedAccessToken().contains("abcdefgh"));
        assertFalse(b.maskedAppSecret().contains("abcdefgh"));
        assertFalse(b.maskedVerifyToken().contains("abcdefgh"));
    }

    @Test
    void maskingHandlesBlankAndShortValues() {
        var b = new TelegramBinding();
        assertNull(b.maskedWebhookSecret(), "null secret masks to null");
        b.webhookSecret = "";
        assertNull(b.maskedWebhookSecret(), "blank secret masks to null");
        b.webhookSecret = "ab";
        assertEquals("****", b.maskedWebhookSecret(), "a short secret leaks no prefix");
    }

    // ── effectiveWebhookUrl drops the secret path segment ──

    @Test
    void effectiveWebhookUrlNeverContainsTheSecret() throws Exception {
        var b = new TelegramBinding();
        b.id = 7L;
        b.transport = ChannelTransport.WEBHOOK;
        b.webhookBaseUrl = "https://host.tailnet.ts.net";
        b.webhookSecret = "SUPER-SECRET-TOKEN-XYZ";

        var url = effectiveWebhookUrl(b);
        assertNotNull(url, "a fully-configured webhook binding still surfaces a preview URL");
        assertFalse(url.contains("SUPER-SECRET-TOKEN-XYZ"),
                "effectiveWebhookUrl must not embed the webhook secret: " + url);
        assertEquals("https://host.tailnet.ts.net/api/webhooks/telegram/7", url);
    }

    @Test
    void effectiveWebhookUrlTrimsTrailingSlashAndStaysNullWhenUnconfigured() throws Exception {
        var withSlash = new TelegramBinding();
        withSlash.id = 3L;
        withSlash.transport = ChannelTransport.WEBHOOK;
        withSlash.webhookBaseUrl = "https://host/";
        withSlash.webhookSecret = "s3cr3t-value";
        assertEquals("https://host/api/webhooks/telegram/3", effectiveWebhookUrl(withSlash));

        // Polling binding (or a webhook binding missing a secret/base) → null.
        var polling = new TelegramBinding();
        polling.id = 4L;
        polling.transport = ChannelTransport.POLLING;
        polling.webhookBaseUrl = "https://host";
        polling.webhookSecret = "s3cr3t-value";
        assertNull(effectiveWebhookUrl(polling));
    }

    @Test
    void gsonDoesNotSerializeCredentialFields() {
        // JCLAW-730 follow-up: the app's primary serializer is Gson, which
        // ignores @JsonIgnore — so GsonHolder now honors it via an
        // ExclusionStrategy. A credential value must never appear in the output.
        var t = new TelegramBinding();
        t.botToken = "SECRET-bot-token-value";
        t.webhookSecret = "SECRET-webhook-value";
        var json = utils.GsonHolder.GSON.toJson(t);
        assertFalse(json.contains("SECRET-bot-token-value"),
                "Gson must not serialize botToken in the clear");
        assertFalse(json.contains("SECRET-webhook-value"),
                "Gson must not serialize webhookSecret in the clear");
    }

    private static String effectiveWebhookUrl(TelegramBinding b) throws Exception {
        var view = Class.forName("controllers.ApiTelegramBindingsController$BindingView");
        Method m = view.getDeclaredMethod("effectiveWebhookUrl", TelegramBinding.class);
        m.setAccessible(true);
        return (String) m.invoke(null, b);
    }
}
