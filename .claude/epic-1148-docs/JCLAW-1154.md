# JCLAW-1154 — AGENTS.md fragment

**Target section:** `## Development Commands` → `### Backend (Play 1.x)`, as a new subsection
immediately after the backend commands block (before `### Frontend (Nuxt 4)`). It is test guidance,
so it belongs beside `play autotest` rather than under Architecture.

---

### Property-based tests (jqwik)

Every jqwik `@Property` in the backend lives in **one class**, `test/PropertyBasedTest.java`. That
is a hard constraint, not a style preference: the play1 fork runs pure unit-test classes on a
16-way parallel lane and gives each class its own `LauncherFactory.create()`, while jqwik's engine
keeps process-global mutable state (`StoreRepository.current` is a plain static). Two
property-bearing classes executing at the same time corrupt each other. Measured on jqwik 1.10.1:
four property classes run together failed two runs in three, with `ConcurrentModificationException`,
a `StoreRepository` NPE, `CannotFindArbitraryException` and `IllegalArgumentException: List length
= -1` — all spurious, none reproducible when the classes run alone. The same properties gathered
into one class passed five consecutive runs against 13-, 61- and 101-class slices. Lifting the
constraint needs a change in the fork: `TestEngine` would have to run property-bearing classes on
the serial (`D:`) lane, or `FirePhoque` would need a third lane for them.

**Reach for a property when the invariant is easier to state than the examples are to enumerate** —
a round trip (`render → parse → apply` reproduces the input), an idempotence (`f(f(x)) == f(x)`), a
bound that must hold for every input (no chunk exceeds the API's cap), or an order that must survive
a transformation. **Reach for an example test for everything else**: a specific edge case, an exact
error string, a regression a ticket named, or anything with fixtures. Properties and examples are
complements — `UnifiedPatchParserTest`, `TelegramOutboundPlannerTest` and `UtilsFilenamesTest` still
own the named cases and the error messages; the properties cover the arithmetic between them.

Two mechanics the fork forces:

- **Pin `tries` and write the wall-time budget next to the annotation.** The suite's critical path
  is what everyone pays; an unpinned property silently grows it. The current set costs ~290 ms.
- **Put the generated inputs in the assertion's message supplier.** jqwik publishes its sample
  report through JUnit Platform reporting entries, and the fork's `TestEngine.Listener` does not
  implement `reportingEntryPublished` — it records `Throwable.getMessage()` and nothing else. A
  message supplier is therefore the only route a shrunk counterexample has into `test-result`. It
  works well: a deliberately falsified `n < 500` reported `falsified at n=500`, the exact boundary.

`play autotest` writes jqwik's failure database to `.jqwik-database` at the repo root on every run;
it is gitignored.
