---
description: Measure Core Web Vitals (LCP, CLS, FCP, TTFB, INP) for every JClaw page against the running prod instance, report a verdict per page, and — only where a metric is outside "good" — propose remediation options for the user to choose from. All-good pages are reported as good and get no remediation.
argument-hint: "[pages to scope, optional — e.g. chat tasks settings]"
---

# Frontend rendering-performance audit

Measure **Core Web Vitals** across the JClaw Nuxt frontend, page by page, against the **running production instance**. Produce a verdict table, then propose remediation **only for metrics that actually fail**. Use `/usr/bin/git` for every git invocation (project convention).

**Scope:** `$ARGUMENTS` — if empty, audit every page.

## The rule that governs this whole skill

**A page whose metrics are all inside "good" is a finding of "good" — not a starting point for optimization.** Say so plainly, move on, and propose nothing for it. Do not invent work on a healthy page: no speculative micro-optimizations, no "could be faster" rows, no remediation table with nothing in it. If every page passes, the deliverable is one short paragraph saying exactly that, plus the numbers.

Remediation is proposed **only** for a metric measured outside the Good band in the table below.

## Thresholds (Google Core Web Vitals)

| Metric | Good | Needs improvement | Poor |
| --- | --- | --- | --- |
| **LCP** Largest Contentful Paint | ≤ 2500 ms | ≤ 4000 ms | > 4000 ms |
| **CLS** Cumulative Layout Shift | ≤ 0.1 | ≤ 0.25 | > 0.25 |
| **INP** Interaction to Next Paint | ≤ 200 ms | ≤ 500 ms | > 500 ms |
| **FCP** First Contentful Paint | ≤ 1800 ms | ≤ 3000 ms | > 3000 ms |
| **TTFB** Time to First Byte | ≤ 800 ms | ≤ 1800 ms | > 1800 ms |

These bands are calibrated for real-world networks and mid-range devices. Measured on localhost against a warm JVM, LCP and TTFB will be *far* inside Good on every page — that is expected and is not a reason to hunt for problems.

---

## Phase 0 — Setup, and proving the harness works

1. **Measure the prod instance on :9000, not a dev server.** Dev-mode Nuxt serves unminified modules and compiles routes on demand; its numbers describe the dev server, not what an operator experiences. `./jclaw.sh status` confirms prod is up. If it isn't, ask the user before starting it.

2. **Confirm the served build matches the source.** Prod serves a *prebuilt* SPA from `public/spa/`, so a source change that hasn't been rebuilt is invisible to every measurement you take:
   ```bash
   stat -f "spa:    %Sm" public/spa/index.html
   stat -f "newest: %Sm" $(find frontend/pages frontend/components frontend/composables frontend/layouts -type f -newer public/spa/index.html | head -1)
   ```
   If any frontend source is newer than `public/spa/index.html`, you are measuring stale code. Say so and stop — rebuilding means restarting :9000, which needs the user's approval (Phase 3).

3. **Authenticate by asking the user, not by handling credentials.** Every page except `/login` and `/setup-password` is session-gated. Open `http://localhost:9000/` in Chrome DevTools MCP; if it lands on `/login`, ask the user to sign in in that window and confirm when done. Never read the admin password out of H2 or ask them to paste it.

4. **Warm the JVM before the first measurement.** A freshly restarted backend has no JIT warmup, and a cold endpoint measured 1642 ms against 619 ms warm — a 2.6× false-bad that looks exactly like a regression:
   ```bash
   for i in 1 2 3 4 5; do curl -s -o /dev/null http://localhost:9000/api/status; done
   ```
   Then load the page once and discard that load before recording.

5. **Validate the measuring harness against a case whose answer you already know, before trusting any number it produces.** This is not optional ceremony — every harness in this area has a silent-zero failure mode:
   - A layout-shift probe that appends a tall element *below the fold* reports **0**, because CLS only counts elements in the viewport. Insert above visible content and confirm you get a non-zero score before believing a zero elsewhere.
   - A green from a quality gate that prints nothing (`pnpm typecheck`) proves nothing until you have seen it fail. Inject a deliberate error once, watch it report, revert.
   - A trace whose "exit code 0" came from the last command in a pipe (`… | tail`) is not the tool's exit code.

---

## Phase 1 — Measure

