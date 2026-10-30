# Capabilities

Java 25 cannot express a capability in a signature, and JEP 486 removed the
SecurityManager, so nothing confines one at runtime either — which class may spawn a
process or reach the database is invisible to both javac and the JVM. `ArchUnit`
stands in for that at test time: `test/CapabilityRulesTest.java` holds six
allowlists, one per authority the codebase actually exercises, and a class that picks
up an authority it was never granted fails `play autotest` with a `because` clause
naming the capability.

**Test-only seams** are the mirror image of a capability, and `CapabilityRulesTest`
holds them too: fourteen methods `app/` may never call, enforced by
`noAppClassCallsATestOnlySeam`, with `everyScopedValueSeamInAppIsOnTheList` failing the build on a
`public static *ForTest` method that binds a `ScopedValue` and is not on it. Each binds a `ScopedValue` for the dynamic extent of
one call — `SsrfGuard.permitOriginForTest` (any http(s) IP-literal origin, so a
loopback fixture passes the SSRF check), `SsrfGuard.callWithHostsForTest` (hostnames
answered from IP literals instead of DNS, so the public-host paths run without a resolver),
`JevPage.callWithCallLimitForTest` (any
positive frozen-page bound, in place of the 30 s default), `McpElicitations.callWithWaitForTest`
(any positive wait before an unanswered MCP form is cancelled, in place of 5 minutes), `HttpFactories.runWith` /
`callWith` (a canned transport), `AppClock.runWith` / `callWith` (a fixed clock),
`PlaywrightBrowserTool.callWithFailingScreenForTest` (every `BrowserScreenProxy` it opens fails to
start, the browser tool's and a stealth render's, JCLAW-1288), `EventLogger.captureForTest`
(the events the thread records, which concurrent classes' `clear()` and table wipes cannot reach)
`EventLogger.captureMatchingForTest` (events any thread records that the caller's predicate
matches, for a background-thread logger; listener-based, not a `ScopedValue`) and
`OperatorAlerts.captureMcpAlertsForTest`, `capturePersonaAlertsForTest` and `captureTaskAlertsForTest`
(the MCP protocol-switch, withheld persona-file and failed-task alerts any thread decides, with or
without a destination set; listener-based too).
A binding reaches everything that runs inside it, which is why production never takes
one: every URL screened, every request sent, every time read. The rule also asserts
each named seam still exists, so a rename fails it rather than leaving it guarding
nothing. The list is explicit because these names carry no pattern to match —
`runWith` and `callWith` are ordinary names — and because the `*ForTest` setters are a
different problem: they flip process-global state, and several are called by other
test hooks in `app/`, so banning them needs a frozen store rather than this list
(JCLAW-1282).

| Capability | Holders |
| --- | --- |
| Spawn an OS process | 19 files — the sidecar supervisors, media transcoders, harness runners, `tools.ShellExecTool` and `services.BackgroundProcesses` (`lsof`); enumerated in `archunit_store/shell-process-spawners` |
| Resolve a model-controlled path | `tools.FsPaths` → `utils.WorkspacePathGuard`; the sites under `tools..` predating that seam are listed in `archunit_store/filesystem-tool-paths` |
| Open an outbound connection | `utils.HttpFactories`, plus `utils.SsrfGuard` and `channels.TelegramBotApiHttpClients` for their own tuned clients; raw sockets only in `services.printing..`, `services.LocalSidecarDaemon`, `tools.BrowserScreenProxy` and `utils.SsrfGuard`'s provider-screen socket factory |
| Reach the database | Everything except the subsystems `jobs.ShutdownJob` stops — teardown that needs a connection has no useful recovery when it cannot get one (JCLAW-1143) |
| Act as the agent principal | Every routed `/api` action declares `@AgentAccess`: `OPEN`, `OWN_ONLY` or `OPERATOR_ONLY`, with no annotation meaning `OPERATOR_ONLY` (JCLAW-1270) |
| Read raw memory-graph records | `memory.graph..` and `services.grapheval..` only: a raw record set reads former, denied, retracted and guest claims as current facts, which `memory.graph.GraphView` combines into one answer (JCLAW-1364) |

Shell and filesystem carry pre-existing holders, so they run as `FreezingArchRule`s
and the checked-in store under `archunit_store/` *is* the allowlist: a listed site
passes and a new one fails. Both store switches are pinned off in
`conf/archunit.properties`, so a listed site that stops matching fails too, until its
line is deleted by hand — deleting an entry is how the list shrinks; editing a rule is
not. The predicates match accesses, so a method reference such as `ProcessBuilder::start`
or `Path::of` counts the same as a call. Each frozen rule is paired with a floor on its
live match count, which guards the deliberate regeneration run: with the switches on, a
predicate that had silently stopped matching would prune the store to empty and pass
forever.

The filesystem authority is deliberately the narrow one: it covers paths resolved from
tool arguments, since those are the only ones an operator does not control.
Application-internal file access — config, caches, sidecar working directories — is not
this capability and is not gated.

The agent-principal capability is the one rule that reads `conf/routes`. ArchUnit imports
bytecode and cannot see the routes file, so `everyRoutedApiActionDeclaresAnAgentAccessLevel`
parses it the way `WebhookControllerTest.webhookRoutes` does and checks the action each route
binds to. It is not frozen — every route is adjudicated, so the rule lands green and a new
route with no level fails immediately. The floor is on the route count rather than on matched
files, because a routes file that stopped parsing would otherwise adjudicate nothing and pass.
Reads are in scope: skipping them on the grounds that a leaky GET is a masking problem is what
left 91 of them answering to no rule at all until JCLAW-1270.

`@AgentAccess` is one declaration read in two places, which is the point of it. `AgentAccessGate`
enforces it at the request layer from `AuthCheck`, `LoadtestAuthCheck` and `ApiDatabaseController`'s loopback-secret branch — Play assigns
`Http.Request.invokedMethod` before it runs any `@Before`, so the interceptor reads the annotation
off the method about to run — and `JClawApiTool` derives its discover and call surface from the
same annotation. It replaced four mechanisms (`@ChatHidden`, `@AgentCallable`, `PATH_BLOCKLIST`
and the hand-written `requireOperator()` helpers) of which only the last refused anyone; the first
two were read inside one tool, so they hid a route without gating it and an agent holding the
internal bearer walked past both. **Do not add a guard to the body of an `OPERATOR_ONLY` action** —
the interceptor has already refused, and a second check is the drift the collapse removed.

The levels are not interchangeable. `OPEN` means any agent may call it, and on a mutating route
its `reason` is mandatory and must name what bounds it instead (a scoped tool,
`DangerousActionGate`, `SsrfGuard`, `LoadtestAuthCheck`) — an empty reason there is worse than no
annotation, because it reads as adjudicated. `OWN_ONLY` means an agent reaches only rows its own
agent owns, and the rule matches the enforcement by the check it *reaches* — through
any chain of helpers on the same controller — not by a helper's name, so renaming one is safe and a
`requireOwner` that checks nothing does not pass. It accepts either
`RequestPrincipal.mayReachAgentScopedRow` or `callingAgent`, because an `OWN_ONLY` route has two
honest shapes: a row route refuses one it does not own, and a list route pins its query to the
caller — a list that 403s on another agent's rows would have had to read them first.
`OPERATOR_ONLY` refuses the agent principal outright, whatever tool it arrived through, so it
holds against a leaked internal bearer as well as against `jclaw_api`.

One consequence worth knowing before you reach for the old shape: there is no level meaning
"hidden from the tool but not refused". That state is what let JCLAW-1266's routes look protected
while refusing nobody, and collapsing it closed two reads that had been pinned open on the
reasoning that they carried nothing secret (`GET /api/tailscale`, `GET /api/bindings`). If a route
should be visible to an agent, it is `OPEN`; if not, it is refused.
