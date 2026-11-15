package services.secrets;

import org.jspecify.annotations.Nullable;
import utils.HttpKeys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two rewrites the secrets proxy makes (JF-324), each over a stream so a body of any size passes: a placeholder
 * becomes its value on the way to a host the secret is bound to, encoded for where it sits, and a value a server
 * sends back becomes its placeholder again, so the command never holds one either way. A renewed secret's value taken
 * from a sign-in answer is replaced the same way, in the JSON strings that hold it too (JF-374).
 */
final class PlaceholderRewriter {

    /** How a value is written where its placeholder stood. */
    enum Encoding { RAW, HEADER, PERCENT, FORM, JSON }

    /** A request the proxy will not forward, with the reason the command is told. */
    static final class Refused extends Exception {
        Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    private static final byte[] PREFIX = SecretGrants.PLACEHOLDER_PREFIX.getBytes(StandardCharsets.US_ASCII);
    private static final int LENGTH = SecretGrants.PLACEHOLDER_LENGTH;
    private static final String HEX = "0123456789ABCDEF";

    private PlaceholderRewriter() {}

    /** The encoding a request body's placeholders take, from its content type. */
    static Encoding bodyEncoding(String contentType) {
        var type = contentType.toLowerCase(Locale.ROOT);
        int semi = type.indexOf(';');
        if (semi >= 0) type = type.substring(0, semi);
        type = type.strip();
        if (type.equals("application/x-www-form-urlencoded")) return Encoding.FORM;
        if (type.equals(HttpKeys.APPLICATION_JSON) || type.endsWith("+json")) return Encoding.JSON;
        return Encoding.RAW;
    }

    /**
     * How often one request carried each secret (JF-374): every stand-in the proxy replaced, wherever it stood, and
     * which stand-in it was. A stand-in written escaped is not replaced, so it is not counted, and nor is one whose
     * value is empty, which sends nothing.
     */
    static final class Replaced {
        private record Key(String skill, String name) {}

        private record Count(String placeholder, int times) {}

        private final ConcurrentHashMap<Key, Count> counts = new ConcurrentHashMap<>();

        void add(SecretGrant.Binding binding) {
            if (binding.value().isEmpty()) return;
            counts.merge(new Key(binding.skill(), binding.name()), new Count(binding.placeholder(), 1),
                    (was, one) -> new Count(was.placeholder(), was.times() + 1));
        }

        int times(String skill, String name) {
            var count = counts.get(new Key(skill, name));
            return count == null ? 0 : count.times();
        }

        @Nullable String placeholder(String skill, String name) {
            var count = counts.get(new Key(skill, name));
            return count == null ? null : count.placeholder();
        }
    }