For each page in scope (see the route list at the bottom):

1. **Navigate first, then trace.** `performance_start_trace` with `reload: true, autoStop: true` records a full navigation.
2. **Take at least 3 samples per page and report the median.** Single traces vary widely — the same unchanged page measured 112, 154, 155, 160, 162 and 168 ms LCP across one session. One sample is an anecdote.
3. **Record LCP (with its TTFB / render-delay split), CLS, FCP, and TTFB.** The LCP breakdown is what separates "the server was slow" from "the page rendered nothing while it waited" — a 3 ms TTFB with a 763 ms render delay is a client-side blocking problem, not a backend one.
4. **INP needs an interaction.** Skip it unless the page has an obvious primary control; if you measure it, say which interaction you drove.
5. **Capture the network waterfall** (`list_network_requests`, or `performance.getEntriesByType('resource')`) for any page whose LCP is not Good. Request *start* times matter as much as durations: requests that begin only after an earlier one finished reveal a serialization the durations alone hide.

**Where a metric is outside Good, find the actual cause before proposing anything:**

- **Sample geometry per animation frame** to see what is really moving or resizing. This is the single most useful technique here:
  ```js
  window.__s = [];
  const grab = () => { const t = performance.now();
    window.__s.push({ t: Math.round(t), h: [...document.querySelectorAll(SELECTOR)]
      .map(e => Math.round(e.getBoundingClientRect().height)) });
    if (t < 2500) requestAnimationFrame(grab); };
  requestAnimationFrame(grab);
  ```
  Pass it as `initScript` to `navigate_page`, then collapse to the frames where the values changed. It finds causes that the trace only gestures at.

- **Read the CLS culprit list as a list of *impacted* elements — victims as well as causes.** An element can appear there simply because something above it moved. The tell that you fixed a victim rather than a cause is a near-zero change in the score: one fix moved CLS 0.0424 → 0.0413, which meant the element fixed was being pushed, not pushing. Sample the *ancestors and siblings* of the impacted element to find what actually moved.

- **`take_screenshot` with `fullPage: true` expands scroll containers**, so a capped, internally-scrolling panel photographs as if it were full height. Use viewport screenshots to judge layout, and `getBoundingClientRect()` for any number you intend to report.

### Pathologies this codebase has actually produced

Check for these directly — each was a real, measured defect here:

- **A top-level `await` in `<script setup>`** makes the page component async, so Nuxt suspends it and renders *nothing* — not even the skeleton — until every awaited promise settles. Two sequential awaits serialize into two waves. Grep for `await useFetch`, `await useAsyncData`, `await Promise.all` in `frontend/pages/**`. The fix is `useLazyFetch` / `useLazyAsyncData`, which several `frontend/components/settings/*` panels already document.
- **An uncached expensive backend endpoint on the critical path.** `/api/workspace/stats` walked 66k files per request (~600 ms) with no cache. Look for a slow API call whose response the page blocks on.
- **A ref that defaults to its error state.** `apiOnline = ref(false)` made the layout paint a red "API is unreachable" banner on *every* load until `/api/status` answered — a false error, and a 41 px shift when it vanished. Look for `ref(false)` / `ref(null)` driving a `v-if` on an error or empty state, and gate it on a "have we actually checked yet" flag.
- **`pending` vs `status` on a lazy fetch.** A `useLazyFetch` sits at status `'idle'` with `pending === false` before it starts, so anything keyed off `!pending` fires immediately. Use `status`.
- **Content that pops in at its natural size.** Panels rendering header-only then expanding (137 px → 1349 px here). `frontend/composables/useStableHeight.ts` exists for exactly this: it reserves the viewer's last-known height and releases it when data lands.
- **A control that appears once data arrives**, growing its container — a data-gated view toggle grew two panel headers 52 → 55 px.
- **A `<select>` that widens when its options populate**, re-centering a `justify-center` flex cluster.

---

## Phase 2 — The report, and stop

Lead with the verdict table — every page in scope, whether or not it passed:

| Page | LCP (median) | CLS | FCP | TTFB | Verdict |

Verdict is **Good** / **Needs improvement** / **Poor**, set by the worst individual metric.

Then, **only if at least one metric is outside Good**, a remediation table for those metrics only:

| # | Page | Metric | Measured | Target | Cause (as evidenced) | Remediation options |

