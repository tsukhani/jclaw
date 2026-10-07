package controllers;

import channels.WhatsAppUsage;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.BindingService;
import utils.AppClock;

import java.time.YearMonth;
import java.time.ZoneOffset;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * This month's replies for a Cloud API binding's number against Meta's free allowance
 * (JCLAW-1412). The figure is Meta's, so every read asks Meta and nothing is stored.
 */
@With(AuthCheck.class)
public class WhatsAppUsageController extends Controller {

    /** {@code state} is COUNTED, UNKNOWN or NOT_APPLICABLE; the counts are null unless COUNTED. */
    private record UsageView(Long bindingId, String state, int year, int month,
                             @Nullable Long replies, @Nullable Long billed, int allowance,
                             @Nullable String reason) {
        static UsageView of(Long bindingId, WhatsAppUsage.Usage usage) {
            return switch (usage) {
                case WhatsAppUsage.Counted(YearMonth m, long replies, long billed) ->
                        new UsageView(bindingId, "COUNTED", m.getYear(), m.getMonthValue(), replies, billed,
                                WhatsAppUsage.FREE_SERVICE_ALLOWANCE, null);
                case WhatsAppUsage.Unknown(YearMonth m, String reason) ->
                        new UsageView(bindingId, "UNKNOWN", m.getYear(), m.getMonthValue(), null, null,
                                WhatsAppUsage.FREE_SERVICE_ALLOWANCE, reason);
            };
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UsageView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void usage(Long id) {
        WhatsAppBinding binding = BindingService.findWhatsAppBindingById(id);
        notFoundIfNull(binding);
        if (binding.transport == WhatsAppTransport.WHATSAPP_WEB
                || isBlank(binding.phoneNumberId) || isBlank(binding.accessToken)
                || isBlank(binding.displayPhoneNumber)) {
            var month = YearMonth.from(AppClock.now().atOffset(ZoneOffset.UTC));
            renderJSON(GSON.toJson(new UsageView(binding.id, "NOT_APPLICABLE", month.getYear(),
                    month.getMonthValue(), null, null, WhatsAppUsage.FREE_SERVICE_ALLOWANCE, null)));
        }
        renderJSON(GSON.toJson(UsageView.of(binding.id,
                WhatsAppUsage.read(binding.phoneNumberId, binding.accessToken, binding.displayPhoneNumber))));
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
