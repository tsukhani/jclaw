package services.secrets;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.SkillSecrets;
import utils.UrlPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the secrets proxy takes from an answer that a renewed secret's capture rule matches (JF-374). The rule is the
 * operator's, stored with the secret: editing or deleting the SKILL.md line that suggested it changes nothing here. A
 * success or a redirect from one of the secret's hosts, answering a request the service would route to the rule's
 * method and path, gives up the value when the request sent the rule's credential, a secret with a value, once and
 * only in the place the rule names: the proxy stores it, and the command receives its stand-in for the secret, or a
 * marker when it was not given that secret. The proxy writes the credential's value in place of every one of its
 * stand-ins, so a sign-in that sent it more than once, or anywhere else, went out with the operator's credential and
 * an answer whose session cannot be told apart: that answer is withheld. Only a sign-in that never sent the credential
 * passes as before, since its session is whoever supplied the credentials it did send. A matching answer the value
 * cannot be taken from is withheld whole, and so is one to a request whose body cannot be read for the credential, or
 * to a path holding an escaped slash that a server may read as the rule's: a miss fails closed, so a rule whose method
 * and path still match the sign-in never lets the real key through when the service moves the key elsewhere in its
 * answer. A sign-in the service moved to another method or path is no longer matched, and its answer passes as before.
 *
 * <p>The value is replaced where it appears as it was taken: in the header a header rule names, anywhere else in the
 * answer as the same bytes, and in a JSON answer inside any string however it is escaped. A header-captured key that
 * the answer's body also carries escaped or percent-encoded is not found there.
 */
final class SecretCapture {

    static final String WITHHELD_REPLY = "JClaw withheld this response: renewing a secret from it failed.";
    static final String UNCHECKED = "JClaw could not check this request's secrets; try again.";
    private static final String MARKER = "[renewed: %s]";
    private static final int LIMIT = SecretProxy.BUFFERED_BODY_LIMIT;
    /** The headers a server may route a request by in place of its method. */
    private static final List<String> METHOD_OVERRIDES = List.of("X-HTTP-Method-Override", "X-HTTP-Method",
            "X-Method-Override");
    private static final Pattern METHOD = Pattern.compile("[A-Za-z]{1,16}");
    /** The query or form field a server may route a request by in place of its method. */
    private static final String METHOD_PARAMETER = "_method";
    /** A header value that is a token after at most one scheme word, as in {@code Bearer TOKEN}. */
    private static final Pattern SCHEMED = Pattern.compile("(?:[!#$%&'*+.^_`|~0-9A-Za-z-]+ +)?(\\S+)");

    /**
     * The request as the command sent it, before any placeholder was replaced: its head's headers, its body, null when
     * it was too long to read whole, and its content type as forwarded.
     */
    @SuppressWarnings("java:S6218") // carries one request's bytes and is never compared, hashed or printed
    record Sent(List<String[]> headers, byte @Nullable [] body, @Nullable String contentType) {}

    /** What a capture that stored its values sends in place of the answer's body: the body it rewrote, or null. */
    @SuppressWarnings("java:S6218") // carries one answer's bytes and is never compared, hashed or printed
    record Taken(byte @Nullable [] body) {}

    /**
     * How a request carried a rule's credential: once and only in the rule's place, not at all, any other way, or,
     * once in a body the proxy could not read for the place, unknown.
     */
    private enum Credential { SENT, NOT_SENT, STRAY, UNREAD }

    private final SecretGrant grant;
    private final String host;
    /**
     * The request as an event names it: the rule's method and path, never the request's own path, which holds the
     * value of any secret whose placeholder the command put there.
     */
    private final String request;
    /** Before {@link #judge}, every rule the request was routed to; after it, those the answer is taken or withheld for. */
    private final List<SkillSecrets.Renewable> rules;
    private final Sent sent;
    /** The path holds an escaped slash, so whether the service read it as the sign-in's cannot be known. */
    private final boolean ambiguous;
    /** Why the answer is withheld whatever it holds, or null when the rules' values are taken from it. */
    private final @Nullable String withheld;

