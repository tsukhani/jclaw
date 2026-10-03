package services.grapheval;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Progress of a certification run (JCLAW-1359): a {@code pass} event when one model finishes one run over the case
 * set, and {@code heartbeat} events naming every pass under way. Counts only, so it is safe for the held-out set; the
 * report never sees it, so it carries no timings. Thread-safe: cases finish on the harness's pool and heartbeats
 * arrive from a timer.
 */
public final class EvalProgress {

    public static final String PASS = "pass";
    public static final String HEARTBEAT = "heartbeat";

    private final Consumer<JsonObject> sink;
    private final List<Pass> passes = new ArrayList<>();
    private int runs;
    private int cases;
    private boolean closed;

    public EvalProgress(Consumer<JsonObject> sink) {
        this.sink = sink;
    }

    public static EvalProgress none() {
        return new EvalProgress(_ -> {});
    }

    private static final class Pass {
        final String model;
        final int run;
        int done;
        int failed;
        long startedNs;
        boolean started;

        Pass(String model, int run) {
            this.model = model;
            this.run = run;
        }
    }

    /** Declares the passes in the order the harness indexes them: run-major, then model. */
    synchronized void plan(List<String> models, int runs, int cases) {
        this.runs = runs;
        this.cases = cases;
        passes.clear();
        for (int r = 1; r <= runs; r++) {
            for (var m : models) passes.add(new Pass(m, r));
        }
    }

    synchronized void caseStarted(int pass) {
        var p = passes.get(pass);
        if (!p.started) {
            p.started = true;
            p.startedNs = System.nanoTime();
        }
    }

    synchronized void caseFinished(int pass, int failedDecisions) {
        var p = passes.get(pass);
        p.done++;
        p.failed += failedDecisions;
        if (p.done != cases) return;
        var event = event(PASS, p);
        event.addProperty("failed", p.failed);
        event.addProperty("seconds", (System.nanoTime() - p.startedNs) / 1e9);
        emit(event);
    }

    /** One line per pass that has started and not finished. */
    public synchronized void heartbeat() {
        for (var p : passes) {
            if (p.started && p.done < cases) emit(event(HEARTBEAT, p));
        }
    }

    /** Stops every later event, so a heartbeat cannot follow the report. */
    public synchronized void close() {
        closed = true;
    }

    private JsonObject event(String type, Pass p) {
        var event = new JsonObject();
        event.addProperty("event", type);
        event.addProperty("model", p.model);
        event.addProperty("run", p.run);
        event.addProperty("runs", runs);
        event.addProperty("done", p.done);
        event.addProperty("cases", cases);
        return event;
    }

    private void emit(JsonObject event) {
        if (!closed) sink.accept(event);
    }
}
