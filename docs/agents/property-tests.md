# Property-based tests (jetCheck)

A property is an ordinary JUnit `@Test` that calls jetCheck through `PropertyInputs.check(iterations,
generator, property)` in `test/PropertyInputs.java`. Each subject has its own `<Subject>PropertyTest`
class (`SafeTokenPropertyTest`, `ApplyPatchPropertyTest`, `AbsoluteDatesPropertyTest`, …). jetCheck
keeps no static mutable state and prints nothing under `silent()`, which `check` sets, so a property
class runs on the fork's parallel lane like any other pure class, and one that needs a workspace
agent runs on the database lane like any other. jqwik, which jetCheck replaced (JF-500), forced every
property into one class because its engine kept process-global state, and it printed a line
addressed to AI agents on every run.

**Reach for a property when the invariant is easier to state than the examples are to enumerate** —
a round trip (`render → parse → apply` reproduces the input), an idempotence (`f(f(x)) == f(x)`), a
bound that must hold for every input (no chunk exceeds the API's cap), or an order that must survive
a transformation. **Reach for an example test for everything else**: a specific edge case, an exact
error string, a regression a ticket named, or anything with fixtures. Properties and examples are
complements — `FileSystemToolsPatchTest`, `TelegramOutboundPlannerTest` and `UtilsFilenamesTest`
own the named cases and the error messages; the properties cover the arithmetic between them.

- **Pin the iteration count and write its cost next to the call.** The suite's critical path is what
  everyone pays, and an unpinned count silently grows it. The current 14 cost about 1.1 s of method
  time together, spread over parallel classes.
- **Keep the count well under the number of distinct inputs.** jetCheck never repeats an input, and
  throws `CannotSatisfyCondition` when it cannot find a new one, so 40 draws from 24 photo counts
  fails. Check a domain of a few dozen values with a plain loop over all of it, as
  `TelegramOutboundPlannerPropertyTest` does.
- **Put the generated input in the assertion's message supplier.** The fork's `PlayJUnitExtension`
  reports a failure's root cause, which is the assertion jetCheck caught at the shrunk input, so that
  message is all that reaches `test-result`; `check` appends jetCheck's replay data to it. Generate
  several inputs as one record (`Generator.from(env -> new Case(env.generate(a), env.generate(b)))`),
  so the input prints as one value.
- **A failed property is not retried.** jetCheck keeps no failure database and draws new inputs on
  every run, so a rerun of a red property can pass with the bug still there. Before rerunning, copy
  the input from `test-result` into an example test in the subject's example class. To step through
  the exact failure, run the test locally with the `Replay:` line from its message,
  `PropertyChecker.customized().rechecking("<data>").forAll(generator, …)`, in place of `check`.
  jetCheck marks `rechecking` deprecated; it is a debugging aid and never belongs in a commit.
