package tools;

import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.Tx;
import utils.AppClock;
import utils.JsonArgs;

import java.util.List;
import java.util.Map;

public class CheckListTool implements ToolRegistry.Tool {

    private static final String NAME = "checklist";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ACTIVE_FORM = "activeForm";

    /** Section heading of {@link #reminder}, shared with the tests that assert it. */
    public static final String REMINDER_HEADING = "## Your checklist";

    /** User messages a stored list may go without an update before {@link #reminder} treats it as abandoned. */
    public static final int ABANDONED_AFTER_USER_MESSAGES = 3;

    @Override
    public String name() { return NAME; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "check"; }

    @Override
    public String shortDescription() {
        return "Create and manage structured checklists to track multi-step work in progress.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("update", "Submit a checklist with items and statuses; at most one item may be in_progress at a time")
        );
    }

    @Override
    public String description() {
        return """
                Create and manage a structured checklist for tracking multi-step work. \
                Submit a list of items; each item requires three fields: \
                `content` (imperative form, e.g. "Run tests"), \
                `status` (one of pending, in_progress, completed), and \
                `activeForm` (present-progressive form, e.g. "Running tests"). \
                At most one item may be in_progress at a time (zero is also valid, e.g. before starting or after finishing). \
                Your reply ends the turn and nothing updates the list after it, so mark finished items completed before you write it.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        SchemaKeys.ITEMS, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.ARRAY,
                                SchemaKeys.ITEMS, Map.of(
                                        SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                        SchemaKeys.PROPERTIES, Map.of(
                                                FIELD_CONTENT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                                                FIELD_STATUS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                                        SchemaKeys.ENUM, List.of("pending", "in_progress", "completed")),
                                                FIELD_ACTIVE_FORM, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING)
                                        ),
                                        SchemaKeys.REQUIRED, List.of(FIELD_CONTENT, FIELD_STATUS, FIELD_ACTIVE_FORM)
                                )
                        )
                ),
                SchemaKeys.REQUIRED, List.of(SchemaKeys.ITEMS)
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        if (!args.has(SchemaKeys.ITEMS) || !args.get(SchemaKeys.ITEMS).isJsonArray()) {
            return "Error: `items` is required and must be an array.";
        }
        var itemsArray = args.getAsJsonArray(SchemaKeys.ITEMS);

        int inProgressCount = 0;
        for (int i = 0; i < itemsArray.size(); i++) {
            var validation = validateItem(itemsArray.get(i), i);
            if (validation.error() != null) return validation.error();
            if (validation.inProgress()) inProgressCount++;
        }

        if (inProgressCount > 1) {
            return "Error: At most one item may be in_progress. Found %d.".formatted(inProgressCount);
        }

        // A task fire runs on an unsaved conversation; its calls stay in the fire's own messages.
        var conversationId = ToolContext.conversationId();
        if (conversationId != null) store(conversationId, itemsArray);
        return "Checklist updated successfully (%d items).".formatted(itemsArray.size());
    }

    private static void store(Long conversationId, JsonArray items) {
        var stored = new JsonArray();
        for (var el : items) {
            var item = el.getAsJsonObject();
            var copy = new JsonObject();
            for (var field : List.of(FIELD_CONTENT, FIELD_STATUS, FIELD_ACTIVE_FORM)) copy.add(field, item.get(field));
            stored.add(copy);
        }
        Tx.run(() -> {
            var conversation = (Conversation) Conversation.findById(conversationId);
            if (conversation == null) return;
            conversation.checklist = stored.toString();
            conversation.checklistUpdatedAt = AppClock.now();
            conversation.save();
        });
    }

    /** The conversation's stored list, or null when it has none or the stored text is not a JSON array. */
    public static @Nullable JsonArray storedItems(@Nullable String checklist) {
        if (checklist == null) return null;
        try {
            var parsed = JsonParser.parseString(checklist);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        } catch (JsonParseException _) {
            return null;
        }
    }

    /**
     * A reminder of the conversation's stored list for the turn's trailing context, or null when there is
     * nothing to remind: no list, every item completed, or a {@code checklist} call still in {@code messages}.
     * An unfinished list that more than {@link #ABANDONED_AFTER_USER_MESSAGES} user messages have passed
     * without an update is abandoned: this clears it, so the chat stops showing it as well.
     */
    public static @Nullable String reminder(Conversation conversation, List<ChatMessage> messages) {
        var items = storedItems(conversation.checklist);
        if (items == null || !hasOpenItem(items)) return null;
        if (abandoned(conversation)) {
            forget(conversation);
            return null;
        }
        if (callInContext(messages)) return null;
        var lines = new StringBuilder();
        for (var el : items) {
            var item = el.getAsJsonObject();
            var status = JsonArgs.optString(item, FIELD_STATUS, "pending");
            var text = "in_progress".equals(status)
                    ? JsonArgs.optString(item, FIELD_ACTIVE_FORM, "")
                    : JsonArgs.optString(item, FIELD_CONTENT, "");
            lines.append("- [").append(status.replace('_', ' ')).append("] ").append(text).append('\n');
        }
        return "\n" + REMINDER_HEADING + "\n"
                + "The checklist call that set this list is no longer in the conversation above. Its current state:\n"
                + lines;
    }

    private static boolean hasOpenItem(JsonArray items) {
        for (var el : items) {
            if (!"completed".equals(JsonArgs.optString(el.getAsJsonObject(), FIELD_STATUS, "pending"))) return true;
        }
        return false;
    }

    /**
     * Counted in messages a person sent, not time or all messages: one tool-heavy turn adds dozens of the latter.
     * A subagent's returning result is a user-role row too (it wakes the parent), tagged with a kind, and an inline
     * child's rows carry its run id; neither counts, or delegating four steps would erase a live list.
     */
    private static boolean abandoned(Conversation conversation) {
        var updatedAt = conversation.checklistUpdatedAt;
        if (updatedAt == null) return true;
        long later = Tx.run(() -> Message.count("conversation.id = ?1 AND role = ?2 AND messageKind IS NULL"
                        + " AND subagentRunId IS NULL AND createdAt > ?3",
                conversation.id, MessageRole.USER.value, updatedAt));
        return later > ABANDONED_AFTER_USER_MESSAGES;
    }

    private static void forget(Conversation conversation) {
        conversation.checklist = null;
        conversation.checklistUpdatedAt = null;
        Tx.run(() -> {
            var managed = (Conversation) Conversation.findById(conversation.id);
            if (managed == null) return;
            managed.checklist = null;
            managed.checklistUpdatedAt = null;
            managed.save();
        });
    }

    private static boolean callInContext(List<ChatMessage> messages) {
        for (var m : messages) {
            var calls = m.toolCalls();
            if (calls == null) continue;
            for (var call : calls) {
                if (NAME.equals(call.function().name())) return true;
            }
        }
        return false;
    }

    /** Per-item validation result. {@code error} non-null short-circuits the loop. */
    private record ItemValidation(@Nullable String error, boolean inProgress) {
        static ItemValidation fail(String msg) { return new ItemValidation(msg, false); }
        static ItemValidation ok(boolean inProgress) { return new ItemValidation(null, inProgress); }
    }

    private static ItemValidation validateItem(JsonElement el, int i) {
        if (!el.isJsonObject()) {
            return ItemValidation.fail("Error: Item %d must be an object.".formatted(i));
        }
        var item = el.getAsJsonObject();
        String content = JsonArgs.optString(item, FIELD_CONTENT);
        if (content == null) return ItemValidation.fail("Error: Item %d is missing required field `content`.".formatted(i));
        String status = JsonArgs.optString(item, FIELD_STATUS);
        if (status == null) return ItemValidation.fail("Error: Item %d is missing required field `status`.".formatted(i));
        String activeForm = JsonArgs.optString(item, FIELD_ACTIVE_FORM);
        if (activeForm == null) return ItemValidation.fail("Error: Item %d is missing required field `activeForm`.".formatted(i));

        if (content.isBlank()) return ItemValidation.fail("Error: All items must have non-blank content.");
        if (activeForm.isBlank()) return ItemValidation.fail("Error: All items must have non-blank activeForm.");
        return ItemValidation.ok("in_progress".equals(status));
    }
}
