---
name: app-creator
description: Builds and installs a hosted mini-app into public/apps/<slug>/ — scaffolds a Nuxt 4 static site, generates it, places the output, and writes the app.json manifest so it appears on the JClaw Apps page.
version: 1.0.0
author: jclaw
tools: [exec, filesystem]
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

## Steps

### 1. Clarify + name

Pin down what the app does (its pages, the core action) and a display name. Derive `<slug>` = the name lowercased, spaces → hyphens, non-alphanumerics stripped (e.g. "Proposal Generator" → `proposal-generator`); confirm it if ambiguous. Capture: **name**, **creator** (default the operator), **version** (default `1.0.0`), an optional **price/plan** label (metadata only), and an optional **icon**.

### 2. Scaffold a Nuxt 4 app

In a working directory — **NOT** `public/`, e.g. `workspace/app-build-<slug>/` — create a minimal Nuxt 4 project matching JClaw's frontend conventions (Vue 3 + TypeScript + Tailwind v4). Keep it lean. Install deps with your shell tool.

The one config that makes the static output work under the subpath — set it in `nuxt.config.ts`:

```ts
export default defineNuxtConfig({
  ssr: false,                          // SPA/SSG — no server runtime
  app: { baseURL: '/apps/<slug>/' },   // trailing slash required; resolves asset + route links under the subpath
})
```

### 3. Implement

Build the pages/components per the operator's requirements. Persist data **client-side** (localStorage) unless the operator specifies an external API — there is no JClaw backend for the app.

### 4. Generate (static)

Run `npx nuxi generate` (or `pnpm generate`) in the working dir. The self-contained static site lands in `.output/public/` — an `index.html` plus `_nuxt/` assets, all referencing `/apps/<slug>/…`.

### 5. Place it

Copy `.output/public/*` into `public/apps/<slug>/`, giving you `public/apps/<slug>/index.html` and its assets. Overwrite cleanly when rebuilding an existing app.

### 6. Write the manifest

Write `public/apps/<slug>/app.json`:

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

### 7. Confirm

Tell the operator it's installed: it now appears on the **Apps** page and launches in a new tab at `/apps/<slug>/`. (The Apps page enumerates `public/apps/*/` via `GET /api/apps`.)

## Guardrails

- **Static only.** Never wire the app into a JClaw route or the JClaw DB. Persistence = client-side storage or a third-party API the operator provides.
- **Base path is mandatory.** Without `app.baseURL = '/apps/<slug>/'` the generated assets point at `/_nuxt/…` (JClaw's own SPA assets) and the app breaks. Always set it.
- **Trusted, same-origin.** The app runs on JClaw's origin; only build apps the operator authored or asked for.
