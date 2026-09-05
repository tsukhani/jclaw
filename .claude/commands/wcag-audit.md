---
description: Audit the frontend for WCAG 2.2 AA compliance (contrast across light/dark, typography, layout, color scheme) in an isolated worktree, produce an approval-ready remediation list, and — only after approval — apply and commit the fixes there.
argument-hint: "[pages or areas to scope, optional — e.g. chat settings channels]"
---

# WCAG / UI-UX accessibility audit

Audit the JClaw Nuxt frontend (`frontend/`) for **WCAG 2.2 AA**, with particular emphasis on **contrast (light AND dark themes), typography (font sizing), layout, and color schemes**. Produce a prioritized **remediation list for the user to approve**, then apply the approved rows in an **isolated git worktree** and commit them there. Nothing is pushed. Use `/usr/bin/git` for every git invocation (project convention).

**The audit itself is read-only, and it stays read-only until the user names the rows they want.** Inspect, present the list, stop. The worktree exists so that the fixes, when approved, never touch the primary tree — whose dev server and prod instance keep running.

**Scope:** `$ARGUMENTS` — if empty, audit the whole UI.

## Orient first: how this frontend themes
- Theme tokens are shadcn-style CSS custom properties (HSL) in `frontend/assets/css/tailwind.css`: **light under `:root`, dark under `.dark`**, exposed as Tailwind v4 utilities via `@theme inline`. Agent/driver accent colors live in `frontend/assets/css/driver-theme.css`.
- Dark mode is class-based (`.dark` on `<html>`), but **do not switch themes by toggling that class** — see Phase 2.
- Pages: `frontend/pages/**` (including `channels/`, `conversations/`). Components: `frontend/components/**`.
- Every finding must be evaluated in **both themes** — a pairing that passes in light can fail in dark and vice-versa.

---

## Phase 0 — Setup: isolated worktree

1. Create a dedicated worktree off `main` so nothing touches the primary tree:
   ```bash
   /usr/bin/git worktree add ../jclaw-wcag -b wcag-fixes
   ```
   Do all work in `../jclaw-wcag`. The `post-checkout` hook runs `init-worktree`, which seeds `certs/.env`; the backend port it pins is irrelevant here (this skill runs no `play autotest`) but leave it alone.

2. **Install and prepare the frontend in the worktree.** `node_modules/` and `.nuxt/` are gitignored, so a fresh worktree has neither, and `pnpm test` / `pnpm typecheck` will **false-RED** on a missing `.nuxt/tsconfig.json` rather than on any real defect:
   ```bash
   cd ../jclaw-wcag/frontend && pnpm install && pnpm exec nuxi prepare
   ```
   Expect this to take a few minutes on first run. If you skip it, every gate in Phase 4 lies.

3. **Establish a green baseline** before auditing, so a later red is attributable to the fixes and not to something already broken:
   ```bash
   cd ../jclaw-wcag/frontend && pnpm lint && pnpm stylelint && pnpm typecheck && pnpm test
   ```

4. If the audit ends with **no approved fixes**, remove the worktree rather than leaving an empty branch to review:
   ```bash
   /usr/bin/git worktree remove ../jclaw-wcag
   ```

---

## Phase 1 — Static pass (always)

- Extract every light/dark **color-variable pair** from `tailwind.css` (`:root` vs `.dark`): foreground/background, muted/muted-foreground, primary, secondary, destructive, border, input, ring, accent, popover, card, etc. For each pairing the UI actually renders text on, compute the WCAG contrast ratio **in both themes**.
- Build a **typography inventory**: the `@theme` font-size / line-height tokens plus every `text-*`, `text-[...]`, and inline `font-size` across pages/components. Flag body copy below ~16px, `text-xs`/≤12px on meaningful content, weak line-height, and fixed px that won't honor user zoom.
  - **Watch for compound `em` scaling.** A `code { font-size: 0.875em }` rule inside a `pre { font-size: 0.8rem }` multiplies out — that pairing rendered 10.24px in `GuideRenderer.vue`, and neither declaration looks wrong on its own. Compute the product, don't read the declarations.
