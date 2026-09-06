# JCLAW-1152 — AGENTS.md fragment

**Target section:** `## Architecture` → new subsection `### Capabilities`.

**Placement:** insert as a new `###` subsection immediately after
`### Outbound HTTP — OkHttp 5` and before `### Frontend`. It reads as the general
case of the rule that section already states, so it belongs after it rather than
before.

## Prose to merge

### Capabilities

Java 25 cannot express a capability in a signature, and JEP 486 removed the
SecurityManager, so nothing confines one at runtime either — which class may spawn a
process or reach the database is invisible to both javac and the JVM. `ArchUnit`
stands in for that at test time: `test/CapabilityRulesTest.java` holds four
allowlists, one per authority the codebase actually exercises, and a class that picks
up an authority it was never granted fails `play autotest` with a `because` clause
naming the capability.

| Capability | Holders |
| --- | --- |
| Spawn an OS process | 17 files — the sidecar supervisors, media transcoders, harness runners and `tools.ShellExecTool`; enumerated in `archunit_store/shell-process-spawners` |
| Resolve a model-controlled path | `tools.FsPaths` → `utils.WorkspacePathGuard`; the sites under `tools..` predating that seam are listed in `archunit_store/filesystem-tool-paths` |
| Open an outbound connection | `utils.HttpFactories`, plus `utils.SsrfGuard` and `channels.TelegramBotApiHttpClients` for their own tuned clients; raw sockets only in `services.printing..` and `services.LocalSidecarDaemon` |
| Reach the database | Everything except the subsystems `jobs.ShutdownJob` stops — teardown that needs a connection has no useful recovery when it cannot get one (JCLAW-1143) |

Shell and filesystem carry pre-existing holders, so they run as `FreezingArchRule`s
and the checked-in store under `archunit_store/` *is* the allowlist: a listed site
passes, a new one fails, and fixing one prunes it from the store on the next run.
Deleting an entry is how the list shrinks; editing a rule is not. Each frozen rule is
paired with a floor on its live match count, because a predicate that silently stopped
matching would otherwise let the store prune itself to empty and pass forever.

The filesystem authority is deliberately the narrow one: it covers paths resolved from
tool arguments, since those are the only ones an operator does not control.
Application-internal file access — config, caches, sidecar working directories — is not
this capability and is not gated.

## Notes for the integrator

- JCLAW-1151's fragment offers an optional sentence saying "`ArchitectureTest`
  enforces both halves" of the network rule. Those two rules, plus 1151's raw-socket
  rule, moved into `CapabilityRulesTest` in this story. If that sentence is merged,
  change the class name to `CapabilityRulesTest`; otherwise drop it, since the table
  above already covers it.
- The holder counts (17 files) are measured, not estimated — they come from the
  frozen store, so re-read `archunit_store/shell-process-spawners` if this merges
  long after the story.
- Fragment lives under `.claude/`, which `.gitignore` excludes; it was committed with
  `git add -f`. Delete it once merged.
- No AGENTS.md file was edited by this story.