    private SecretCapture(SecretGrant grant, String host, List<SkillSecrets.Renewable> rules, Sent sent,
                          boolean ambiguous, @Nullable String withheld) {
        var rule = rules.getFirst().capture();
        this.grant = grant;
        this.host = host;
        this.request = rule.method() + " " + rule.path();
        this.rules = rules;
        this.sent = sent;
        this.ambiguous = ambiguous;
        this.withheld = withheld;
    }

    /**
     * The rules of the grant owner's renewed secrets that {@code request} could be routed to, for {@link #judge} once
     * it is sent, or null when there is none. Read before the request is sent, so a failed read sends nothing.
     */
    static @Nullable SecretCapture forRequest(SecretGrant grant, String host, int port, Request request, Sent sent) {
        if (!grant.forAgent()) return null;
        var forHost = SecretGrants.renewable(grant).stream()
                .filter(r -> SecretGrant.allowsHost(r.hosts(), host, port)).toList();
        if (forHost.isEmpty()) return null;
        var readings = UrlPaths.readings(request.url().encodedPath());
        var onPath = forHost.stream()
                .filter(r -> UrlPaths.matches(readings, r.capture().path())).toList();
        if (onPath.isEmpty()) return null;
        var methods = methods(request, sent);
        var routed = onPath.stream()
                .filter(r -> methods == null || methods.contains(effective(r.capture().method()))).toList();
        return routed.isEmpty() ? null : new SecretCapture(grant, host, routed, sent, readings.ambiguous(), null);
    }

    /**
     * The capture the answer makes, judged from {@code replaced}, every stand-in the proxy put a value in for once the
     * whole request, a streamed body included, was sent: null when no rule's credential went out, so the sign-in was
     * made with credentials of its own and its answer passes as before.
     */
    @Nullable SecretCapture judge(PlaceholderRewriter.Replaced replaced) {
        var own = new ArrayList<SkillSecrets.Renewable>();
        var stray = new ArrayList<SkillSecrets.Renewable>();
        var unknown = new ArrayList<SkillSecrets.Renewable>();
        for (var r : rules) {
            switch (credential(r, sent, replaced)) {
                case SENT -> own.add(r);
                case STRAY -> stray.add(r);
                case UNREAD -> unknown.add(r);
                case NOT_SENT -> { }
            }
        }
        if (!stray.isEmpty()) {
            return judged(stray, "its request sent " + String.join(", ", stray.stream().map(r -> r.capture().credential()
                    + " other than once in " + r.capture().credentialIn().wire()).distinct().toList()));
        }
        if (!unknown.isEmpty()) {
            return judged(unknown, ("the proxy could not read its request body, too long or malformed, so whether it sent"
                    + " %s only in the place its rule names is unknown").formatted(String.join(", ", unknown.stream()
                    .map(r -> r.capture().credential()).distinct().toList())));
        }
        if (own.isEmpty()) return null;
        return judged(own, ambiguous ? "its path holds an escaped slash, which the service may read as the rule's path"
                : null);
    }

    private SecretCapture judged(List<SkillSecrets.Renewable> to, @Nullable String why) {
        return new SecretCapture(grant, host, to, sent, ambiguous, why);
    }

    /**
     * How {@code sent} carried {@code rule}'s credential, from the times {@code replaced} counts the proxy wrote its
     * value: never; once, with the rule's place occurring once and holding the stand-in alone; or any other way. A
     * stand-in written escaped is never replaced, and one of a secret with no value sends nothing, so neither counts.
     */
    private static Credential credential(SkillSecrets.Renewable rule, Sent sent, PlaceholderRewriter.Replaced replaced) {
        var name = rule.capture().credential();
        int times = replaced.times(rule.skill(), name);
        if (times == 0) return Credential.NOT_SENT;
        if (times > 1) return Credential.STRAY;
        var values = valuesIn(rule.capture().credentialIn(), sent);
        // A body the proxy could not read may hold the one copy in the rule's place, or anywhere else.
        if (values == null) return Credential.UNREAD;
        return values.size() == 1 && Objects.equals(replaced.placeholder(rule.skill(), name), values.getFirst())
                ? Credential.SENT : Credential.STRAY;
    }

