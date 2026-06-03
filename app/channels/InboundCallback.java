package channels;

/**
 * Inbound callback_query payload (JCLAW-109). Emitted by
 * {@link TelegramInboundParser#parseCallback(org.telegram.telegrambots.meta.api.objects.Update)}
 * when the update is a tap on an inline keyboard button. Extracted from
 * {@code TelegramChannel} in JCLAW-151.
 *
 * @param callbackId callback id passed back to
 *                   {@code answerCallbackQuery} to dismiss the spinner
 *                   on the user's button tap
 * @param chatId     chat the button was tapped in (used for binding
 *                   authorization)
 * @param chatType   {@code "private"} or {@code "group"} from the
 *                   inbound chat
 * @param fromId     user id of the tapper (used for binding
 *                   authorization)
 * @param messageId  original message id carrying the inline keyboard,
 *                   so the handler can edit-in-place
 * @param data       opaque data string parsed by the kind-specific
 *                   dispatcher
 */
public record InboundCallback(String callbackId, String chatId, String chatType,
                              String fromId, Integer messageId, String data) {}
