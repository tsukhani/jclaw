package agents;

/**
 * JF-197: thrown at a sync turn's checkpoint ({@link AgentRunner#checkTurnCancel}) once an operator stop has set the
 * turn's own cancel flag. The turn that took its conversation turns it into a stopped result, so it never reaches the
 * catch of a subagent or task run, where a {@link RunCancelledException} means a kill.
 */
final class TurnStoppedException extends RunCancelledException {

    TurnStoppedException() {
        super("Turn stopped by the operator");
    }
}
