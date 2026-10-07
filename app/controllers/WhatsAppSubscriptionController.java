package controllers;

import channels.WhatsAppSubscription;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.BindingService;
import services.EventLogger;
import utils.ApiResponses;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static controllers.BindingKeys.EVENT_CATEGORY_CHANNEL;
import static utils.GsonHolder.GSON;

/**
 * Whether a Cloud API binding's Meta app is subscribed to the number's WhatsApp
 * Business Account, and the operator's button that subscribes it (JCLAW-1410).
 * The state lives at Meta, so every read asks Meta and nothing is stored.
 */
@With(AuthCheck.class)
public class WhatsAppSubscriptionController extends Controller {

    private static final String CHANNEL_WHATSAPP = "whatsapp";

    /** {@code state} is SUBSCRIBED, NOT_SUBSCRIBED, UNKNOWN or NOT_APPLICABLE; identifiers only, no credential. */
    private record SubscriptionView(Long bindingId, String state, @Nullable String wabaId,
                                    @Nullable String appId, @Nullable String reason) {
        static SubscriptionView of(Long bindingId, WhatsAppSubscription.State state) {
            return switch (state) {
                case WhatsAppSubscription.Subscribed(String waba, String app) ->
                        new SubscriptionView(bindingId, "SUBSCRIBED", waba, app, null);
                case WhatsAppSubscription.NotSubscribed(String waba, String app) ->
                        new SubscriptionView(bindingId, "NOT_SUBSCRIBED", waba, app, null);
                case WhatsAppSubscription.Unknown(String reason) ->
                        new SubscriptionView(bindingId, "UNKNOWN", null, null, reason);
            };
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SubscriptionView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void status(Long id) {
        WhatsAppBinding binding = BindingService.findWhatsAppBindingById(id);
        notFoundIfNull(binding);
        if (!subscribable(binding)) {
            renderJSON(GSON.toJson(new SubscriptionView(binding.id, "NOT_APPLICABLE", null, null, null)));
        }
        renderJSON(GSON.toJson(SubscriptionView.of(binding.id,
                WhatsAppSubscription.check(binding.phoneNumberId, binding.accessToken))));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SubscriptionView.class)))
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "subscribes the binding's Meta app to its WhatsApp Business Account, a write to Meta")
    public static void subscribe(Long id) {
        WhatsAppBinding binding = BindingService.findWhatsAppBindingById(id);
        notFoundIfNull(binding);
        if (!subscribable(binding)) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Only a Cloud API binding with a phone number id and an access token can be subscribed");
        }
        var agentName = binding.agent != null ? binding.agent.name : null;
        var result = WhatsAppSubscription.subscribe(binding.phoneNumberId, binding.accessToken);
        if (result instanceof WhatsAppSubscription.Failed(String reason)) {
            EventLogger.warn(EVENT_CATEGORY_CHANNEL, agentName, CHANNEL_WHATSAPP,
                    "Binding %d: subscribing the Meta app failed: ".formatted(binding.id) + reason);
            ApiResponses.error(422, ApiResponses.CLOUD_API_SUBSCRIBE_FAILED, "Meta refused the subscription: " + reason);
        }
        var wabaId = ((WhatsAppSubscription.Done) result).wabaId();
        EventLogger.info(EVENT_CATEGORY_CHANNEL, agentName, CHANNEL_WHATSAPP,
                "Binding %d: subscribed the Meta app to WhatsApp Business Account %s".formatted(binding.id, wabaId));
        renderJSON(GSON.toJson(SubscriptionView.of(binding.id,
                WhatsAppSubscription.check(binding.phoneNumberId, binding.accessToken))));
    }

    private static boolean subscribable(WhatsAppBinding binding) {
        return binding.transport != WhatsAppTransport.WHATSAPP_WEB
                && binding.phoneNumberId != null && !binding.phoneNumberId.isBlank()
                && binding.accessToken != null && !binding.accessToken.isBlank();
    }
}
