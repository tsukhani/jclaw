# JClaw UAT suite

Playwright end-to-end tests, run against an **already-running** JClaw server:

```bash
./jclaw.sh e2e          # picks :3000 (dev) or :9000 (prod), loads certs/.env
```

Deliberately excluded from `./jclaw.sh test` and from Jenkins CI — it needs a
live server and a real admin credential, so it is a local UAT safety net rather
than a merge gate. See `../../playwright.config.ts` for the runner config.

## The rule that matters

**The suite runs against real operator data.** Everything here is read-only
except the fixtures a spec creates and removes itself, and those carry the
`e2e-uat-` prefix. Two specs assert at the end that no prefixed row survived, so
a leak fails the run rather than quietly accumulating.

Never added to this suite:

| Not done | Why |
| --- | --- |
| Clicking **Delete all** on memories / conversations | Wipes the corpus or the audit trail |
| Creating a **task** | A task is a live cron entry; it would fire against a real agent and bill model calls |
| Pausing/resuming a real schedule | Silently changes what runs tonight |
| `POST /api/system/{restart,upgrade}` | Stops the JVM under test |
| Binding **Test** buttons, Tailscale Funnel toggle | Sends real messages; changes public reachability |
| Clicking **Generate** in the new-prompt dialog | Bills a model call and returns nondeterministic text |
| `GET /api/printers` | Live mDNS browse, seconds per call |

Chat turns are mocked at the route level for the same reason — `chat.uat.spec.ts`
covers the client half (SSE parsing, rendering, error recovery), which is what a
mock exercises faithfully. The backend half belongs to `ApiChatControllerTest`
and the eval suites.

## Coverage

| Area | Spec | Notes |
| --- | --- | --- |
| UAT-1 Authentication | `auth.uat.spec.ts` | Runs unauthenticated; session gate, bad password, sign-out |
| UAT-2 Read-side API contract | `api-contract.uat.spec.ts` | 38 endpoints + 404 shape + SPA fallback |
| UAT-3 Navigation | `navigation.uat.spec.ts` | Sidebar routing, deep links, command palette |
| UAT-4 Agent lifecycle | `agents.uat.spec.ts` | Create/read/update/delete + 409 conflict paths |
| UAT-5 Prompts library | `prompts.uat.spec.ts` | Create via API, UI for search/filter/edit/delete |
| UAT-6 Tasks & reminders | `tasks.uat.spec.ts` | Read-only over the live schedule |
| UAT-7 Memories | `memories.uat.spec.ts` | Serial; importance edit restores its own write |
| UAT-8 Conversations | `conversations.uat.spec.ts` | List, filter, transcript, subagent runs |
| UAT-9 Settings | `settings.uat.spec.ts` | All 26 panels mount |
| UAT-10 Capability surface | `skills-tools.uat.spec.ts` | Skills, tools, per-agent grant round-trip |
| UAT-11 Channels | `channels.uat.spec.ts` | Bindings; webhooks stay un-gated |
| UAT-12 Dashboard | `dashboard.uat.spec.ts` | Four panels, metrics endpoints, logs |
| UAT-13/14 Apps & guide | `apps-guide.uat.spec.ts` | Hosted apps, cache headers, guide chapters |
| UAT-15 Chat | `chat.uat.spec.ts` | Mocked stream: tokens, tool calls, error recovery |
| UAT-16 Accessibility | `accessibility.uat.spec.ts` | axe-core, serious+critical only |
| UAT-17 Circuit breakers | `breakers.uat.spec.ts` | Registry rows mirrored on the dashboard, grouped by subsystem; read-only |
| Page smoke | `pages.smoke.spec.ts` | Pre-existing; ten top-level pages |
| Prompt caching | `prompt-caching.uat.spec.ts` | Pre-existing; cached-token badge |

## Gotchas encoded in `helpers.ts`

- **External hosts are stubbed.** `GithubStarsButton` fetches `api.github.com`
  unauthenticated (60 req/hour/IP); a parallel run exhausts that immediately and
  every page then logs a 403, which reads as a broken app. Stubbed, not aborted
  — an abort logs `net::ERR_FAILED` instead.
- **`FilterBar` is addressed by `aria-label`, not placeholder.** The placeholder
  becomes `"Add filter..."` once any chip is committed, so a placeholder locator
  silently stops matching after the first filter.
- **Filters commit on Enter**, not on input. `fill()` alone leaves every row on
  screen and the assertion passes by accident.
- **Facet tests assert the chip, not the row count** — a facet matching zero
  rows is legitimate, so a count assertion passes or fails with the data.

## Known-failing accessibility baseline

`accessibility.uat.spec.ts` carries a `KNOWN_VIOLATIONS` map so the suite
catches *new* violations without being permanently red. It currently holds one
real defect:

- **`/agents` — `nested-interactive` (serious, WCAG 4.1.2).** Each agent row is
  a `<div role="button" tabindex="0">` containing real `<button>`s (enable
  toggle, thinking pill, delete). Screen readers announce the row as a single
  control and the inner buttons can become unreachable. Fix by demoting the row
  to a non-interactive container with an explicit open affordance.

The test also fails if a baselined rule *stops* firing, so the entry cannot rot
after the markup is fixed — delete it then.