    /** Each occurrence of {@code place} in {@code sent}, as the command wrote it; null for a body that cannot be read. */
    private static @Nullable List<@Nullable String> valuesIn(SkillSecrets.Capture.Place place, Sent sent) {
        var name = place.name();
        var body = sent.body();
        return switch (place.kind()) {
            case HEADER -> SecretProxy.forwardsHeader(Objects.requireNonNull(name))
                    ? headers(sent, name).stream().map(SecretCapture::token).toList() : List.of();
            case BASIC -> headers(sent, "Authorization").stream().map(SecretCapture::basicPassword).toList();
            case JSON -> body == null ? null : jsonStrings(body, Objects.requireNonNull(name));
            case FORM -> body == null ? null : formFields(body, sent.contentType(), Objects.requireNonNull(name));
        };
    }

    private static List<String> headers(Sent sent, String name) {
        var out = new ArrayList<String>();
        for (var h : sent.headers()) {
            if (h[0].equalsIgnoreCase(name)) out.add(h[1]);
        }
        return out;
    }

    private static @Nullable String token(String value) {
        var m = SCHEMED.matcher(value.strip());
        return m.matches() ? m.group(1) : null;
    }

    private static @Nullable String basicPassword(String value) {
        var trimmed = value.strip();
        if (!trimmed.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(trimmed.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return null;
        }
        int colon = credentials.indexOf(':');
        return colon < 0 ? null : credentials.substring(colon + 1);
    }

    /**
     * Each top-level field {@code name} of {@code body} read as one JSON object, whatever its content type says: a
     * string's text as written between its quotes, since only a stand-in written plainly is one the proxy replaced,
     * and a field that is not a string as null. None for an empty body; null for one that is no JSON object.
     */
    private static @Nullable List<@Nullable String> jsonStrings(byte[] body, String name) {
        if (body.length == 0) return List.of();
        var text = BodyFields.utf8(body);
        if (text == null || BodyFields.jsonFields(text) == null) return null;
        var out = new ArrayList<@Nullable String>();
        for (var field : BodyFields.rawJsonFields(text)) {
            if (field.name().equals(name)) out.add(field.raw());
        }
        return out;
    }

    /**
     * Each field {@code name} of a form body as the command wrote it: a multipart part's content, or an urlencoded
     * value before decoding, since only a stand-in written plainly is one the proxy replaced. Null for a multipart body
     * whose parts cannot be read.
     */
    private static @Nullable List<String> formFields(byte[] body, @Nullable String contentType, String name) {
        var media = contentType == null ? null : MediaType.parse(contentType);
        if (contentType != null && media == null && BodyFields.namesMultipart(contentType)) return null;
        if (media != null && BodyFields.multipart(media)) return BodyFields.multipartValues(body, media, name);
        return BodyFields.urlencodedValues(body, name);
    }

    /** Whether a capture takes from {@code response}: a success or a redirect, as a service answers a sign-in. */
    static boolean answers(Response response) {
        return response.code() >= 200 && response.code() < 400;
    }

    /**
     * Every method a server may route {@code request} as: its own, each one a method-override header names, and each
     * {@code _method} of its query and of a form, multipart or JSON body; null, for every method, when such a body could
     * not be read for one.
     */
    private static @Nullable Set<String> methods(Request request, Sent sent) {
        var named = new ArrayList<@Nullable String>();
        for (var header : METHOD_OVERRIDES) {
            for (var value : request.headers(header)) named.addAll(List.of(value.split(",")));
        }
        named.addAll(request.url().queryParameterValues(METHOD_PARAMETER));
        var fromBody = bodyMethods(sent);
        if (fromBody == null) return null;
        named.addAll(fromBody);
        var out = new HashSet<String>();
        out.add(effective(request.method()));
        for (var method : named) {
            if (method != null && METHOD.matcher(method.strip()).matches()) out.add(effective(method.strip()));
        }
        return out;
    }

    /** The {@code _method} fields of the body, or null when its kind may carry one and it could not be read. */
    private static @Nullable List<@Nullable String> bodyMethods(Sent sent) {
        var contentType = sent.contentType();
        if (contentType == null) return List.of();
        var encoding = PlaceholderRewriter.bodyEncoding(contentType);
        var media = MediaType.parse(contentType);
        if (media == null) {
            // The proxy wrote the body as its type says; a server may still read a type it cannot parse whole.
            return encoding == PlaceholderRewriter.Encoding.RAW && !BodyFields.namesMultipart(contentType) ? List.of()
                    : null;
        }
        boolean multipart = BodyFields.multipart(media);
        if (!multipart && encoding == PlaceholderRewriter.Encoding.RAW) return List.of();
        var body = sent.body();
        if (body == null) return null;
        if (multipart) {
            var values = BodyFields.multipartValues(body, media, METHOD_PARAMETER);
            return values == null ? null : values.stream()
                    .map(v -> new String(v.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)).toList();
        }
        if (encoding == PlaceholderRewriter.Encoding.FORM) {
            return BodyFields.urlencodedValues(body, METHOD_PARAMETER).stream().map(BodyFields::formDecoded).toList();
        }
        return jsonMethods(body);
    }

    /** The {@code _method} fields of a JSON body: none when it is no object, null when it cannot be read. */
    private static @Nullable List<@Nullable String> jsonMethods(byte[] body) {
        if (body.length == 0) return List.of();
        var text = BodyFields.utf8(body);
        if (text == null) return null;
        try {
            var reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return List.of();
        } catch (IOException | RuntimeException _) {
            return null;
        }
        var fields = BodyFields.jsonFields(text);
        if (fields == null) return null;
        var out = new ArrayList<@Nullable String>();
        for (var field : fields) {
            if (field.getKey().equals(METHOD_PARAMETER) && field.getValue() instanceof JsonPrimitive p && p.isString()) {
                out.add(p.getAsString());
            }
        }
        return out;
    }

    /** A server answers a HEAD from its GET route. */
    private static String effective(String method) {
        var upper = method.toUpperCase(Locale.ROOT);
        return upper.equals("HEAD") ? "GET" : upper;
    }

    /**
     * Take each rule's value from {@code response}, an answer {@link #answers} accepts, as {@link #judge} decided, and
     * store them all; null when the judgment withheld the answer, or a value cannot be taken or stored. A rule taking
     * a JSON field reads the body, which the caller then sends from {@link Taken#body} rather than from the response.
     */
    @Nullable Taken take(Response response, boolean noBody) throws IOException {
        if (withheld != null) return miss(rules, withheld);
        if (!noBody && compressed(response)) return miss(rules, "it is still compressed");
        String text = null;
        Parsed json = null;
        if (rules.stream().anyMatch(r -> r.capture().from() == SkillSecrets.Capture.From.JSON)) {
            if (noBody) return miss(rules, "it has no body");
            var body = readUpTo(response.body().byteStream(), LIMIT + 1);
            if (body.length > LIMIT) return miss(rules, "its body is longer than the %d bytes the proxy reads".formatted(LIMIT));
            text = BodyFields.utf8(body);
            json = text == null ? null : object(text);
            if (json == null) return miss(rules, "its body is not a JSON object");
        }
        var captured = new ArrayList<SecretGrants.Captured>();
        for (var rule : rules) {
            var value = value(rule, response, json);
            var why = whyNotTaken(rule, value, json);
            if (why != null) return miss(List.of(rule), why);
            captured.add(new SecretGrants.Captured(rule, Objects.requireNonNull(value)));
        }
        SkillSecrets.@Nullable Renewable refused;
        try {
            refused = SecretGrants.storeCaptured(grant, captured);
        } catch (RuntimeException e) {
            // The class alone: a database error's message can quote the row it failed to write.
            return miss(rules, "storing it failed (%s)".formatted(e.getClass().getSimpleName()));
        }
        if (refused != null) return miss(List.of(refused), "the secret or its rule changed");
        var replaced = bindings(captured);
        grant.maskFromNowOn(replaced);
        byte[] body = text == null ? null
                : PlaceholderRewriter.replaceInJsonStrings(text, replaced).getBytes(StandardCharsets.UTF_8);
        return new Taken(body);
    }

    /** The value {@code rule} takes from the answer: its header, or its field of {@code json}; null when there is none. */
    private static @Nullable String value(SkillSecrets.Renewable rule, Response response, @Nullable Parsed json) {
        var field = rule.capture().field();
        if (rule.capture().from() != SkillSecrets.Capture.From.JSON) return header(response, field);
        return json == null ? null : string(json.object(), field);
    }

    /** Why {@code value}, which {@code rule} took from the answer, cannot be stored; null when it can. */
    private static @Nullable String whyNotTaken(SkillSecrets.Renewable rule, @Nullable String value,
                                                @Nullable Parsed json) {
        var field = rule.capture().field();
        // A parser keeps one of two same-named fields and a client may read the other: which is the key is unknown.
        if (rule.capture().from() == SkillSecrets.Capture.From.JSON && json != null && json.repeated().contains(field)) {
            return "it has the field " + field + " more than once";
        }
        if (value == null) {
            return rule.capture().from() == SkillSecrets.Capture.From.HEADER
                    ? "it has no single, non-empty header " + field
                    : "it has no field " + field + " holding a non-empty string";
        }
        if (value.length() > SkillSecrets.MAX_VALUE_CHARS) {
            return "its value is longer than the %d characters a secret holds".formatted(SkillSecrets.MAX_VALUE_CHARS);
        }
        if (!SkillSecrets.headerSafe(value)) return "its value holds a line break or a NUL, which no header can carry";
        return null;
    }

    /** Each stored value, bound to the grant's stand-in for its secret, or to a marker when the grant has none. */
    private List<SecretGrant.Binding> bindings(List<SecretGrants.Captured> captured) {
        var out = new ArrayList<SecretGrant.Binding>();
        for (var c : captured) {
            var rule = c.rule();
            var placeholder = grant.placeholderOf(rule.skill(), rule.name());
            out.add(new SecretGrant.Binding(rule.skill(), rule.name(), c.value(), List.of(),
                    placeholder != null ? placeholder : MARKER.formatted(rule.name())));
        }
        return out;
    }

    /** Whether the body is still encoded: OkHttp undoes only the gzip it asked for itself. */
    private static boolean compressed(Response response) {
        for (var value : response.headers("Content-Encoding")) {
            for (var coding : value.split(",")) {
                var name = coding.strip();
                if (!name.isEmpty() && !name.equalsIgnoreCase("identity")) return true;
            }
        }
        return false;
    }

    /** Record why the answer is withheld, naming the secrets and never a value, and withhold it. */
    private @Nullable Taken miss(List<SkillSecrets.Renewable> missed, String why) {
        var names = String.join(", ", missed.stream().map(SkillSecrets.Renewable::name).toList());
        EventLogger.warn(SkillSecrets.LOG_CATEGORY, grant.ownerName(), null, ("Secrets proxy: withheld the answer to %s"
                + " from %s, as %s could not be renewed from it: %s").formatted(request, host, names, why));
        return null;
    }

    private static @Nullable String header(Response response, String name) {
        var values = response.headers(name);
        return values.size() == 1 && !values.getFirst().isEmpty() ? values.getFirst() : null;
    }

    private static @Nullable String string(JsonObject json, String field) {
        return json.get(field) instanceof JsonPrimitive p && p.isString() && !p.getAsString().isEmpty() ? p.getAsString()
                : null;
    }

    /** A JSON object, and the names of its top-level fields that occur more than once. */
    private record Parsed(JsonObject object, Set<String> repeated) {}

    /** {@code text} as one JSON object, read strictly, so every string in it is one {@link PlaceholderRewriter} can find. */
    private static @Nullable Parsed object(String text) {
        var fields = BodyFields.jsonFields(text);
        if (fields == null) return null;
        var object = new JsonObject();
        var repeated = new HashSet<String>();
        for (var field : fields) {
            if (object.has(field.getKey())) repeated.add(field.getKey());
            object.add(field.getKey(), field.getValue());
        }
        return new Parsed(object, Set.copyOf(repeated));
    }

    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        var out = new ByteArrayOutputStream();
        var buf = new byte[16_384];
        int n;
        while (out.size() < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - out.size()))) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