- **Cause** must be something you observed — a waterfall entry, a per-frame geometry change, a culprit-list element you traced to its mover. Not a guess. If you could not establish the cause, say so and propose no fix for it.
- **Remediation options**: give the user a genuine choice where one exists, with the trade-off stated, rather than a single verdict. Layout-shift fixes in particular have distinct end states — reserve the space (which may mean internal scrolling), delay the render, or remember the previous size — and they look different on screen. Say which you recommend and why.

Close with: pages covered, pages skipped and why, and **what you could not measure** (INP where there was no sensible interaction, dynamic routes with no id, anything the app's state prevented).

**If every page is Good, that closing paragraph is the whole deliverable.** Say the numbers, say they pass, propose nothing.

One factual observation is allowed even when everything passes, and only as an observation: if a page's LCP exceeds roughly **3× the median of its peers** while still inside Good, note the outlier and its number in a single sentence. This is worth knowing — the Dashboard's 766 ms LCP was comfortably "good" and still five times its siblings, and it was the user-visible complaint that started this work. **Do not attach a proposed fix to it, and do not enter it in the remediation table.** If the user wants it pursued, they will say so.

Then **stop and ask which rows to act on.** Make no edits until they choose.

---

## Phase 3 — Apply approved fixes

Only the rows the user named.

1. **Work in the primary tree, not a worktree.** Unlike `/wcag-audit`, this skill's verification requires prod-mode measurement, and there is exactly one prod instance. A worktree's dev server cannot confirm a production number. Commits stay local; nothing is pushed.
2. Apply the approved fixes.
3. **Gate before rebuilding:**
   ```bash
   cd frontend && pnpm lint && pnpm stylelint && pnpm typecheck && pnpm test
   ```
4. **Ask before rebuilding and restarting.** `./jclaw.sh restart` rebuilds the SPA and precompiles the backend, and the instance may be serving live work. A prior approval covers one restart, not all of them.
5. **Re-measure exactly as in Phase 1** — same page, same sample count, warm JVM, and re-check that `public/spa/index.html` is now newer than the sources you changed. Report before/after per row.
6. **Verify you did not break what the change touched.** A fix that alters an error path is the dangerous case: gating the offline banner meant confirming it still appears when the backend is genuinely down (stop the backend, watch it return, restart). A regression in an error path is silent by definition.
7. **Commit locally**, one commit per coherent fix. The GitHub mirror is public, so the body is a published artifact: state the measured before/after, the cause, and what was deliberately left alone. Never push or merge — that is the user's call via `/deploy`.

---

## Hard rules

- **Good is a result, not a prompt to optimize.** No remediation for a metric inside its Good band. No empty remediation tables.
- **Measure prod on :9000.** Dev-server numbers are not production numbers, and a stale `public/spa` means you are measuring code that is not in the tree.
- **Warm the JVM first**, and discard the first load after any restart.
- **Three samples minimum per page, report the median.** One trace is noise.
- **Validate the harness against a known-answer case before trusting a zero.**
- **Establish the cause before proposing a fix.** A culprit-list entry may be a victim; a near-zero improvement after a "fix" means you fixed the wrong element.
- **Never restart or stop :9000 without explicit approval** — it may be serving live work, and approval for one restart is not approval for the next.
- **Read-only in the UI.** The prod instance is backed by the live database; never click a destructive control while auditing.
- **Do not handle the user's password.** Ask them to sign in.
- **Report what you measured and what you inferred, separately.** Every number in the report and in any commit body is a reading, not an estimate.

---

## Routes

Authenticated pages (the audit set):

`/` (Dashboard) · `/chat` · `/prompts` · `/channels` · `/channels/slack` · `/channels/telegram` · `/channels/whatsapp` · `/conversations` · `/agents` · `/subagents` · `/apps` · `/tasks` · `/reminders` · `/skills` · `/tools` · `/mcp-servers` · `/settings` · `/memories` · `/logs` · `/guide`

Dynamic routes (`/conversations/[id]`, `/agents/[[name]]`, `/skills/[[name]]`) need a real id — take one from the corresponding list page and say which you used, since the measurement depends on that record's size.

`/login` and `/setup-password` redirect away once authenticated. Measure them only if the user asks, in a separate signed-out browser context.
