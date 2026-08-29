package services.printing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * RFC 1179 Line Printer Daemon client — the last-resort print backend (JCLAW-911).
 *
 * <p>Written from the RFC rather than adapted from existing code. The reference
 * implementation the ticket pointed at carries no license at all, which makes
 * copying it into a repo with a public mirror indefensible; RFC 1179 is a public
 * IETF specification, so an implementation from the spec is unencumbered.
 *
 * <p>Protocol, in full — it is small. Every command is acknowledged by a single
 * zero byte; anything else is a refusal:
 * <pre>
 *   \002 queue \n                    "receive a printer job"        → ack
 *   \002 &lt;len&gt; SP cfA&lt;id&gt;&lt;host&gt; \n   "receive control file"          → ack
 *   &lt;control bytes&gt; \000                                            → ack
 *   \003 &lt;len&gt; SP dfA&lt;id&gt;&lt;host&gt; \n   "receive data file"             → ack
 *   &lt;document bytes&gt; \000                                           → ack
 * </pre>
 *
 * <p>Not implemented, deliberately: the RFC's requirement that the client bind a
 * source port in 721–731. Those are privileged ports, so honouring it would mean
 * running JClaw as root — a far worse trade than the trust it buys, and modern
 * daemons do not enforce it.
 */
public final class LpdClient {

    /** Control-file line: the originating host. */
    private static final char CTRL_HOST = 'H';
    /** Control-file line: the submitting user. */
    private static final char CTRL_USER = 'P';
    /** Control-file line: the job name shown on the printer's display/banner. */
    private static final char CTRL_JOB_NAME = 'J';
    /**
     * Control-file line: print the data file verbatim, leaving control characters
     * intact. Lowercase L, and load-bearing — the alternative ('f', formatted
     * text) makes the daemon interpret the stream as ASCII and paginate it, which
     * turns a PDF into hundreds of pages of mojibake.
     */
    private static final char CTRL_PRINT_RAW = 'l';
    /** Control-file line: the client-side name of the source file. */
    private static final char CTRL_SOURCE_NAME = 'N';
    /** Control-file line: unlink the data file once printed. */
    private static final char CTRL_UNLINK = 'U';

    private static final byte ACK_OK = 0;

    private LpdClient() {}

    /**
     * Send {@code document} to an RFC 1179 daemon.
     *
     * @param host        printer host
     * @param port        printer port (515 by convention)
     * @param queue       queue name; daemons commonly accept {@code "lp"} or {@code "raw"}
     * @param jobName     name shown on the printer's banner/display
     * @param user        submitting user recorded in the control file
     * @param document    the bytes to print, already in a format the printer understands
     * @param timeoutMs   connect and read timeout
     * @throws IOException if the connection fails or the daemon refuses any step
     */
    public static void print(String host, int port, String queue, String jobName,
                             String user, byte[] document, int timeoutMs) throws IOException {
        // Three digits, per the RFC's cfA<nnn> convention. Derived from the document
        // rather than a counter so the id is stable for a given job and carries no
        // cross-job state; collisions are harmless (the daemon scopes by host).
        var jobId = String.format("%03d", Math.floorMod(java.util.Arrays.hashCode(document), 1000));
        var localHost = safeToken(java.net.InetAddress.getLocalHost().getHostName());
        var controlName = "cfA" + jobId + localHost;
        var dataName = "dfA" + jobId + localHost;

        var control = buildControlFile(localHost, safeToken(user), jobName, dataName);

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            command(out, in, "\002" + queue + "\n", "open queue '" + queue + "'");
            sendFile(out, in, '\002', controlName, control, "control file");
            sendFile(out, in, '\003', dataName, document, "data file");
        }
    }

    /**
     * The control file: one {@code <letter><value>} line per attribute. Order is not
     * significant to the RFC, but host-then-user-then-job matches what daemons print
     * in their logs and makes a packet capture readable.
     */
    public static byte[] buildControlFile(String host, String user, String jobName, String dataName) {
        var sb = new StringBuilder();
        sb.append(CTRL_HOST).append(host).append('\n');
        sb.append(CTRL_USER).append(user).append('\n');
        sb.append(CTRL_JOB_NAME).append(safeToken(jobName)).append('\n');
        sb.append(CTRL_PRINT_RAW).append(dataName).append('\n');
        sb.append(CTRL_SOURCE_NAME).append(safeToken(jobName)).append('\n');
        sb.append(CTRL_UNLINK).append(dataName).append('\n');
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Send a command line and require the daemon's zero-byte acknowledgment. */
    private static void command(OutputStream out, InputStream in, String line, String what)
            throws IOException {
        out.write(line.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        expectAck(in, what);
    }

    /** Subcommand + length header, then the payload and its terminating NUL, each acked. */
    private static void sendFile(OutputStream out, InputStream in, char subCommand,
                                 String name, byte[] payload, String what) throws IOException {
        command(out, in, subCommand + Integer.toString(payload.length) + " " + name + "\n",
                what + " header");
        out.write(payload);
        out.write(0);
        out.flush();
        expectAck(in, what + " body");
    }

    /**
     * Read the one-byte acknowledgment. End-of-stream is treated as a refusal
     * rather than success: a daemon that closes the connection mid-job has not
     * accepted it, and reporting success there is how a job silently vanishes.
     */
    private static void expectAck(InputStream in, String what) throws IOException {
        int ack = in.read();
        if (ack == -1) {
            throw new IOException("LPD daemon closed the connection during " + what);
        }
        if (ack != ACK_OK) {
            throw new IOException("LPD daemon refused " + what + " (ack=" + ack + ")");
        }
    }

    /**
     * Strip whitespace and control characters from a control-file value.
     *
     * <p>Not cosmetic. Control-file records are newline-delimited, so an embedded
     * newline in a job name would inject an extra record and the daemon would read
     * the next line as a command — the LPD equivalent of header injection, reachable
     * here because job names come from model-supplied arguments.
     */
    public static String safeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "jclaw";
        }
        var cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        // RFC 1179 caps a control-file line at 131 characters including the
        // leading letter and trailing newline; stay well inside it.
        return cleaned.length() > 96 ? cleaned.substring(0, 96) : cleaned;
    }
}
