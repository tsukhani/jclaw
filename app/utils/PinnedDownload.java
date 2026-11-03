package utils;

import okhttp3.Request;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.IntConsumer;

/**
 * Downloads whose SHA-256 is pinned in the code rather than fetched beside the file, so a compromised mirror
 * cannot vouch for its own download.
 */
public final class PinnedDownload {

    private PinnedDownload() {}

    /**
     * Writes the body at {@code url} to {@code target}, and throws unless the server answered 2xx, the body is
     * at most {@code maxBytes} and it hashes to {@code sha256}. {@code onPercent} receives 0-100 as bytes
     * arrive. The hash is known only once the last byte arrives, so a failure can leave an unverified body in
     * {@code target}: fetch to a temporary file and delete it.
     */
    public static void fetch(String url, String sha256, long maxBytes, Path target, IntConsumer onPercent) throws IOException {
        var request = new Request.Builder().url(url).build();
        try (var response = HttpFactories.general().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " fetching " + url);
            }
            var body = response.body();
            long total = body.contentLength();
            if (total > maxBytes) throw new IOException("refusing a " + total + "-byte download of " + url);
            var digest = digest();
            try (var in = new DigestInputStream(body.byteStream(), digest);
                 OutputStream out = Files.newOutputStream(target)) {
                var buffer = new byte[64 * 1024];
                long read = 0;
                int reported = -1;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    read += n;
                    if (read > maxBytes) throw new IOException(url + " exceeds " + maxBytes + " bytes");
                    out.write(buffer, 0, n);
                    int pct = total > 0 ? (int) (read * 100 / total) : 0;
                    if (pct != reported) {
                        onPercent.accept(pct);
                        reported = pct;
                    }
                }
            }
            var actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(sha256)) {
                throw new IOException("checksum mismatch for " + url + ": expected " + sha256 + ", got " + actual);
            }
        }
    }

    /** {@code file}'s SHA-256, as lowercase hex. */
    public static String sha256(Path file) throws IOException {
        var digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JRE", e);
        }
    }
}
