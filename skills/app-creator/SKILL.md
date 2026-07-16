---
name: app-creator
description: Builds a hosted mini-app in your workspace and installs it to public/apps/<slug>/ via the app_install tool — delegates the coding to an external harness (pi/claude/codex via runtime=acp) when one is configured, else builds it directly. The app then appears on the JClaw Apps page.
version: 1.2.1
author: jclaw
tools: [exec, subagent_spawn, filesystem, app_install]
commands: []
icon: 🧩
---
# App Creator

Build a small, self-contained web app and install it as a JClaw **hosted app** — a static site served at `/apps/<slug>/` that shows up on the **Apps** page — or **update** one that's already there. Use this when the operator asks to "create an app", "build an app", or describes a mini-tool they want hosted (e.g. an RFP/proposal builder, a calculator, a form), or asks to "update" / change an existing app (see **Updating an existing app** below).

## How hosting works — build in your workspace, then install

Your filesystem tools are confined to **your workspace** — you **cannot** write `public/apps/` directly (a direct write there fails with "escapes the workspace"). So the flow is always two steps:

1. **Build** the app inside your workspace, in a directory named for the slug: `<slug>/index.html` (+ assets) and `<slug>/app.json`.
2. **Install** it with the **`app_install`** tool, which copies your workspace `<slug>/` into `public/apps/<slug>/` (running as trusted JClaw code) so the Apps page serves it.

A hosted app is a **static bundle**: no server, no database. It may use client-side storage (localStorage/IndexedDB) or call an external API, but it must not depend on a JClaw backend.

## 1. Clarify + name

Pin down what the app does (its pages, the core action) and a display name. Derive `<slug>` = the name lowercased, spaces → hyphens, non-alphanumerics stripped (e.g. "Proposal Generator" → `proposal-generator`); it must match `^[a-z0-9][a-z0-9-]*$`. Confirm it if ambiguous. Capture: **name**, **creator** (default the operator), **version** (default `1.0.0`), an optional **price/plan** label (metadata only), an optional **icon**, and an optional **designated agent id** (JCLAW-763 — the one agent the app may invoke; passed by the Create/Update form).

## 2. Build the app (in your workspace)

### Pick the app shape first

- **Self-contained static — the default.** A single `index.html` with inline `<style>` + `<script>` and client-side storage (localStorage/IndexedDB), written to `<slug>/index.html` in your workspace (plus an optional `icon.svg`). Use it for single-page tools: forms, calculators, generators, dashboards over local data — the large majority of hosted mini-apps. No build step, no framework runtime, and **no `/_nuxt/` asset paths to break**, so it's the most robust way to host a small app.
- **Nuxt 4 static build — only when genuinely warranted.** Reach for Nuxt when the app really needs it: multiple routes, shared reactive state, or a component-heavy UI. Scaffold a Nuxt 4 app (Vue 3 + TypeScript + Tailwind v4 to match the JClaw frontend) in a workspace build dir, set `ssr: false` + `app.baseURL: '/apps/<slug>/'`, `nuxi generate`, then place `.output/public/*` into your workspace `<slug>/`.

Default to self-contained static unless the requirements clearly demand Nuxt — a smaller, buildless bundle is easier to host and can't trip the baseURL asset trap.

**Prefer delegating the coding to an external harness; build it yourself only when none is configured.** (Either shape.) Either way, the app is built into your workspace `<slug>/` — never into `public/apps/`.

### Preferred — delegate to a coding harness (runtime=acp)

A coding harness (`pi -p`, `claude -p`, `codex`, …) is a far stronger multi-file coder than inline tool calls. Hand it the whole build with the `subagent_spawn` tool using **`runtime: "acp"`** and a **fully self-contained task** — the harness sees only the task string (not this skill), so spell out the exact files and the exact manifest. For a self-contained static app:

> Build a **self-contained static** web app for: `<the operator's requirements>`. A single `index.html` with inline `<style>` and `<script>` — no framework, no build step, no external CDN/font/image requests (embed assets). Persist data client-side (localStorage) — there is no backend.
> Create a `<slug>/` directory in your current working directory and write **exactly** `<slug>/index.html` and `<slug>/app.json` into it (plus `<slug>/icon.svg` only if you set an icon). Write nothing anywhere else.
> `<slug>/app.json` must be EXACTLY this object — **these keys only, and no others** (do NOT add `slug`, `entry`, or any extra key):
> ```json
> {"name": "<Display Name>", "version": "1.0.0", "creator": "<creator>", "description": "<one-sentence summary>", "icon": "", "price": "", "agent": <id>}
> ```
> Set `icon` to `"icon.svg"` only if you created that file, else `""`. Leave `price` as `""` for a free app. Set `agent` to the designated agent id when one is given, otherwise **omit the `agent` key entirely**.

For the Nuxt shape, instead instruct: build a Nuxt 4 app with `ssr: false` and `app: { baseURL: '/apps/<slug>/' }` (trailing slash required — without it the assets break), run `nuxi generate`, and place `.output/public/*` into the `<slug>/` directory.