- **Layout**: fixed heights/widths on text containers, `overflow-hidden` that can clip text, tap/click targets < 24×24 CSS px (WCAG 2.5.8; 44px ideal), and reflow risks at a 320px viewport. Sum the fixed column widths of any nowrap flex row — that sum, not the declarations, is what overflows.
- **Color scheme**: information conveyed by color alone (1.4.1); hardcoded hex/hsl/`rgb()` outside the token system (theme-blind values that won't adapt to dark); non-text contrast of borders, icons, and focus rings (≥ 3:1).
  - **Alpha suffixes are the recurring defect here.** A correct per-theme pair (`text-amber-700 dark:text-amber-400`) given an alpha (`/60`–`/80`) blends back toward the surface and fails in *both* themes while looking deliberate. Enumerate `text-<color>/<alpha>` across the tree and compute each at its alpha; the token itself passing proves nothing.
- Fold in the existing linters: `cd frontend && pnpm lint` (eslint-plugin-vuejs-accessibility) and `pnpm stylelint`. Treat their a11y output as input, not gospel — both have been clean through audits that found real defects.

---

## Phase 2 — Live pass

Run this from the **worktree**, against its own dev server, so the primary tree's dev server (if any) and the prod instance on :9000 are undisturbed.

1. **Start the worktree's dev server on a port of its own.** Pick the first free port at or above 3100 — never 3000 (the primary tree's dev server) or 9000/9443 (prod):
   ```bash
   cd ../jclaw-wcag/frontend && nohup pnpm dev --port 3100 > /tmp/wcag-dev.log 2>&1 &
   ```
   It proxies `/api` to `localhost:9000` by default (`JCLAW_BACKEND_PORT` overrides it), so it shares the running backend and its real data. **Read-only in the UI too:** never click a destructive control while auditing — those writes hit the live database.
   **Wait by polling the port, not by grepping the log for a readiness marker.** Nuxt's startup text varies by version and an `until grep` loop on a marker that never arrives spins forever:
   ```bash
   for i in $(seq 1 30); do curl -sf -o /dev/null http://localhost:3100/ && break; sleep 2; done
   ```
   Authentication carries over for free — cookies ignore the port, so a session established on :9000 is already valid on :3100.

2. **Switch themes via `localStorage`, then reload — never `classList.toggle('dark')`.** The app themes through the View Transitions API, so forcing the class mid-session catches a half-reconciled frame and manufactures phantom failures (a past run invented 21 of them):
   ```js
   localStorage.setItem('jclaw-theme', 'dark')   // or 'light'; remove the key to restore 'system'
   ```
   then reload the page and measure.

3. **Run two independent measurements per page, because they have complementary blind spots:**
   - The Chrome DevTools MCP **Lighthouse accessibility audit** (axe-backed).
   - **Your own computed-contrast sweep** via `evaluate_script`, compositing each element's effective background through its ancestors and its inherited `opacity`.

   Neither alone is sufficient. **A Lighthouse 100 is not coverage:** axe records contrast over an alpha background as *incomplete*, and Lighthouse reports only *violations*, so 16 pills measuring 1.55–2.29:1 once passed a 100. Conversely, static token math alone missed an `opacity-60` utility layered over a passing token.

4. **Parse `oklch()` and `oklab()`.** Tailwind v4 emits its palette in those spaces, and an rgb-only parser silently treats them as black — false-failing exactly the accent colors you care about. Convert via Ottosson's oklab→sRGB. Sanity-check the parser against a known value before trusting a sweep.

5. **For the 320px reflow check use `emulate`, not `resize_page`** — Chrome will not size a window below roughly 500px:
   ```
   emulate(viewport: "320x800x1,mobile,touch")
   ```
   Measure `main.scrollWidth` against `clientWidth`, and look for text spans that have collapsed to zero width.

6. Check that a visible focus indicator appears on keyboard focus, and measure its contrast against the adjacent surface (≥ 3:1). Note that scripted `.focus()` does not always trigger `:focus-visible`, so a computed-style diff shows an indicator *exists*, not that it clears 3:1.

7. **When measured values disagree with declared tokens, trust the measured values.**

If no server can be reached, **say so, continue static-only, and mark the live gap in the report** — don't silently skip it.

---

## WCAG criteria (emphasis in bold)
- **1.4.3 Contrast (Minimum, AA)** — text ≥ 4.5:1; large text (≥24px, or ≥18.66px bold) ≥ 3:1 — **both themes**.
- **1.4.11 Non-text contrast** — UI components, focus rings, icon/border affordances ≥ 3:1.
- **1.4.4 Resize text · 1.4.12 Text spacing · 1.4.10 Reflow** — **typography & layout** survive 200% zoom and 320px width.
- **1.4.1 Use of color** — color is not the only signal.
- **2.4.7 Focus visible · 2.5.8 Target size**.
- Secondary (report if seen): 1.3.1 info & relationships (headings/labels), 1.1.1 alt text, form-control labels.

**Exempt — do not report as defects:** disabled controls (1.4.3 explicitly exempts them) and purely decorative `aria-hidden` elements. An icon that is a control's only affordance is *not* decorative; it owes 3:1.

