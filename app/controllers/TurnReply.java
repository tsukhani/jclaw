package controllers;

import agents.AgentRunner;

import java.util.Map;

/** The reply fields of a route that runs a turn and answers with its result (JF-166). */
final class TurnReply {

    private TurnReply() { /* static-only utility */ }

    /**
     * Puts the turn's {@code response}. A turn that suspended on {@code subagent_yield} has no reply yet:
     * it gets a null {@code response}, {@code "status": "waiting"} and the awaited runs in {@code waitingOnRunIds};
     * the turn resumes in the same conversation once they end.
     */
    static void put(Map<String, Object> resp, AgentRunner.RunResult result) {
        if (!result.yielded()) {
            resp.put("response", result.response());
            return;
        }
        resp.put("response", null);
        resp.put("status", "waiting");
        resp.put("waitingOnRunIds", result.yieldedRunIds());
    }
}
