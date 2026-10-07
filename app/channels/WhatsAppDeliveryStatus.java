package channels;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One entry of a Cloud-API webhook's {@code value.statuses} array: Meta's report of a sent
 * message's fate (JCLAW-1411). The error fields come from {@code errors[0]}, present only when
 * the status is {@code failed}.
 */
public record WhatsAppDeliveryStatus(String messageId, String status, @Nullable String recipientId,
                                     @Nullable Instant timestamp, @Nullable Integer errorCode,
                                     @Nullable String errorTitle, @Nullable String errorDetails) {

    public boolean failed() {
        return "failed".equals(status);
    }
}
