package services.printing;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * JetDirect / AppSocket printing — stream the document straight down a TCP
 * socket, conventionally port 9100 (JCLAW-911).
 *
 * <p>There is no protocol here. The printer treats whatever arrives as a print
 * job and the connection close as end-of-job, which is why it works on almost
 * everything and tells you almost nothing.
 *
 * <p><b>Success means the bytes were written, not that anything printed.</b> No
 * status comes back, so a printer that is out of paper, jammed, or fed a format
 * it cannot parse accepts the write and reports nothing. {@link PrintDispatcher}
 * therefore tries IPP first and marks raw-socket results as unverified, and the
 * tool says so in the text the model sees — an agent that reports "printed
 * successfully" off a blind write is stating something it cannot know.
 */
public final class RawSocketClient {

    private RawSocketClient() {}

    /**
     * Write {@code document} to {@code host:port} and close.
     *
     * @throws IOException if the socket cannot be opened or the write fails
     */
    public static void print(@Nullable String host, int port, byte[] document, int timeoutMs)
            throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            var out = socket.getOutputStream();
            out.write(document);
            out.flush();
            // Half-close so the printer sees EOF and starts the job. Without this
            // some firmware waits for its idle timeout before printing, which reads
            // as "the job never arrived" for 30+ seconds.
            socket.shutdownOutput();
        }
    }
}
