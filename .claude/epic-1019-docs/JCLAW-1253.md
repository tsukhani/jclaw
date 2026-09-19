# JCLAW-1253 — doc fragment

Two edits for `AGENTS.md`. Both belong in the **Capabilities** section; the second also
touches the Personal Edition bullet under **Known pitfalls**.

---

## 1. Capabilities — add the fifth row and the paragraph below the table

The section opens "…holds four allowlists"; that becomes **five**. Add this row to the table:

| Capability | Holders |
| --- | --- |
| Mutate state as the agent principal | Every POST/PUT/PATCH/DELETE route in `conf/routes` declares a stance: `@ChatHidden`, a `RequestPrincipal.isAgentOriginated` guard, or `@AgentCallable` |

And this paragraph after the "filesystem authority is deliberately the narrow one" one:

> The agent-principal capability is the one rule that reads `conf/routes`. ArchUnit imports
> bytecode and cannot see the routes file, so `everyMutatingRouteDeclaresAnAgentPrincipalStance`
> parses it the way `WebhookControllerTest.webhookRoutes` does and checks the action each
> mutating route binds to. It is not frozen — all 134 mutating routes were adjudicated in
> JCLAW-1253, so the rule lands green and a new route with no stance fails immediately. The
> floor is on the route count rather than on matched files: a routes file that stopped parsing
> would otherwise adjudicate nothing and pass. Reads are out of scope, because a GET that leaks
> is a masking problem at the seam.
>
> The three stances are not interchangeable. `@ChatHidden` removes the action from
> `jclaw_api`'s discover and call surface. A `requireOperator`-style guard refuses the agent
> principal server-side, so it also holds against a leaked internal bearer token — the two are
> complementary and the strongest routes carry both. `@AgentCallable` changes nothing at
> runtime: it records that leaving the route open was a decision, and its mandatory reason
> names what bounds it instead (a scoped tool, `DangerousActionGate`, `SsrfGuard`,
> `LoadtestAuthCheck`). The rule matches the guard by the check it *reaches* —
> `RequestPrincipal.isAgentOriginated`, up to two hops through helpers on the same controller —
> not by the helper being named `requireOperator`, so renaming one is safe and a
> `requireOperator` that checks nothing does not pass.
>
> `JClawApiTool.PATH_BLOCKLIST` is deliberately **not** a fourth stance. It is a path-prefix
> deny-floor in the tool layer, and accepting it would have left 34 actions — the whole of
> auth, chat, bindings, the webhooks — with nothing at the action saying why they are closed,
> which is the invisibility the story exists to remove. Those 34 now carry `@ChatHidden` as
> well; the floor already refused them, so that half of the sweep changed no behaviour.

## 2. Known pitfalls — extend the Personal Edition bullet

After "…standing approvals (JCLAW-1062)", add:

> Since JCLAW-1253 that list is enforced rather than remembered: a mutating route with no
> stance fails `CapabilityRulesTest`, and `JClawApiToolTest.theCallableApiSurfaceIsExactlyTheseRoutes`
> pins what an agent can still invoke, so widening the surface is a visible diff in two places.

---

## What the sweep actually changed (for the epic retro, not for AGENTS.md)

134 mutating routes. 34 already had a stance; 100 did not. Of those 100, 34 sat behind the
deny-floor and 66 were genuinely agent-reachable. JCLAW-1227 closed 9 of the 66 first.

The adjudication that landed: 56 new `@ChatHidden`, 35 new `@AgentCallable`, and one new
`requireOperator()` call (`ApiSkillsController.deleteAgentSkill`, which deletes any agent's
workspace skill and revokes its shell-allowlist grants — the JCLAW-1023/1058 shape its siblings
already guard, missed by the audit).

Net behaviour change is exactly **22 routes leaving the `jclaw_api` callable surface**, and the
diff in `JClawApiToolTest`'s expected list is the reviewable record of it. The other 34
`@ChatHidden` additions were on deny-floored paths and moved nothing. Nothing an agent tool
depends on was closed: `task_manager`, `subagent_spawn`, `memory`, MCP-server and provider CRUD
all stay reachable, the last two because the Personal Edition posture says to remediate at the
seam rather than at the endpoint.

The 22 fall into four groups — erasing the operator's record (log archives, latency and
compression metrics, subagent runs, task-run stats, a notification), reaching past the calling
agent (deleting any agent with its workspace, any agent's core memories, any agent's workspace
skill, attachment bytes by uuid), operator-owned hardware and content (default printer, cloned
voice clip, hosted app directory, bulk prompt-library replace), and endpoints an agent could
never reach anyway (the two eval-capture routes behind `LoadtestAuthCheck`, `/api/apps/{slug}/invoke`
which drives a full agent turn — the recursion `/api/chat/` is floored for).
