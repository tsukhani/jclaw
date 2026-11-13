package tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Where a script's interpreter is started (JF-380), always inside {@link HarnessSandbox#wrapScript}. */
public final class ScriptProcess {

    private ScriptProcess() {}

    /**
     * Start {@code interpreterArgv} in {@code scratch}, confined by {@link HarnessSandbox#wrapScript}, with its three
     * standard streams piped. Its environment is {@code HOME} and {@code TMPDIR}, both {@code scratch}, and nothing of
     * JClaw's own, so no variable the JVM was started with reaches the interpreter.
     *
     * @throws HarnessSandbox.SandboxUnavailableException when the run cannot be confined; nothing has been started,
     *         and the caller refuses the run
     */
    public static Process start(List<String> interpreterArgv, Path scratch, Path interpreter) throws IOException {
        var pb = new ProcessBuilder(HarnessSandbox.wrapScript(interpreterArgv, scratch, interpreter));
        pb.directory(scratch.toFile());
        var env = pb.environment();
        env.clear();
        env.put("HOME", scratch.toString());
        env.put("TMPDIR", scratch.toString());
        return pb.start();
    }
}