**Where the harness writes — and how to install it.** A `runtime=acp` harness runs in its OWN per-session directory under `coding/<name>/` in your workspace (JCLAW-666), **not** your workspace root — so it writes the app to `coding/<name>/<slug>/`. You don't need to track that: after the harness finishes, just call `app_install` `action: "validate"` then `action: "install"` with `slug: "<slug>"`. It **auto-locates** the built app — the workspace-root `<slug>/` (direct build) or the newest `coding/<session>/<slug>/` (harness build) — and copies it to `public/apps/<slug>/`. (The install result echoes the `source` it used.)

`runtime=acp` runs the operator-configured harness (`subagent.acp.command`). **If it reports no harness is configured**, fall through to the direct path below — do NOT ask the operator to configure one unless they want the harness.

### Fallback — build it yourself

With no harness configured, do it directly using your `exec` + `filesystem` tools, writing into your workspace `<slug>/`. Self-contained static: author `<slug>/index.html` (inline CSS/JS, client-side storage). Nuxt shape: scaffold a lean Nuxt 4 app in a workspace build dir, set `ssr: false` + `app.baseURL: '/apps/<slug>/'`, `npx nuxi generate`, place `.output/public/*` into `<slug>/`. Either way, write the manifest, then install (section 4).

## 3. The app.json manifest

Write `<slug>/app.json` in your workspace (it travels into `public/apps/<slug>/` on install):

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

- `icon` is a path **relative to the app dir** (drop e.g. `icon.png` into `<slug>/`); omit it to use the Apps page's default tile.
- `price` is a **display label only** — no payments or access control. Omit for free apps.
- `name`, `version`, `creator` are shown on the card. `name` and `version` are **required** — install refuses a manifest missing either.
- `agent` (JCLAW-763) is the **designated agent id** the app is allowed to invoke — the numeric id from `GET /api/agents`. Set it to exactly the id the operator's request names (the Create/Update App form passes it as *"Designated agent id … : `<id>`"*). **Omit the field** when the request names no agent (or says to remove it) — the app is then non-invoking. Never invent an id.
- **Use exactly these keys** — `name`, `version`, `creator`, `description`, and the optional `icon` / `price` / `agent`. Do NOT add non-standard keys (`slug`, `entry`, …); the Apps page reads only these.

## 4. Install

With the app built in your workspace `<slug>/`, publish it with the **`app_install`** tool:

1. *(Optional but recommended)* `app_install` with `action: "validate"`, `slug: "<slug>"` — confirms `<slug>/` is well-formed (parseable `app.json` with name + version, `index.html` present). Fix any reported issues before installing.
2. `app_install` with `action: "install"`, `slug: "<slug>"` — copies your workspace `<slug>/` to `public/apps/<slug>/`, replacing any existing app at that slug, and returns the `/apps/<slug>/` url. (`source` defaults to `<slug>`; pass it only if you built under a different workspace dir.)

Never try to write `public/apps/` with your filesystem or exec tools — it is outside your workspace and will be rejected. `app_install` is the only path in.

## 5. Confirm

Tell the operator it's installed: it now appears on the **Apps** page and launches in a new tab at `/apps/<slug>/`. (The Apps page enumerates `public/apps/*/` via `GET /api/apps`.)

## Updating an existing app

When the operator asks to **update** an existing app — the Apps page's per-card update affordance sends a request naming the app, its slug, and the current version — edit it **in place** and bump its version; do NOT create a new app:

1. **Stage it into your workspace.** Call `app_install` with `action: "stage"`, `slug: "<slug>"` — it copies `public/apps/<slug>/` into your workspace `<slug>/` (idempotent; pass `overwrite: true` to refresh an existing workspace copy). Now read `<slug>/app.json` (current `name`, `version`, `creator`, `price`, `icon`, `agent`) and `<slug>/index.html` so you change the right app and keep its identity.
2. **Apply the changes** in your workspace `<slug>/`, keeping the SAME shape it already uses (a self-contained `index.html`, or a Nuxt rebuild with the same `app.baseURL: '/apps/<slug>/'`). Preserve the slug, name, creator, and price unless the operator asked to change them. If the request sets, changes, or removes the **designated agent**, write (or delete) the `agent` field in `<slug>/app.json` to match the named id. Prefer delegating to the harness (`runtime=acp`) with the staged files as context; otherwise edit directly with your `exec` + `filesystem` tools.
3. **Bump the version** in `<slug>/app.json`: **patch** for small fixes, **minor** for new features, **major** for breaking changes or a redesign.
4. **Install** the updated app: `app_install` with `action: "install"`, `slug: "<slug>"` (validate first if unsure). The bumped version shows on the app's card immediately (the Apps page re-reads `app.json` via `GET /api/apps`).

## Guardrails

- **Never write `public/apps/` directly.** Your filesystem/exec tools are workspace-confined; `public/apps/` is off-limits to them by design. Build in your workspace `<slug>/`, then use `app_install` — it is the only writer of `public/apps/`.
- **Static only.** Never wire the app into a JClaw route or the JClaw DB. Persistence = client-side storage or a third-party API the operator provides.
- **Base path is mandatory for the Nuxt shape.** If you build with Nuxt, `app.baseURL = '/apps/<slug>/'` is required — without it the generated assets point at `/_nuxt/…` (JClaw's own SPA assets) and the app breaks. Always set it, and pass it to the harness in the task. (Self-contained static apps use relative paths and sidestep this entirely — another reason it's the default.)
- **Trusted, same-origin.** The app runs on JClaw's origin; only build apps the operator authored or asked for.
