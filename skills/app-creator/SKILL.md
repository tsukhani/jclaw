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

Build a small, self-contained web app and install it as a JClaw **hosted app** — a static site served at `/apps/<slug>/` that shows up on the **Apps** page — or **update** one that's already there. Use this when the operator asks to "create an app", "build an app", or describes a mini-tool they want hosted (e.g. an RFP/proposal builder, a calculator, a form), or asks to "update" / change an existing app (see **Updating an existing app** below).

## What you produce

A directory `public/apps/<slug>/` containing:

- `index.html` (+ static assets) — a Nuxt 4 app, statically generated, with base path `/apps/<slug>/`.
- `app.json` — the manifest the Apps page reads.

No server, no database — a hosted app is a **static bundle**. It may use client-side storage (localStorage/IndexedDB) or call an external API, but it must not depend on a JClaw backend.

## 1. Clarify + name

Pin down what the app does (its pages, the core action) and a display name. Derive `<slug>` = the name lowercased, spaces → hyphens, non-alphanumerics stripped (e.g. "Proposal Generator" → `proposal-generator`); confirm it if ambiguous. Capture: **name**, **creator** (default the operator), **version** (default `1.0.0`), an optional **price/plan** label (metadata only), an optional **icon**, and an optional **designated agent id** (JCLAW-763 — the one agent the app may invoke; passed by the Create/Update form).

## 2. Build the app

### Pick the app shape first

- **Self-contained static — the default.** A single `index.html` with inline `<style>` + `<script>` and client-side storage (localStorage/IndexedDB), dropped straight into `public/apps/<slug>/` (plus an optional `icon.svg`). Use it for single-page tools: forms, calculators, generators, dashboards over local data — the large majority of hosted mini-apps. No build step, no framework runtime, and **no `/_nuxt/` asset paths to break**, so it's the most robust way to host a small app.
- **Nuxt 4 static build — only when genuinely warranted.** Reach for Nuxt when the app really needs it: multiple routes, shared reactive state, or a component-heavy UI. Scaffold a Nuxt 4 app (Vue 3 + TypeScript + Tailwind v4 to match the JClaw frontend), set `ssr: false` + `app.baseURL: '/apps/<slug>/'`, `nuxi generate`, then copy `.output/public/*` into `public/apps/<slug>/`.

Default to self-contained static unless the requirements clearly demand Nuxt — a smaller, buildless bundle is easier to host and can't trip the baseURL asset trap.

**Prefer delegating the coding to an external harness; build it yourself only when none is configured.** (Either shape.)

### Preferred — delegate to a coding harness (runtime=acp)

A coding harness (`pi -p`, `claude -p`, `codex`, …) is a far stronger multi-file coder than inline tool calls. Hand it the whole build with the `subagent_spawn` tool using **`runtime: "acp"`** and a self-contained task. For a self-contained static app:

> Build a **self-contained static** web app for: `<the operator's requirements>`. A single `index.html` with inline `<style>` and `<script>` — no framework, no build step, no external CDN/font/image requests (embed assets). Persist data client-side (localStorage) — there is no backend. Write it to `public/apps/<slug>/index.html`, then write `public/apps/<slug>/app.json` = `{name, version, creator, icon, price, description, agent}` (see the manifest section; include `agent` only when the request designates one).

For the Nuxt shape, instead instruct: build a Nuxt 4 app with `ssr: false` and `app: { baseURL: '/apps/<slug>/' }` (trailing slash required — without it the assets break), run `nuxi generate`, and copy `.output/public/*` into `public/apps/<slug>/`.

The harness does the coding, any static generate, the placement, and the manifest. `runtime=acp` runs the operator-configured harness (`subagent.acp.command`).

**If `runtime=acp` reports no harness is configured**, fall through to the direct path below — do NOT ask the operator to configure one unless they want the harness.

### Fallback — build it yourself

With no harness configured, do it directly using your `exec` + `filesystem` tools. Self-contained static: author `public/apps/<slug>/index.html` directly (inline CSS/JS, client-side storage). Nuxt shape: scaffold a lean Nuxt 4 app in a working dir (**NOT** `public/`, e.g. `workspace/app-build-<slug>/`), set `ssr: false` + `app.baseURL: '/apps/<slug>/'`, `npx nuxi generate`, copy `.output/public/*` into `public/apps/<slug>/`. Either way, finish by writing the manifest.

## 3. The app.json manifest

Either path must produce `public/apps/<slug>/app.json`:

```json
{
  "name": "Proposal Generator",
  "version": "1.0.0",
  "creator": "<operator>",
  "icon": "icon.png",
  "price": "$9/mo",
  "description": "Create RFPs and proposals.",
  "agent": 3
}
```

- `icon` is a path **relative to the app dir** (drop e.g. `icon.png` into `public/apps/<slug>/`); omit it to use the Apps page's default tile.
- `price` is a **display label only** — no payments or access control. Omit for free apps.
- `name`, `version`, `creator` are shown on the card.
- `agent` (JCLAW-763) is the **designated agent id** the app is allowed to invoke — the numeric id from `GET /api/agents`. Set it to exactly the id the operator's request names (the Create/Update App form passes it as *"Designated agent id … : `<id>`"*). **Omit the field** when the request names no agent (or says to remove it) — the app is then non-invoking. Never invent an id.

## 4. Confirm

Tell the operator it's installed: it now appears on the **Apps** page and launches in a new tab at `/apps/<slug>/`. (The Apps page enumerates `public/apps/*/` via `GET /api/apps`.)

## Updating an existing app

When the operator asks to **update** an existing app — the Apps page's per-card update affordance sends a request naming the app, its slug (`public/apps/<slug>/`), and the current version — edit it **in place** and bump its version; do NOT create a new app:

1. **Locate + read.** Open `public/apps/<slug>/` (the slug is in the request). Read its `app.json` (current `name`, `version`, `creator`, `price`, `icon`) and its `index.html` (+ any other sources) so you change the right app and keep its identity.
2. **Apply the changes.** Modify per the operator's requirements, keeping the SAME shape it already uses (a self-contained `index.html`, or a Nuxt rebuild with the same `app.baseURL: '/apps/<slug>/'`). Preserve the slug, name, creator, and price unless the operator asked to change them. If the request sets, changes, or removes the **designated agent**, write (or delete) the `agent` field in `app.json` to match the named id — that's the one agent the app may invoke. Prefer delegating to the harness (`runtime=acp`) with the existing files as context; otherwise edit directly with your `exec` + `filesystem` tools.
3. **Bump the version** in `public/apps/<slug>/app.json`: **patch** for small fixes, **minor** for new features, **major** for breaking changes or a redesign.
4. **Confirm.** The bumped version shows on the app's card immediately (the Apps page re-reads `app.json` via `GET /api/apps`).

## Guardrails

- **Static only.** Never wire the app into a JClaw route or the JClaw DB. Persistence = client-side storage or a third-party API the operator provides.
- **Base path is mandatory for the Nuxt shape.** If you build with Nuxt, `app.baseURL = '/apps/<slug>/'` is required — without it the generated assets point at `/_nuxt/…` (JClaw's own SPA assets) and the app breaks. Always set it, and pass it to the harness in the task. (Self-contained static apps use relative paths and sidestep this entirely — another reason it's the default.)
- **Trusted, same-origin.** The app runs on JClaw's origin; only build apps the operator authored or asked for.