    /** {@code text} with each placeholder replaced and counted in {@code replaced}; the request goes to {@code host}:{@code port}. */
    static String substitute(String text, SecretGrant grant, String host, int port, Encoding encoding, Replaced replaced)
            throws Refused {
        if (!text.contains(SecretGrants.PLACEHOLDER_PREFIX)) return text;
        var in = text.getBytes(StandardCharsets.ISO_8859_1);
        var out = new ByteArrayOutputStream(in.length + 64);
        try {
            new Substituter(grant, host, port, encoding, replaced).feed(in, in.length, true, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    /** An {@code Authorization: Basic} value with placeholders inside its encoded credentials replaced and counted. */
    static String substituteBasic(String headerValue, SecretGrant grant, String host, int port, Replaced replaced)
            throws Refused {
        var trimmed = headerValue.strip();
        if (!trimmed.regionMatches(true, 0, "Basic ", 0, 6)) return headerValue;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed.substring(6).strip());
        } catch (IllegalArgumentException _) {
            return headerValue;
        }
        var credentials = new String(decoded, StandardCharsets.UTF_8);
        if (!credentials.contains(SecretGrants.PLACEHOLDER_PREFIX)) return headerValue;
        var out = new ByteArrayOutputStream();
        try {
            var bytes = credentials.getBytes(StandardCharsets.UTF_8);
            new Substituter(grant, host, port, Encoding.RAW, replaced).feed(bytes, bytes.length, true, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return "Basic " + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * Placeholder to value, over a stream. {@link #feed} writes what is settled and returns how much of the input it
     * consumed; the caller carries the rest, at most a placeholder's length, into the next call.
     */
    static final class Substituter {
        private final SecretGrant grant;
        private final String host;
        private final int port;
        private final Encoding encoding;
        private final Replaced replaced;

        Substituter(SecretGrant grant, String host, int port, Encoding encoding, Replaced replaced) {
            this.grant = grant;
            this.host = host;
            this.port = port;
            this.encoding = encoding;
            this.replaced = replaced;
        }

        int feed(byte[] buf, int len, boolean last, OutputStream out) throws IOException, Refused {
            int written = 0;
            int from = 0;
            while (true) {
                int at = indexOf(buf, PREFIX, from, len);
                if (at < 0) {
                    int safe = last ? len : Math.max(written, len - (PREFIX.length - 1));
                    out.write(buf, written, safe - written);
                    return safe;
                }
                if (at + LENGTH > len) {
                    if (!last) {
                        out.write(buf, written, at - written);
                        return at;
                    }
                    from = at + 1;
                    continue;
                }
                var token = new String(buf, at, LENGTH, StandardCharsets.US_ASCII);
                if (!SecretGrants.PLACEHOLDER.matcher(token).matches()) {
                    from = at + 1;
                    continue;
                }
                out.write(buf, written, at - written);
                out.write(valueFor(token));
                written = at + LENGTH;
                from = written;
            }
        }

        private byte[] valueFor(String token) throws Refused {
            var binding = grant.binding(token);
            if (binding == null) {
                var withdrawn = grant.revoked(token);
                if (withdrawn != null) {
                    throw new Refused(("secret %s is no longer given to this command: what gave it changed after the"
                            + " command started, or could not be re-checked").formatted(withdrawn.name()));
                }
                throw new Refused("it carries a secret placeholder this command was not given, or one whose command"
                        + " has ended");
            }
            if (!binding.allows(host, port)) {
                throw new Refused("secret %s may be sent only to %s, not to %s".formatted(binding.name(),
                        String.join(", ", binding.hosts()), port == 443 ? host : host + ":" + port));
            }
            var value = binding.value();
            var bytes = switch (encoding) {
                case RAW -> value.getBytes(StandardCharsets.UTF_8);
                case HEADER -> {
                    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                        throw new Refused("secret %s holds a line break, so it cannot go in a header"
                                .formatted(binding.name()));
                    }
                    yield value.getBytes(StandardCharsets.UTF_8);
                }
                case PERCENT -> percent(value).getBytes(StandardCharsets.US_ASCII);
                case FORM -> URLEncoder.encode(value, StandardCharsets.UTF_8).getBytes(StandardCharsets.US_ASCII);
                case JSON -> json(value).getBytes(StandardCharsets.UTF_8);
            };
            replaced.add(binding);
            return bytes;
        }
    }

    /** Value to placeholder, over a stream, holding back what a longer value could still span. */
    static final class Masker {
        private final byte[][] values;
        private final byte[][] placeholders;
        private final int longest;

        Masker(List<SecretGrant.Binding> bindings) {
            // Longest first: where two values start at one place, the longer is masked whole.
            var usable = bindings.stream().filter(b -> !b.value().isEmpty())
                    .sorted(Comparator.comparingInt((SecretGrant.Binding b) -> b.value().length()).reversed()).toList();
            values = new byte[usable.size()][];
            placeholders = new byte[usable.size()][];
            int max = 1;
            for (int i = 0; i < usable.size(); i++) {
                values[i] = usable.get(i).value().getBytes(StandardCharsets.UTF_8);
                placeholders[i] = usable.get(i).placeholder().getBytes(StandardCharsets.US_ASCII);
                max = Math.max(max, values[i].length);
            }
            longest = max;
        }

        int feed(byte[] buf, int len, boolean last, OutputStream out) throws IOException {
            int written = 0;
            int from = 0;
            while (true) {
                int best = -1;
                int which = -1;
                for (int v = 0; v < values.length; v++) {
                    int at = indexOf(buf, values[v], from, len);
                    if (at >= 0 && (best < 0 || at < best)) {
                        best = at;
                        which = v;
                    }
                }
                if (best < 0 || (!last && best + longest > len)) {
                    int hold = best >= 0 ? best : len - (longest - 1);
                    int safe = last ? len : Math.max(written, hold);
                    out.write(buf, written, safe - written);
                    return safe;
                }
                out.write(buf, written, best - written);
                out.write(placeholders[which]);
                written = best + values[which].length;
                from = written;
            }
        }

        /** {@code text} as OkHttp decoded it, UTF-8, with each value replaced. */
        String mask(String text) {
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            var out = new ByteArrayOutputStream(bytes.length);
            try {
                feed(bytes, bytes.length, true, out);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    static int indexOf(byte[] buf, byte[] needle, int from, int len) {
        if (needle.length == 0) return -1;
        for (int i = Math.max(0, from); i <= len - needle.length; i++) {
            if (Arrays.equals(buf, i, i + needle.length, needle, 0, needle.length)) return i;
        }
        return -1;
    }

    /** Every byte but RFC 3986's unreserved ones, percent-encoded: right in a path segment and a query alike. */
    static String percent(String value) {
        var out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xf));
            }
        }
        return out.toString();
    }

    /**
     * {@code json}, a document a strict parser accepted, with each string that holds one of the {@code replaced}
     * values written again with that value replaced by the binding's placeholder (JF-374). Only those strings change:
     * matching their decoded text finds a value however the service escaped it, as in {@code a\/b} or <code>&#92;u0061</code>.
     */
    static String replaceInJsonStrings(String json, List<SecretGrant.Binding> replaced) {
        var longestFirst = replaced.stream().filter(b -> !b.value().isEmpty())
                .sorted(Comparator.comparingInt((SecretGrant.Binding b) -> b.value().length()).reversed())
                .toList();
        var out = new StringBuilder(json.length());
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c != '"') {
                out.append(c);
                i++;
                continue;
            }
            var decoded = new StringBuilder();
            int end = i + 1;
            while (json.charAt(end) != '"') {
                char d = json.charAt(end);
                if (d != '\\') {
                    decoded.append(d);
                    end++;
                    continue;
                }
                char e = json.charAt(end + 1);
                if (e == 'u') {
                    decoded.append((char) Integer.parseInt(json.substring(end + 2, end + 6), 16));
                    end += 6;
                    continue;
                }
                decoded.append(switch (e) {
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> e;
                });
                end += 2;
            }
            var text = decoded.toString();
            var rewritten = text;
            for (var b : longestFirst) rewritten = rewritten.replace(b.value(), b.placeholder());
            if (rewritten.equals(text)) out.append(json, i, end + 1);
            else out.append('"').append(json(rewritten)).append('"');
            i = end + 1;
        }
        return out.toString();
    }

    /** The inside of a JSON string holding {@code value}. */
    static String json(String value) {
        var out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append("\\u%04x".formatted((int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
