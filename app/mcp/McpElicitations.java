package mcp;

import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.NotificationBus;
import utils.AppClock;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Form-mode elicitation (2026-07-28): an {@code elicitation/create} a server puts in an {@code input_required}
 * result, put to the operator on a top-level web-chat turn. Their answer goes back as given, and JClaw's
 * {@link Answer#note()} tells the agent what the operator did; a submit's names the fields filled in, never their
 * values. When a question cannot be put to the operator (off web chat, url mode, a form JClaw cannot show) nothing
 * goes back: the agent gets the server's questions.
 * One call's forms share one {@link #ANSWER_WAIT} from the first; Stop or the chat stream closing cancels them at once.
 *
 * <p>The values the operator enters are never logged or stored: they exist only in the answer handed back to
 * the waiting call. The registry is in memory, so a restart drops a waiting form along with the call behind it.
 */
public final class McpElicitations {

    public static final Duration ANSWER_WAIT = Duration.ofMinutes(5);
    public static final String CATEGORY = "MCP_ELICITATION";
    /** Bus event: a form is waiting. Payload: the {@link Question}'s fields. */
    public static final String BUS_ASKED = "mcp.elicitation";
    /** Bus event: a form is answered or gone. Payload: {@code id}, {@code conversationId}, {@code action}. */
    public static final String BUS_CLOSED = "mcp.elicitation.closed";
    public static final String ACCEPT = "accept";
    public static final String DECLINE = "decline";
    public static final String CANCEL = "cancel";

    /** How much of a server's message an audit line or the agent's copy of a question quotes. */
    private static final int MESSAGE_CHARS = 500;
    private static final Set<String> FORMATS = Set.of("email", "uri", "date", "date-time");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final String TYPE = "type";
    private static final String FIELD = "Field ";
    private static final String URL_MODE = "url mode, which JClaw does not support";
    private static final String UNKNOWN_MODE = "a request of an unknown mode";
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "array", "object", "null");
    /** How many fields, characters and options of one question the agent's copy describes. */
    private static final int DESCRIBED_FIELDS = 30;
    private static final int DESCRIBED_CHARS = 2_000;
    private static final int DESCRIBED_OPTIONS = 10;
    /** A bound or value past this many digits of scale or precision is no form's: its message alone could fill memory. */
    private static final int NUMBER_DIGITS = 64;

    private static final ScopedValue<Duration> WAIT_OVERRIDE = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> FROM_VIEW = ScopedValue.newInstance();
    private static final ConcurrentHashMap<String, Waiting> WAITING = new ConcurrentHashMap<>();
    private static final AtomicLong ORDER = new AtomicLong();

    private McpElicitations() {}

    /** A form waiting for the operator, as the chat page shows it. */
    public record Question(String id, long conversationId, String server, String message,
                           @Schema(type = "object", description = "The server's requestedSchema: a flat object of fields")
                           JsonObject requestedSchema,
                           String expiresAt) {

        Map<String, Object> toBus() {
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("conversationId", conversationId);
            out.put("server", server);
            out.put("message", message);
            out.put("requestedSchema", requestedSchema);
            out.put("expiresAt", expiresAt);
            return out;
        }
    }

    /**
     * What goes back to the server for one form, {@code {action}} or {@code {action, content}}, and the line JClaw
     * adds to the tool's result for the agent.
     */
    public record Answer(JsonObject response, String note) {}

    private record Waiting(Question question, long order, CompletableFuture<Answer> answer) {}

    /** What became of the operator's answer to a form. */
    public sealed interface Outcome {
        /** The form is closed and {@code action} went back to the server. */
        record Answered(String action) implements Outcome {}
        /** No form waits under that id: answered already, timed out, or never asked. */
        record NotPending() implements Outcome {}
        /** The answer was refused for {@code field}; the form stays open. */
        record Refused(String field, String message) implements Outcome {}
    }

    /** A field an answer fails, and the message naming it. */
    public record Invalid(String field, String message) {}

    /** Why nothing a request asks can go to the operator, whatever its forms are: as logged, and as the agent reads it. */
    public enum Unasked {
        NOT_WEB_CHAT("not a top-level web-chat turn that can be stopped",
                "JClaw asks the operator only on a top-level web-chat turn that can be stopped"),
        READ_OR_LISTING("a resource read or a listing, where JClaw does not ask",
                "JClaw does not ask the operator during a resource read or a listing"),
        VIEW("a tool call from a view", "the call came from a view, which cannot ask the operator"),
        HANDSHAKE("an MCP v1 server", "the server speaks MCP v1, where JClaw does not ask the operator");

        private final String logged;
        private final String why;

        Unasked(String logged, String why) {
            this.logged = logged;
            this.why = why;
        }
    }

    /** One call's time for the operator to answer, from its first form; each form after it waits only what is left. */
    public static final class Wait {
        private final Duration length;
        private long endsAtNanos;
        private @Nullable Instant endsAt;

        private Wait(Duration length) {
            this.length = length;
        }

        /** The time left, the clock starting at the first form. */
        Duration left() {
            if (endsAt == null) {
                endsAt = AppClock.now().plus(length);
                endsAtNanos = System.nanoTime() + length.toNanos();
            }
            return Duration.ofNanos(Math.max(0, endsAtNanos - System.nanoTime()));
        }

        /** When the call's time to answer runs out, once {@link #left()} has started the clock. */
        Instant endsAt() {
            return Objects.requireNonNull(endsAt, "the wait has not started");
        }
    }

    /** A fresh {@link Wait} for one call: {@link #ANSWER_WAIT}, or what a test bound with {@link #callWithWaitForTest}. */
    public static Wait newWait() {
        return new Wait(WAIT_OVERRIDE.orElse(ANSWER_WAIT));
    }

    /**
     * Whether a request made on this thread declares elicitation: a top-level web-chat turn whose Stop and chat
     * stream can cancel a waiting form. A failure to tell counts as no, so the server is offered nothing.
     */
    public static boolean offeredHere() {
        return ToolContext.turnCancel() != null && McpViewTools.webChatDispatchOrNot();
    }

    /** Why a request on this thread cannot ask the operator at all, or null when its forms can go to them. */
    public static @Nullable Unasked unaskedHere(boolean toolCall, boolean modern) {
        if (!toolCall) return Unasked.READ_OR_LISTING;
        if (!modern) return Unasked.HANDSHAKE;
        if (FROM_VIEW.isBound()) return Unasked.VIEW;
        return offeredHere() ? null : Unasked.NOT_WEB_CHAT;
    }

    /** {@code body}, a tool call an MCP Apps view makes, which cannot ask the operator. */
    public static <T> T callFromView(ScopedValue.CallableOp<T, IOException> body) throws IOException {
        return ScopedValue.where(FROM_VIEW, true).call(body);
    }

    /**
     * Null when every one of {@code forms} (the params of each {@code elicitation/create} in one result) can be put
     * to the operator: nothing about the request rules it out ({@code unasked} is null), and each is a form JClaw
     * shows. Otherwise none is asked, each is logged as handed to the agent, and this text gives the agent them all.
     */
    public static @Nullable String questionsForAgent(String server, Collection<JsonObject> forms, @Nullable Unasked unasked) {
        var reasons = new ArrayList<@Nullable String>();
        for (var form : forms) reasons.add(unasked != null ? unasked.logged : whyNotShown(form));
        if (reasons.stream().allMatch(Objects::isNull)) return null;
        var lines = new ArrayList<String>();
        var i = 0;
        for (var form : forms) {
            var reason = reasons.get(i++);
            EventLogger.info(CATEGORY, "MCP server '%s' asked %s: handed to the agent (%s)".formatted(server,
                    quoted(messageOf(form)), reason != null ? reason : "another of its questions cannot be shown"));
            lines.add("- " + quoted(messageOf(form)) + described(form));
        }
        var why = unasked != null ? unasked.why : whyNoneShown(reasons);
        return ("MCP server '%s' asked for input that JClaw did not put to the operator, because %s, so the call ended"
                + " with nothing sent back. The server asked:\n%s\nYou can fill the values in as arguments and call"
                + " again, ask in chat, or use another tool.").formatted(server, why, String.join("\n", lines));
    }

    private static String whyNoneShown(List<@Nullable String> reasons) {
        if (reasons.contains(URL_MODE)) return "a question asks the operator to open a link, which JClaw does not do";
        if (reasons.contains(UNKNOWN_MODE)) return "a question is of a mode JClaw does not know";
        return "a question has a field JClaw cannot show";
    }

    /**
     * The operator's answer to {@code form}, one {@link #questionsForAgent} found could be put to them on this
     * thread's web-chat turn. Blocks until they answer, the turn stops, or {@code wait} runs out; a form asked once
     * it has run out, or once the turn has ended, is cancelled without being shown.
     */
    public static Answer ask(String server, JsonObject form, Wait wait) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null || !(form.get("requestedSchema") instanceof JsonObject schema)) {
            throw new IllegalStateException("a form is asked only on a web-chat turn, once it is known to be shown");
        }
        var message = messageOf(form);
        var unanswered = "JClaw: the operator did not answer the request for input from MCP server '%s' within %s."
                .formatted(server, spoken(wait.length));
        var stopped = ("JClaw: the turn ended (Stop, or the chat closed), which cancelled the request for input from"
                + " MCP server '%s'.").formatted(server);
        var turnCancel = ToolContext.turnCancel();
        if (ToolContext.cancelled() || (turnCancel != null && turnCancel.get())) {
            return cancelledUnshown(server, message, stopped, "the turn had already ended");
        }
        var left = wait.left();
        if (left.isZero()) return cancelledUnshown(server, message, unanswered, "the time to answer had run out");
        var id = UUID.randomUUID().toString();
        var question = new Question(id, conversationId, server, message, schema.deepCopy(), wait.endsAt().toString());
        var waiting = new Waiting(question, ORDER.incrementAndGet(), new CompletableFuture<>());
        // Logged before the form can be answered, so the question always precedes its action.
        EventLogger.info(CATEGORY, "MCP server '%s' asked %s (fields: %s): waiting for the operator"
                .formatted(server, quoted(message), fieldNames(schema)));
        WAITING.put(id, waiting);
        try {
            NotificationBus.publish(BUS_ASKED, question.toBus());
            // The delay runs on the JDK's scheduler, so the wait below parks untimed (JDK-8373224).
            CompletableFuture.delayedExecutor(left.toNanos(), TimeUnit.NANOSECONDS,
                            r -> Thread.ofVirtual().name("mcp-elicitation-timeout").start(r))
                    .execute(() -> close(id, new Answer(action(CANCEL), unanswered), "no answer in time"));
            try (var _ = ToolContext.onCancel(() -> close(id, new Answer(action(CANCEL), stopped), "the turn ended"))) {
                return waiting.answer().join();
            }
        } finally {
            WAITING.remove(id, waiting);
        }
    }

    private static Answer cancelledUnshown(String server, String message, String note, String why) {
        EventLogger.info(CATEGORY, "MCP server '%s' asked %s: %s (%s)".formatted(server, quoted(message), CANCEL, why));
        return new Answer(action(CANCEL), note);
    }

    /** {@code 5 minutes}, or seconds below a whole minute: {@code 0.2 seconds}. */
    private static String spoken(Duration d) {
        if (d.toSeconds() >= 60 && d.toMillis() % 60_000 == 0) {
            var minutes = d.toMinutes();
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        var seconds = BigDecimal.valueOf(d.toMillis(), 3);
        return plain(seconds) + (seconds.compareTo(BigDecimal.ONE) == 0 ? " second" : " seconds");
    }

    /**
     * Test seam: the wait before an unanswered form is cancelled is {@code wait} while {@code body} runs on this
     * thread. {@code CapabilityRulesTest} fails the build if anything in {@code app/} calls it.
     */
    public static <T> T callWithWaitForTest(Duration wait, Supplier<T> body) {
        if (wait.isZero() || wait.isNegative()) throw new IllegalArgumentException("wait must be positive: " + wait);
        return ScopedValue.where(WAIT_OVERRIDE, wait).call(body::get);
    }

    /** The forms waiting in {@code conversationId}, oldest first. */
    public static List<Question> pendingFor(long conversationId) {
        return WAITING.values().stream()
                .filter(w -> w.question().conversationId() == conversationId)
                .sorted(Comparator.comparingLong(Waiting::order))
                .map(Waiting::question)
                .toList();
    }

    /**
     * The operator's answer to the form {@code id}. An {@code accept} is checked against the form's schema first,
     * and a failing one leaves the form open.
     */
    public static Outcome resolve(String id, @Nullable String action, @Nullable JsonElement content) {
        var waiting = WAITING.get(id);
        if (waiting == null) return new Outcome.NotPending();
        if (action == null || !Set.of(ACCEPT, DECLINE, CANCEL).contains(action)) {
            return new Outcome.Refused("action", "Field 'action' must be accept, decline or cancel");
        }
        var response = action(action);
        var server = waiting.question().server();
        var sentFor = "";
        if (ACCEPT.equals(action)) {
            var given = content == null || content.isJsonNull() ? new JsonObject() : content;
            var invalid = invalid(waiting.question().requestedSchema(), given);
            if (invalid != null) return new Outcome.Refused(invalid.field(), invalid.message());
            // A null is no value: an optional field left empty goes back absent.
            var sent = new JsonObject();
            for (var e : given.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonNull()) sent.add(e.getKey(), e.getValue().deepCopy());
            }
            response.add("content", sent);
            sentFor = sentFor(waiting.question().requestedSchema(), sent);
        }
        var answer = switch (action) {
            // Names, never values: without this line the agent takes the operator's answers for the server's defaults.
            case ACCEPT -> new Answer(response,
                    "JClaw: the operator %s the request for input from MCP server '%s'%s.".formatted(
                            sentFor.isEmpty() ? "accepted" : "answered", server, sentFor));
            case DECLINE -> new Answer(response,
                    "JClaw: the operator declined the request for input from MCP server '%s'.".formatted(server));
            default -> new Answer(response,
                    "JClaw: the operator cancelled the request for input from MCP server '%s'.".formatted(server));
        };
        var why = switch (action) {
            case ACCEPT -> "the operator submitted the form";
            case DECLINE -> "the operator declined";
            default -> "the operator cancelled";
        };
        return close(id, answer, why) ? new Outcome.Answered(action) : new Outcome.NotPending();
    }

    /** Closes the form {@code id} with {@code answer}; false when it was closed already. */
    private static boolean close(String id, Answer answer, String why) {
        var waiting = WAITING.remove(id);
        if (waiting == null) return false;
        var question = waiting.question();
        var action = answer.response().get("action").getAsString();
        EventLogger.info(CATEGORY, "MCP server '%s' asked %s: %s (%s)"
                .formatted(question.server(), quoted(question.message()), action, why));
        waiting.answer().complete(answer);
        var closed = new LinkedHashMap<String, Object>();
        closed.put("id", id);
        closed.put("conversationId", question.conversationId());
        closed.put("action", action);
        NotificationBus.publish(BUS_CLOSED, closed);
        return true;
    }

    /** Why {@code form} cannot be put to the operator, or null when it can. */
    private static @Nullable String whyNotShown(JsonObject form) {
        var mode = form.get("mode");
        if (mode != null && !mode.isJsonNull() && !"form".equals(stringOf(mode))) {
            return "url".equals(stringOf(mode)) ? URL_MODE : UNKNOWN_MODE;
        }
        var unsupported = unsupported(form.get("requestedSchema"));
        return unsupported == null ? null : "a form JClaw cannot show: " + unsupported;
    }

    private static String messageOf(JsonObject form) {
        return form.get("message") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
    }

    /**
     * A question's fields as the agent reads them: each name, its type, and whether it is required. Every text the
     * server chose is quoted, and at most {@value #DESCRIBED_FIELDS} fields or {@value #DESCRIBED_CHARS} characters
     * are described.
     */
    private static String described(JsonObject form) {
        var mode = form.get("mode");
        if (mode != null && !mode.isJsonNull() && !"form".equals(stringOf(mode))) {
            return "url".equals(stringOf(mode)) ? " (a link to open)" : " (" + UNKNOWN_MODE + ")";
        }
        if (!(form.get("requestedSchema") instanceof JsonObject schema)) return " (a form JClaw cannot read)";
        var properties = properties(schema);
        if (properties.isEmpty()) return " (no fields)";
        var required = new HashSet<String>();
        if (schema.get("required") instanceof JsonArray names) {
            names.forEach(n -> { if (stringOf(n) instanceof String s) required.add(s); });
        }
        var fields = new ArrayList<String>();
        var length = 0;
        for (var e : properties.entrySet()) {
            var parts = new ArrayList<String>();
            parts.add(e.getValue() instanceof JsonObject field ? typeOf(field) : "no type");
            if (required.contains(e.getKey())) parts.add("required");
            var text = quoted(e.getKey()) + " (" + String.join(", ", parts) + ")";
            if (fields.size() == DESCRIBED_FIELDS || length + text.length() > DESCRIBED_CHARS) break;
            fields.add(text);
            length += text.length() + 2;
        }
        var more = properties.size() - fields.size();
        if (more > 0) fields.add("…and " + more + " more");
        return " Fields: " + String.join(", ", fields) + ".";
    }

    // The names are the server's text, so quoted and capped as described() does; empty for a form with no fields.
    private static String sentFor(JsonObject schema, JsonObject sent) {
        var properties = properties(schema);
        if (properties.isEmpty()) return "";
        var keys = properties.keySet().stream().filter(sent::has).toList();
        if (keys.isEmpty()) return " with no values";
        var shown = new ArrayList<String>();
        var length = 0;
        for (var key : keys) {
            var text = quoted(key);
            if (shown.size() == DESCRIBED_FIELDS || length + text.length() > DESCRIBED_CHARS) break;
            shown.add(text);
            length += text.length() + 2;
        }
        var more = keys.size() - shown.size();
        if (more > 0) shown.add("…and " + more + " more");
        return " with values for " + String.join(", ", shown);
    }

    private static String typeOf(JsonObject field) {
        var raw = stringOf(field.get(TYPE));
        if (raw == null) return "no type";
        var type = TYPES.contains(raw) ? raw : "an unknown type";
        if ("array".equals(type)) {
            var options = field.get("items") instanceof JsonObject items ? options(items, "anyOf") : null;
            return options == null ? type : type + ", any of: " + shownOptions(options);
        }
        if (!"string".equals(type)) return type;
        var options = options(field, "oneOf");
        if (options != null) return type + ", one of: " + shownOptions(options);
        var format = stringOf(field.get("format"));
        if (format == null) return type;
        return type + ", " + (FORMATS.contains(format) ? format : "an unknown format");
    }

    private static String shownOptions(List<String> options) {
        var shown = new ArrayList<String>();
        for (var o : options.subList(0, Math.min(options.size(), DESCRIBED_OPTIONS))) shown.add(quoted(o));
        if (options.size() > DESCRIBED_OPTIONS) shown.add("…and " + (options.size() - DESCRIBED_OPTIONS) + " more");
        return String.join(" | ", shown);
    }

    private static JsonObject action(String action) {
        var out = new JsonObject();
        out.addProperty("action", action);
        return out;
    }

    private static String quoted(String message) {
        if (message.length() <= MESSAGE_CHARS) return new JsonPrimitive(message).toString();
        var end = Character.isHighSurrogate(message.charAt(MESSAGE_CHARS - 1)) ? MESSAGE_CHARS - 1 : MESSAGE_CHARS;
        return new JsonPrimitive(message.substring(0, end) + "…").toString();
    }

    private static String fieldNames(JsonObject schema) {
        var props = properties(schema);
        return props.isEmpty() ? "none" : String.join(", ", props.keySet());
    }

    // ==================== the form's schema ====================

    /** The kinds of field JClaw shows. */
    private enum Kind { TEXT, NUMBER, INTEGER, BOOLEAN, CHOICE, CHOICES }

    /**
     * Null when {@code schema} is a form JClaw shows: a flat object of string, number, integer, boolean,
     * single-select and multi-select fields. Otherwise what it cannot show, for the audit line.
     */
    public static @Nullable String unsupported(@Nullable JsonElement schema) {
        if (!(schema instanceof JsonObject obj) || !"object".equals(stringOf(obj.get(TYPE)))) {
            return "the schema is not an object";
        }
        var props = obj.get("properties");
        if (props != null && !(props instanceof JsonObject)) return "its properties are not an object";
        var properties = properties(obj);
        for (var e : properties.entrySet()) {
            if (!(e.getValue() instanceof JsonObject field) || kind(field) == null) {
                return "field " + quoted(e.getKey()) + " is not a text, number, yes-or-no or choice field";
            }
        }
        var required = obj.get("required");
        if (required == null || required.isJsonNull()) return null;
        if (!(required instanceof JsonArray names)) return "its required list is not a list";
        for (var name : names) {
            if (!(stringOf(name) instanceof String key) || !properties.has(key)) {
                return "it requires a field it does not define";
            }
        }
        return null;
    }

    private static JsonObject properties(JsonObject schema) {
        return schema.get("properties") instanceof JsonObject p ? p : new JsonObject();
    }

    private static @Nullable Kind kind(JsonObject field) {
        var type = stringOf(field.get(TYPE));
        if (type == null) return null;
        var defaultValue = field.get("default");
        var hasDefault = defaultValue != null && !defaultValue.isJsonNull();
        return switch (type) {
            case "string" -> {
                if (field.has("enum") || field.has("oneOf")) {
                    var options = options(field, "oneOf");
                    yield options != null && (!hasDefault || options.contains(stringOf(defaultValue))) ? Kind.CHOICE : null;
                }
                var format = field.get("format");
                var formatOk = format == null || format.isJsonNull() || FORMATS.contains(stringOf(format));
                yield formatOk && count(field, "minLength") && count(field, "maxLength")
                        && (!hasDefault || stringOf(defaultValue) != null) ? Kind.TEXT : null;
            }
            case "number", "integer" -> numberKind(field, "integer".equals(type), hasDefault, defaultValue);
            case "boolean" -> !hasDefault || isBoolean(defaultValue) ? Kind.BOOLEAN : null;
            case "array" -> {
                var options = field.get("items") instanceof JsonObject items ? options(items, "anyOf") : null;
                yield options != null && count(field, "minItems") && count(field, "maxItems")
                        && (!hasDefault || (defaultValue instanceof JsonArray chosen && members(chosen, options)))
                        ? Kind.CHOICES : null;
            }
            default -> null;
        };
    }

    private static @Nullable Kind numberKind(JsonObject field, boolean integer, boolean hasDefault,
                                             @Nullable JsonElement defaultValue) {
        if (!bound(field, "minimum") || !bound(field, "maximum")) return null;
        if (hasDefault && !(number(defaultValue) instanceof BigDecimal d && (!integer || whole(d)))) return null;
        return integer ? Kind.INTEGER : Kind.NUMBER;
    }

    /**
     * The values a choice field offers: {@code enum} strings, or {@code titled} (oneOf for a single select, anyOf
     * for the items of a multi-select) entries of {@code const} and an optional {@code title}. Null for none, or
     * for any other shape.
     */
    private static @Nullable List<String> options(JsonObject field, String titled) {
        var out = new ArrayList<String>();
        if (field.get("enum") instanceof JsonArray values) {
            for (var v : values) {
                if (!(stringOf(v) instanceof String s)) return null;
                out.add(s);
            }
        } else if (field.get(titled) instanceof JsonArray entries) {
            for (var entry : entries) {
                if (!(entry instanceof JsonObject e) || !(stringOf(e.get("const")) instanceof String s)) return null;
                var title = e.get("title");
                if (title != null && !title.isJsonNull() && stringOf(title) == null) return null;
                out.add(s);
            }
        } else {
            return null;
        }
        if (field.has(TYPE) && !"string".equals(stringOf(field.get(TYPE)))) return null;
        return out.isEmpty() ? null : out;
    }

    private static boolean count(JsonObject field, String key) {
        var el = field.get(key);
        return el == null || el.isJsonNull() || (number(el) instanceof BigDecimal d && whole(d) && d.signum() >= 0);
    }

    private static boolean bound(JsonObject field, String key) {
        var el = field.get(key);
        return el == null || el.isJsonNull() || number(el) != null;
    }

    // ==================== the operator's answer ====================

    /**
     * Null when {@code content} answers {@code schema}: every required field present, each value of its field's
     * type, a choice among its options, within its minimum, maximum and lengths, and of its format. Otherwise the
     * first field it fails, with a message naming it.
     */
    public static @Nullable Invalid invalid(JsonObject schema, JsonElement content) {
        if (!(content instanceof JsonObject answer)) return new Invalid("content", "Field 'content' must be an object");
        var properties = properties(schema);
        for (var key : answer.keySet()) {
            if (!properties.has(key)) return new Invalid(key, FIELD + quotedKey(key) + " is not in the form");
        }
        var required = new HashSet<String>();
        if (schema.get("required") instanceof JsonArray names) {
            names.forEach(n -> { if (stringOf(n) instanceof String s) required.add(s); });
        }
        for (var e : properties.entrySet()) {
            var key = e.getKey();
            var value = answer.get(key);
            if (value == null || value.isJsonNull()) {
                if (required.contains(key)) return new Invalid(key, FIELD + quotedKey(key) + " is required");
                continue;
            }
            var reason = e.getValue() instanceof JsonObject field ? reason(field, value) : "is not in a form JClaw shows";
            if (reason != null) return new Invalid(key, FIELD + quotedKey(key) + " " + reason);
        }
        return null;
    }

    /** Why {@code value} does not answer {@code field}, or null when it does. */
    private static @Nullable String reason(JsonObject field, JsonElement value) {
        var kind = kind(field);
        if (kind == null) return "is not in a form JClaw shows";
        return switch (kind) {
            case TEXT -> textReason(field, value);
            case NUMBER, INTEGER -> numberReason(field, value, kind == Kind.INTEGER);
            case BOOLEAN -> isBoolean(value) ? null : "must be true or false";
            case CHOICE -> {
                var options = options(field, "oneOf");
                yield options != null && options.contains(stringOf(value)) ? null : "must be one of its choices";
            }
            case CHOICES -> choicesReason(field, value);
        };
    }

    private static @Nullable String textReason(JsonObject field, JsonElement value) {
        if (!(stringOf(value) instanceof String text)) return "must be text";
        var length = BigDecimal.valueOf(text.codePointCount(0, text.length()));
        if (number(field.get("minLength")) instanceof BigDecimal min && length.compareTo(min) < 0) {
            return "must be at least " + characters(min);
        }
        if (number(field.get("maxLength")) instanceof BigDecimal max && length.compareTo(max) > 0) {
            return "must be at most " + characters(max);
        }
        return switch (stringOf(field.get("format")) instanceof String f ? f : "") {
            case "email" -> EMAIL.matcher(text).matches() ? null : "must be an email address";
            case "uri" -> isUri(text) ? null : "must be a URI";
            case "date" -> parses(() -> LocalDate.parse(text)) ? null : "must be a date (YYYY-MM-DD)";
            case "date-time" -> parses(() -> OffsetDateTime.parse(text)) ? null : "must be a date and time";
            default -> null;
        };
    }

    private static @Nullable String numberReason(JsonObject field, JsonElement value, boolean integer) {
        if (!(number(value) instanceof BigDecimal n)) return "must be a number";
        if (integer && !whole(n)) return "must be a whole number";
        if (number(field.get("minimum")) instanceof BigDecimal min && n.compareTo(min) < 0) return "must be at least " + plain(min);
        if (number(field.get("maximum")) instanceof BigDecimal max && n.compareTo(max) > 0) return "must be at most " + plain(max);
        return null;
    }

    private static @Nullable String choicesReason(JsonObject field, JsonElement value) {
        var options = field.get("items") instanceof JsonObject items ? options(items, "anyOf") : null;
        if (options == null || !(value instanceof JsonArray chosen) || !members(chosen, options)) {
            return "must be a list of its choices";
        }
        var size = BigDecimal.valueOf(chosen.size());
        if (number(field.get("minItems")) instanceof BigDecimal min && size.compareTo(min) < 0) {
            return "must have at least " + choices(min);
        }
        if (number(field.get("maxItems")) instanceof BigDecimal max && size.compareTo(max) > 0) {
            return "must have at most " + choices(max);
        }
        return null;
    }

    private static boolean members(JsonArray chosen, List<String> options) {
        for (var v : chosen) {
            if (!options.contains(stringOf(v))) return false;
        }
        return true;
    }

    private static String characters(BigDecimal n) {
        return plain(n) + (n.compareTo(BigDecimal.ONE) == 0 ? " character" : " characters");
    }

    private static String choices(BigDecimal n) {
        return plain(n) + (n.compareTo(BigDecimal.ONE) == 0 ? " choice" : " choices");
    }

    private static boolean isUri(String text) {
        try {
            return new URI(text).isAbsolute();
        } catch (URISyntaxException _) {
            return false;
        }
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (DateTimeParseException _) {
            return false;
        }
    }

    private static String quotedKey(String key) {
        return "'" + key + "'";
    }

    // ==================== JSON ====================

    private static @Nullable String stringOf(@Nullable JsonElement el) {
        return el instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static boolean isBoolean(@Nullable JsonElement el) {
        return el instanceof JsonPrimitive p && p.isBoolean();
    }

    private static @Nullable BigDecimal number(@Nullable JsonElement el) {
        if (!(el instanceof JsonPrimitive p) || !p.isNumber()) return null;
        BigDecimal n;
        try {
            n = new BigDecimal(p.getAsString());
        } catch (NumberFormatException _) {
            return null;
        }
        return Math.abs(n.scale()) > NUMBER_DIGITS || n.precision() > NUMBER_DIGITS ? null : n;
    }

    private static boolean whole(BigDecimal n) {
        return n.signum() == 0 || n.stripTrailingZeros().scale() <= 0;
    }

    private static String plain(BigDecimal n) {
        return n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString();
    }
}
