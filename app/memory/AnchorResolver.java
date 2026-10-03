package memory;

import models.Memory;
import models.MemoryDerivation;
import models.Message;
import org.jspecify.annotations.Nullable;
import services.TimezoneResolver;
import services.Tx;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which day a relative date in a memory resolves against (JCLAW-1361). For a span of memory M:
 * <ol>
 *   <li>if M's source message states the span, M's own anchor;</li>
 *   <li>else, if a predecessor (a memory M superseded, or a derivation input) states it, the base of the earliest
 *       such predecessor;</li>
 *   <li>else, if M is not derived, M's own anchor;</li>
 *   <li>else unresolved.</li>
 * </ol>
 * "States" is a case-insensitive, word-bounded verbatim match. A memory reached twice is unresolved.
 */
public final class AnchorResolver {

    /** The rows the rule reads. */
    public interface Lookup {
        Optional<Node> node(long memoryId);
    }

    /** A memory: its text, the day it was written, its source message's text, and its predecessors. */
    public record Node(String text, LocalDate anchor, @Nullable String sourceText, boolean derived,
            List<Predecessor> predecessors) {}

    public sealed interface Predecessor permits MemoryPred, MessagePred {
        long id();

        Instant createdAt();
    }

    /** A memory M superseded or was derived from; its text and base come from its own node. */
    public record MemoryPred(long id, Instant createdAt) implements Predecessor {}

    /** A message M was derived from, with the day it was sent. */
    public record MessagePred(long id, Instant createdAt, String text, LocalDate base) implements Predecessor {}

    private static final Comparator<Predecessor> EARLIEST = Comparator.comparing(Predecessor::createdAt)
            .thenComparingInt(p -> p instanceof MemoryPred ? 0 : 1).thenComparingLong(Predecessor::id);

    private final Lookup lookup;

    public AnchorResolver(Lookup lookup) {
        this.lookup = lookup;
    }

    /** The day {@code span} of memory {@code memoryId} resolves against, or empty when it cannot be told. */
    public Optional<LocalDate> base(long memoryId, String span) {
        return base(memoryId, statedBy(span), new HashSet<>());
    }

    private Optional<LocalDate> base(long memoryId, Pattern span, Set<Long> visited) {
        if (!visited.add(memoryId)) return Optional.empty();
        var found = lookup.node(memoryId);
        if (found.isEmpty()) return Optional.empty();
        var node = found.get();
        var source = node.sourceText();
        if (source != null && span.matcher(source).find()) return Optional.of(node.anchor());

        var stating = new ArrayList<Predecessor>();
        for (var p : node.predecessors()) {
            var text = switch (p) {
                case MemoryPred m -> lookup.node(m.id()).map(Node::text).orElse(null);
                case MessagePred m -> m.text();
            };
            if (text != null && span.matcher(text).find()) stating.add(p);
        }
        if (!stating.isEmpty()) {
            return switch (stating.stream().min(EARLIEST).orElseThrow()) {
                case MemoryPred m -> base(m.id(), span, visited);
                case MessagePred m -> Optional.of(m.base());
            };
        }
        return node.derived() ? Optional.empty() : Optional.of(node.anchor());
    }

    private static Pattern statedBy(String span) {
        return Pattern.compile("(?<!\\w)" + Pattern.quote(span) + "(?!\\w)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Reads the memory, message and derivation tables; anchors are app-zone days. */
    public static final class JpaLookup implements Lookup {

        @Override
        public Optional<Node> node(long memoryId) {
            return Tx.run(() -> Optional.ofNullable(load(memoryId)));
        }

        private static @Nullable Node load(long memoryId) {
            Memory m = Memory.findById(memoryId);
            if (m == null) return null;
            String sourceText = null;
            if (m.sourceMessageId != null) {
                Message source = Message.findById(m.sourceMessageId);
                if (source != null) sourceText = source.content;
            }
            var predecessors = new ArrayList<Predecessor>();
            List<Memory> superseded = Memory.find("agent = ?1 AND supersededById = ?2", m.agent, m.id).fetch();
            for (var s : superseded) predecessors.add(new MemoryPred(s.id, s.createdAt));
            List<MemoryDerivation> inputs = MemoryDerivation.find("derivedMemory = ?1", m).fetch();
            for (var d : inputs) {
                Memory input = d.inputMemoryId != null ? Memory.findById(d.inputMemoryId) : null;
                if (input != null) {
                    predecessors.add(new MemoryPred(input.id, input.createdAt));
                    continue;
                }
                Message message = d.inputMessageId != null ? Message.findById(d.inputMessageId) : null;
                if (message != null) {
                    predecessors.add(new MessagePred(message.id, message.createdAt,
                            message.content == null ? "" : message.content, day(message.createdAt)));
                }
            }
            return new Node(m.text, day(m.createdAt), sourceText, m.derived, List.copyOf(predecessors));
        }

        private static LocalDate day(Instant at) {
            return at.atZone(TimezoneResolver.appZone()).toLocalDate();
        }
    }
}
