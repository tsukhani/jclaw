package services.evals;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One assertion about an agent response inside an {@link EvalCase} (JCLAW-875).
 *
 * <p>Every kind here is decidable from the response alone — no judge model. That
 * is deliberate: JCLAW-833 gates this epic on LLM-call count, so a ruler that
 * spent a call per assertion would tax the very thing it measures, and would make
 * eval results depend on the model being evaluated. Model-judged checks (semantic
 * equivalence, free-form rubric grading) arrive with the critic in JCLAW-836.
 *
 * <p>Which field carries the payload depends on {@link Kind}; {@link
 * EvalDatasetLoader} is the only place that maps wire JSON onto them, and it
 * rejects a check whose payload does not fit its kind.
 */
public record EvalCheck(Kind kind, List<String> args, @Nullable JsonObject schema, int limit) {

    /** Wire {@code kind} values, lowercased ({@code CONTAINS_ALL} → {@code contains_all}). */
    public enum Kind {
        /** Every arg appears in the response text (case-insensitive). */
        CONTAINS_ALL,
        /** No arg appears in the response text (case-insensitive). */
        NOT_CONTAINS_ANY,
        /** The single arg, as a regex, finds a match in the response text. */
        MATCHES,
        /** The response text parses as JSON and satisfies {@link #schema()}. */
        JSON_SCHEMA,
        /**
         * The tools the agent actually called, compared as a MULTISET, equal these
         * exactly — no extra tool, and no repeat beyond the number listed. Empty
         * args asserts the agent called no tool at all.
         *
         * <p>An allowlist. It replaced a {@code tool_called} / {@code tool_not_called}
         * pair, which was a denylist and therefore only caught rogue behavior
         * someone predicted: {@code arithmetic-needs-no-tool} says in its rubric that
         * "any tool call here is pure overhead" but could only forbid the two tools
         * it happened to name, so a stray {@code task_manager} call passed it. This
         * kind is how a case says "only what was necessary" and means it (JCLAW-883).
         *
         * <p>Order is not compared. Two tools the agent could equally have called in
         * either order are not a behavior difference worth failing a suite over.
         */
        TOOLS_CALLED_EXACTLY,
        /**
         * Every tool the agent called appears in the args, but none of them is
         * required — the calls are a sub-multiset of the allowance. Extras still
         * fail, and so does a repeat beyond the listed count.
         *
         * <p>This is how a case spells "or". The clock is the motivating example:
         * {@code CurrentTimeInjector} stamps the current time onto the last user
         * message, so an agent that answers "what time is it?" with no tool call is
         * behaving correctly, and so is one that calls {@code datetime} once —
         * {@code tools_called_within: [datetime]} accepts both while still rejecting
         * a web search or a second clock call. Use {@link #TOOLS_CALLED_EXACTLY}
         * when the tool really is mandatory, and {@code exactly: []} to demand no
         * tool at all; an empty allowance here would just be a confusing spelling of
         * that, so the loader rejects it.
         */
        TOOLS_CALLED_WITHIN,
        /**
         * A dispatched call to the named tool carried every key in {@link #schema()}
         * with an equal value. {@code args} holds the one tool name; the schema is
         * the expected argument subset, not a JSON Schema.
         *
         * <p>The tools-called kinds see names only, so they cannot tell one use of a
         * tool from another. {@code datetime} answers "what time is it" with
         * {@code action=now} and "how many days between two dates" with
         * {@code action=calculate}; against {@code tools_called_exactly: [datetime]}
         * both pass, and a case about date arithmetic would score a clock reading as
         * correct. Subset rather than equality so a case pins the argument that
         * carries its meaning without also pinning the ones that do not — a timezone
         * the model is free to default is not the point of the case (JCLAW-883).
         */
        TOOL_ARGS_INCLUDE,
        /**
         * A dispatched call to the named tool returned all the given substrings.
         * {@code args} is the tool name followed by one or more substrings.
         *
         * <p>Arguments say what the agent ASKED for; only the result says what
         * happened. Without this a case passes on a turn that accomplished nothing:
         * a live sweep scored {@code named-url-uses-fetch-not-search} as a pass while
         * that turn's {@code web_fetch} returned "Error fetching URL: HTTP 404". The
         * agent picked the right tool, passed the right arguments, and the tool
         * failed outright, with every check green.
         *
         * <p>Matching is case-insensitive, like the other substring kinds. It asserts
         * what the tool SAID it did — a tool reporting success while half-failing
         * still passes; catching that needs assertions over resulting state, which is
         * deliberately out of scope (JCLAW-891).
         */
        TOOL_RESULT_INCLUDES,
        /** The turn used at most {@link #limit()} model calls (JCLAW-833's NFR). */
        MAX_LLM_CALLS;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> fromWire(String wire) {
            for (var k : values()) {
                if (k.wire().equals(wire)) return Optional.of(k);
            }
            return Optional.empty();
        }
    }

    public EvalCheck {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** A check whose payload is string arguments — the contains/matches/tool kinds. */
    public static EvalCheck of(Kind kind, List<String> args) {
        return new EvalCheck(kind, args, null, 0);
    }

    /** A {@link Kind#JSON_SCHEMA} check over the response's JSON body. */
    public static EvalCheck schema(JsonObject schema) {
        return new EvalCheck(Kind.JSON_SCHEMA, List.of(), schema, 0);
    }

    /** A {@link Kind#MAX_LLM_CALLS} budget check. */
    public static EvalCheck maxLlmCalls(int limit) {
        return new EvalCheck(Kind.MAX_LLM_CALLS, List.of(), null, limit);
    }

    /** The single argument of a one-arg kind (today only {@code matches}). */
    public String arg() {
        return args.getFirst();
    }
}
