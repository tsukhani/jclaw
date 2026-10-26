package channels;

import okhttp3.HttpUrl;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Meta Graph node ids (phone number, WhatsApp Business Account, media) as the path of a Bearer
 * request. Graph ids are numeric; any other shape, such as {@code ../me/accounts?fields=access_token},
 * could steer the binding's token off its node, and {@code addPathSegment} alone still pops on {@code ..}.
 */
final class WhatsAppGraphIds {

    private WhatsAppGraphIds() {}

    private static final Pattern NODE_ID = Pattern.compile("\\d+");

    static final String PHONE_NUMBER_ID_REFUSED = "phoneNumberId must be Meta's numeric Phone number ID (digits only)";

    /** {@code apiBase} with {@code id} appended as one path segment, or null when {@code id} is not all digits. */
    static HttpUrl.@Nullable Builder nodeUrl(String apiBase, @Nullable String id) {
        if (id == null || !NODE_ID.matcher(id).matches()) {
            return null;
        }
        return HttpUrl.get(apiBase).newBuilder().addPathSegment(id);
    }
}
