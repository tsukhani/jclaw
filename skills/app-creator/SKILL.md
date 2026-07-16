---
name: app-creator
description: Builds and installs a hosted mini-app into public/apps/<slug>/ — delegates the coding to an external harness (pi/claude/codex via runtime=acp) when one is configured, else builds it directly, then places the static output and writes the app.json manifest so it appears on the JClaw Apps page.
version: 1.0.0
author: jclaw
tools: [exec, subagent_spawn, filesystem]
commands: []
icon: 🧩
---
# App Creator

Build a small, self-contained web app and install it as a JClaw **hosted app** — a static site served at `/apps/<slug>/` that shows up on the **Apps** page. Use this when the operator asks to "create an app", "build an app", or describes a mini-tool they want hosted (e.g. an RFP/proposal builder, a calculator, a form).

## What you produce

A directory `public/apps/<slug>/` containing:

- `index.html` (+ static assets) — a Nuxt 4 app, statically generated, with base path `/apps/<slug>/`.
- `app.json` — the manifest the Apps page reads.

No server, no database — a hosted app is a **static bundle**. It may use client-side storage (localStorage/IndexedDB) or call an external API, but it must not depend on a JClaw backend.

## 1. Clarify + name

Pin down what the app does (its pages, the core action) and a display name. Derive `<slug>` = the name lowercased, spaces → hyphens, non-alphanumerics stripped (e.g. "Proposal Generator" → `proposal-generator`); confirm it if ambiguous. Capture: **name**, **creator** (default the operator), **version** (default `1.0.0`), an optional **price/plan** label (metadata only), and an optional **icon**.

## 2. Build the app

**Prefer delegating the coding to an external harness; build it yourself only when none is configured.**

### Preferred — delegate to a coding harness (runtime=acp)

A coding harness (`pi -p`, `claude -p`, `codex`, …) is a far stronger multi-file coder than inline tool calls. Hand it the whole build with the `subagent_spawn` tool using **`runtime: "acp"`** and a self-contained task, e.g.:

> Build a Nuxt 4 **static** web app for: `<the operator's requirements>`. In `nuxt.config.ts` set `ssr: false` and `app: { baseURL: '/apps/<slug>/' }` (trailing slash required — without it the assets break). Vue 3 + TypeScript + Tailwind v4 to match the JClaw frontend; keep it lean. Persist data client-side (localStorage) — there is no backend. Run `nuxi generate`, copy `.output/public/*` into `public/apps/<slug>/`, then write `public/apps/<slug>/app.json` = `{name, version, creator, icon, price, description}` (see the manifest section).

The harness does the multi-file coding, the static generate, the placement, and the manifest. `runtime=acp` runs the operator-configured harness (`subagent.acp.command`).

**If `runtime=acp` reports no harness is configured**, fall through to the direct path below — do NOT ask the operator to configure one unless they want the harness.

### Fallback — build it yourself

With no harness configured, do it directly using your `exec` + `filesystem` tools: scaffold a lean Nuxt 4 app in a working dir (**NOT** `public/`, e.g. `workspace/app-build-<slug>/`), set `ssr: false` + `app.baseURL: '/apps/<slug>/'`, implement per the requirements, run `npx nuxi generate`, copy `.output/public/*` into `public/apps/<slug>/`, then write the manifest.

## 3. The app.json manifest

Either path must produce `public/apps/<slug>/app.json`:

```json
{
  "name": "Proposal Generator",
  "version": "1.0.0",
  "creator": "<operator>",
  "icon": "icon.png",
  "price": "$9/mo",
  "description": "Create RFPs and proposals."
}
```

- `icon` is a path **relative to the app dir** (drop e.g. `icon.png` into `public/apps/<slug>/`); omit it to use the Apps page's default tile.
- `price` is a **display label only** — no payments or access control. Omit for free apps.
- `name`, `version`, `creator` are shown on the card.

## 4. Confirm

Tell the operator it's installed: it now appears on the **Apps** page and launches in a new tab at `/apps/<slug>/`. (The Apps page enumerates `public/apps/*/` via `GET /api/apps`.)

## Guardrails

- **Static only.** Never wire the app into a JClaw route or the JClaw DB. Persistence = client-side storage or a third-party API the operator provides.
- **Base path is mandatory.** Without `app.baseURL = '/apps/<slug>/'` the generated assets point at `/_nuxt/…` (JClaw's own SPA assets) and the app breaks. Always set it — and pass it to the harness in the task.
- **Trusted, same-origin.** The app runs on JClaw's origin; only build apps the operator authored or asked for.
