/**
 * The per-agent knowledge-graph store: canonical JSONL documents under {@code data/memory-graph},
 * written atomically and validated against the seed ontology. Types default to non-null;
 * {@code @NullMarked} does not reach subpackages, so a new one needs its own
 * {@code package-info.java}.
 */
@NullMarked
package memory.graph;

import org.jspecify.annotations.NullMarked;