## Known-intentional exceptions — verify, don't blind-flag
Some low-contrast patterns here are deliberate and were marked false-positive in Sonar (e.g. the `chat.vue` accent palette; intentional muted hint text; decorative low-emphasis copy). For these, report as **"verify intentional"** with the rationale rather than asserting a defect. If you can't tell whether a token pairing is real on-screen usage, say so instead of guessing.

---

## Phase 3 — The remediation list, and stop

Present ONE prioritized table, grouped by the four emphasis areas (Contrast · Typography · Layout · Color scheme), then General:

| # | Area | WCAG (level) | Severity | Theme | Location | Current | Required | Proposed remediation |

- **Theme**: light / dark / both.
- **Location**: `file:line` (static) or `page → CSS selector` (live).
- **Current / Required**: concrete, e.g. measured `3.2:1` vs `4.5:1`, or `12px` vs `≥16px`.
- **Proposed remediation**: minimal and specific — name the **token** to change (e.g. raise `--muted-foreground` lightness in `.dark` from X→Y in `tailwind.css`) or the class to swap. **Prefer token-level fixes** that cascade across both themes over per-component patches. Where only one theme fails, fix that half and leave the passing half alone — swapping both is a larger diff that changes a working appearance for nothing.
- **Severity**: Critical (AA failure on primary content) → High → Medium → Low/advisory.

Close with a summary: counts by area and severity, coverage (pages × themes audited), and explicitly **what could not be verified** (e.g. static-only because the app was down, transient states never triggered, pages not reached). Give the worktree path and branch. Then **stop and ask which rows to approve** — make no edits until they choose.

---

## Phase 4 — Apply the approved fixes (worktree only)

Only the rows the user named. Work in `../jclaw-wcag`; never edit the primary tree.

1. **Apply in the order given**, or if none was given, cheapest-cascade first: a base-layer or token rule that fixes many call sites, then component sweeps, then per-element patches.
2. **Re-measure on the worktree's dev server** — both themes, and at 320px for any reflow row. Report before/after numbers per row. Fixing the worst failures often **uncovers a tier that was hidden behind them** (clearing a set of 4.0:1 links exposed 4.44:1 callout titles that had never appeared in a top-N list); re-sweep until the page is clean, and say so when the tail is a pre-existing failure rather than a regression.
3. **Confirm the desktop layout is unchanged** for any layout row — measure at 1280 as well as 320.
4. **Gate in the worktree** (this is why Phase 0 step 2 exists):
   ```bash
   cd ../jclaw-wcag/frontend && pnpm lint && pnpm stylelint && pnpm typecheck && pnpm test
   ```
   Stylelint enforces `comment-empty-line-before`, so a comment added above a rule needs a blank line before it.
5. **Stop the dev server** you started, by its port and not by name, so a `pkill` pattern can't reach the user's other processes:
   ```bash
   lsof -nP -iTCP:3100 -sTCP:LISTEN | awk 'NR>1{print $2}' | sort -u | xargs -r kill
   ```
   Verify :3100 is free and :9000 still answers.
6. **Commit in the worktree**, one commit for the batch (or one per area if the batch is large). The GitHub mirror is public, so the body is a published artifact: state what changed, the measured before/after, and what was deliberately left. Never push, never merge — that's the user's call via `/deploy`.
7. Report the worktree path (`../jclaw-wcag`), branch (`wcag-fixes`), commit hashes, the gate result, and the rows still open. Leave the worktree for the user to review; remove it only on their say-so.

---

## Hard rules
- **Read-only until approval.** The audit proposes; the user disposes. No edits — not even "obvious" ones — before they name rows.
- **Work in `../jclaw-wcag` only.** Never edit the primary working tree. Never `git push` or merge.
- **Install and prepare the worktree's frontend before trusting any gate.** Missing `node_modules`/`.nuxt` produces a false RED that looks exactly like a real one.
- **Measure, don't infer.** Tailwind v4 emits oklch, so hex equivalents are approximations; every number in the report and the commit body is pixel-readback or a validated computation, and the two must be reconciled when they disagree.
- **Run axe and your own sweep.** Either alone has missed real, visible failures in this app.
- **Never theme by `classList.toggle('dark')`** — `localStorage['jclaw-theme']` then reload.
- **Never start a wait-loop on a log marker.** Poll the port; an unmatched `until grep` orphans and spins for the rest of the session.
- **Don't report disabled controls or decorative elements as contrast defects**, and don't silently drop a failing icon that is a control's only affordance.
- The prod instance on :9000 is serving real work. Do not restart or stop it, and do not click destructive controls in the audited UI — the dev server proxies to its live database.
