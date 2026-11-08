package tools;

import com.google.gson.JsonObject;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.SessionCompactor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Model-assisted {@code find}: one call to the operator's chosen model with the page's element list, answering with
 * the refs that do what the request means. A word match cannot tell "the box for my confirmation email" from the
 * footer's newsletter box; a model reading the labels can. Measured on three real pages, DeepSeek 4.1 Flash picked
 * the right element for 21 of 22 requests in one call of about 0.6 s, where a decision model needed five calls to
 * reach 20 of 23.
 *
 * <p>The page's words reach the model, so this is off until the operator names one (Settings → Chrome), and an
 * answer is only ever used to choose among refs the page listed: text the page planted can mislead the pick but
 * cannot produce an element that is not there.
 */
public final class ChromeFinder {

    static final String PROVIDER_KEY = "chromeBridge.find.provider";
    static final String MODEL_KEY = "chromeBridge.find.model";

    private static final int MAX_PICKS = 3;
    private static final int MAX_ANSWER_TOKENS = 100;
    private static final Pattern REF = Pattern.compile("\\be\\d+\\b");

    private ChromeFinder() {}

    /** One model call: the prompt in, the reply text out. */
    public interface Model extends Function<List<ChatMessage>, @Nullable String> {}

    /** The operator's finder model, or null when none is set or its provider is not configured. */
    static @Nullable Model configured() {
        var providerName = ConfigService.get(PROVIDER_KEY);
        var modelId = ConfigService.get(MODEL_KEY);
        if (providerName == null || providerName.isBlank() || modelId == null || modelId.isBlank()) return null;
        var provider = ProviderRegistry.get(providerName.trim());
        if (provider == null) return null;
        var id = modelId.trim();
        return messages -> SessionCompactor.firstChoiceText(
                provider.chat(id, messages, List.of(), MAX_ANSWER_TOKENS, null, null));
    }

    /**
     * The refs, best first, the model chose from {@code listing} (one element per line, starting with its ref), or
     * null when the call failed so the caller falls back to the word match. An empty list means nothing fits.
     */
    public static @Nullable List<String> pick(Model model, String title, String query, String listing) {
        String reply;
        try {
            reply = model.apply(List.of(ChatMessage.user(prompt(title, query, listing))));
        } catch (RuntimeException e) {
            EventLogger.warn("chrome", "Model-assisted find failed: " + e.getClass().getSimpleName());
            return null;
        }
        if (reply == null) return null;
        var known = refsOf(listing);
        var picks = new ArrayList<String>(MAX_PICKS);
        var m = REF.matcher(reply);
        while (m.find() && picks.size() < MAX_PICKS) {
            var ref = m.group();
            if (known.contains(ref) && !picks.contains(ref)) picks.add(ref);
        }
        return picks;
    }

    public static String prompt(String title, String query, String listing) {
        return "Web page: " + title + "\nRequest: " + query + "\n\nElements:\n" + listing + "\n\n"
                + "Pick the elements that do what the request asks. Judge by meaning, not word overlap. "
                + "Answer with up to " + MAX_PICKS + " refs from the list above, best first, separated by spaces, "
                + "and nothing else. Answer NONE if no element fits.";
    }

    private static Set<String> refsOf(String listing) {
        var refs = new LinkedHashSet<String>();
        for (var line : listing.split("\n")) {
            var space = line.indexOf(' ');
            if (space > 0) refs.add(line.substring(0, space));
        }
        return refs;
    }

    /** The listing line for one element, shared with the {@code read} output so the model sees what the agent would. */
    static String line(JsonObject element) {
        var out = new StringBuilder();
        ChromeControlTool.appendElement(out, element);
        return out.toString().stripTrailing();
    }
}
