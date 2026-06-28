---
description: Audit the frontend for WCAG 2.2 AA compliance (contrast across light/dark, typography, layout, color scheme) and produce an approval-ready remediation list. Read-only — proposes, never edits.
argument-hint: "[pages or areas to scope, optional — e.g. chat settings channels]"
---

# WCAG / UI-UX accessibility audit

Audit the JClaw Nuxt frontend (`frontend/`) for **WCAG 2.2 AA**, with particular emphasis on **contrast (light AND dark themes), typography (font sizing), layout, and color schemes**. Produce a prioritized **remediation list for the user to approve**.

**This run is read-only. Do NOT edit, fix, or stage any files.** Inspect, then present the list and stop. Fixes happen in a separate step only after the user approves specific items.

**Scope:** `$ARGUMENTS` — if empty, audit the whole UI.

## Orient first: how this frontend themes
- Theme tokens are shadcn-style CSS custom properties (HSL) in `frontend/assets/css/tailwind.css`: **light under `:root`, dark under `.dark`**, exposed as Tailwind v4 utilities via `@theme inline`. Agent/driver accent colors live in `frontend/assets/css/driver-theme.css`.
- Dark mode is **class-based**: `.dark` on `<html>` (`@custom-variant dark (&:where(.dark, .dark *))`). Toggle at runtime with `document.documentElement.classList.toggle('dark')`.
- Pages: `frontend/pages/**` (including `channels/`, `conversations/`). Components: `frontend/components/**`.
- Every finding must be evaluated in **both themes** — a pairing that passes in light can fail in dark and vice-versa.

## Method — static first, then live

### 1. Static pass (always)
- Extract every light/dark **color-variable pair** from `tailwind.css` (`:root` vs `.dark`): foreground/background, muted/muted-foreground, primary, secondary, destructive, border, input, ring, accent, popover, card, etc. For each pairing the UI actually renders text on, compute the WCAG contrast ratio **in both themes**.
- Build a **typography inventory**: the `@theme` font-size / line-height tokens plus every `text-*`, `text-[...]`, and inline `font-size` across pages/components. Flag body copy below ~16px, `text-xs`/≤12px on meaningful content, weak line-height, and fixed px that won't honor user zoom.
- **Layout**: fixed heights/widths on text containers, `overflow-hidden` that can clip text, tap/click targets < 24×24 CSS px (WCAG 2.5.8; 44px ideal), and reflow risks at a 320px viewport.
- **Color scheme**: information conveyed by color alone (1.4.1); hardcoded hex/hsl/`rgb()` outside the token system (theme-blind values that won't adapt to dark); non-text contrast of borders, icons, and focus rings (≥ 3:1).
- Fold in the existing linters: `cd frontend && pnpm lint` (eslint-plugin-vuejs-accessibility) and `pnpm stylelint`. Treat their a11y output as input, not gospel.

### 2. Live pass (when the app is running)
- Check `http://localhost:3000` (dev) or the prod port. If neither responds, **say so, continue static-only, and mark the live gap in the report** (don't silently skip).
- For each in-scope page, in **both light and dark** (toggle `classList.toggle('dark')` on `<html>`): run the Chrome DevTools MCP **Lighthouse accessibility audit** (axe-backed — authoritative for computed contrast), then `evaluate_script` to measure actual computed `color`/`background-color` contrast on rendered text, computed `font-size`s, and that a visible focus indicator appears on keyboard focus.
- When measured values disagree with declared tokens, **trust the measured values**.

## WCAG criteria (emphasis in bold)
- **1.4.3 Contrast (Minimum, AA)** — text ≥ 4.5:1; large text (≥24px, or ≥18.66px bold) ≥ 3:1 — **both themes**.
- **1.4.11 Non-text contrast** — UI components, focus rings, icon/border affordances ≥ 3:1.
- **1.4.4 Resize text · 1.4.12 Text spacing · 1.4.10 Reflow** — **typography & layout** survive 200% zoom and 320px width.
- **1.4.1 Use of color** — color is not the only signal.
- **2.4.7 Focus visible · 2.5.8 Target size**.
- Secondary (report if seen): 1.3.1 info & relationships (headings/labels), 1.1.1 alt text, form-control labels.

## Known-intentional exceptions — verify, don't blind-flag
Some low-contrast/a11y patterns here are deliberate and were marked false-positive in Sonar (e.g. the `chat.vue` accent palette; intentional muted hint text; decorative low-emphasis copy). For these, report as **"verify intentional"** with the rationale rather than asserting a defect. If you can't tell whether a token pairing is real on-screen usage, say so instead of guessing.

## Output — the remediation list (for approval)
Present ONE prioritized table, grouped by the four emphasis areas (Contrast · Typography · Layout · Color scheme), then General:

| # | Area | WCAG (level) | Severity | Theme | Location | Current | Required | Proposed remediation |

- **Theme**: light / dark / both.
- **Location**: `file:line` (static) or `page → CSS selector` (live).
- **Current / Required**: concrete, e.g. measured `3.2:1` vs `4.5:1`, or `12px` vs `≥16px`.
- **Proposed remediation**: minimal and specific — name the **token** to change (e.g. raise `--muted-foreground` lightness in `.dark` from X→Y in `tailwind.css`) or the class to swap. **Prefer token-level fixes** that cascade across both themes over per-component patches.
- **Severity**: Critical (AA failure on primary content) → High → Medium → Low/advisory.

Close with a summary: counts by area and severity, coverage (pages × themes audited), and explicitly **what could not be verified** (e.g. static-only because the app was down, or pages not reached). Then **stop and ask the user which remediations to approve** — make no edits until they choose.
