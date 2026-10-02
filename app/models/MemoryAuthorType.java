package models;

/**
 * Whose words a {@link Memory} rests on (JCLAW-1318). Lives in {@code models} rather than
 * {@code memory} because {@code models} must not import from {@code memory}. A null
 * {@link Memory#authorType} is a row written before provenance existed.
 */
public enum MemoryAuthorType {
    /** Grounded in something the operator said in a conversation turn. */
    HUMAN_TURN,
    /** Written by an agent with no human turn behind it — a task run, say. */
    AGENT_SYNTHESIZED,
    /** Derived from other memories; exactly the rows carrying {@link MemoryDerivation} links. */
    CONSOLIDATION_DERIVED
}
