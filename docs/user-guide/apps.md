# Apps

**Apps** are operator-hosted mini-apps: small, self-contained web apps that JClaw serves directly, shown as a home-screen-style grid on the [Apps](/apps) page. An app is a different shape from everything else in the guide. It is not an agent, a task, or a tool. It is a plain static site (HTML/CSS/JS). What makes it interesting is how it gets built: you describe what you want, and the agent's [app-creator skill](/guide#skills-tools-mcp) builds and installs it for you.

Reach for an app when you want a small, reusable, point-and-click tool that lives outside the chat: a calculator, a form, a generator, a dashboard over local data. Use an app instead of asking the agent to do the same thing each time.

## What an app is

Each app is a directory under `public/apps/<slug>/` that carries, at minimum, an `index.html` and an `app.json` manifest. **The filesystem is the whole registry**: there is no database row. Adding an app means adding its directory. Removing an app means removing its directory.

An app is a **static bundle**: no server, no database. It may use client-side storage (`localStorage` / `IndexedDB`) or call an external API. The one part of JClaw's backend it may call is its own invoke endpoint. `POST /api/apps/<slug>/invoke` runs the app's designated agent on a `message` and/or file uploads. The agent is resolved from `app.json`'s `agent` field, never from the request. `GET /api/apps/<slug>/files/<uuid>` fetches a file that run produced. Each invoke runs in a fresh app-owned conversation (channel `app`). Because an app is not the operator's own web chat, its turns are untrusted. Dangerous tools such as `exec` fail closed for them. Once installed, the app is served at `/apps/<slug>/` and opens in a new browser tab from its card.

The `<slug>` (the directory name) must match `^[a-z0-9][a-z0-9-]*$`: lowercase letters, digits, and hyphens only. It therefore can never traverse out of `public/apps/`.

## The manifest (`app.json`)

Every app self-describes through a small `app.json` alongside its `index.html`. A malformed or missing manifest only makes the app invisible on the Apps page. It never causes a server error.

| Field | Meaning |
|---------------|---------------------------------------------------------------------------------------------------|
| `name` | Display name on the card. Falls back to the slug when absent. |
| `version` | Shown under the name (for example `v1.0.2`). Defaults to `0.0.0`. |
| `creator` | Author label shown beside the version. Optional. |
| `description` | Short blurb describing what the app does. Optional. |
| `icon` | Filename of an icon inside the app directory (for example `icon.svg`). Cards fall back to a placeholder tile when absent or unloadable. |
| `price` | A **metadata-only** pricing label (`Free`, `$20`, `$9/mo`). Purely a badge: JClaw charges nothing. |
| `agent` | Optional id of the single agent this app is allowed to invoke. Omit it for a non-invoking app. |
| `limit` | Optional cap on `invoke` calls per 60-second window. Default 30; a manifest value replaces it, up to a hard ceiling of 120 (a higher one is clamped to the ceiling). Over the limit, invoke answers HTTP 429 until the window rolls. |

## The Apps page

The [Apps](/apps) page is a grid of app cards, styled like a phone home screen. Each card shows the app's icon, name, version, creator, and price label. Clicking a card opens the app in a new tab.

- **Search**: a floating search bar at the bottom filters the grid by app name in real time.
- **Update** (pencil, top-left on hover): hands the app to the app-creator skill for an edit (see below).
- **Delete** (trash, top-right on hover): removes the app behind a confirmation.
- **Install** (download arrow, bottom-right on hover; only on an app with an icon): opens the app ready to install as a desktop app (see below).

Every hosted app installs as a standalone desktop app (a PWA) from Chrome, Edge, or Safari, with no change to its own files. JClaw serves each app a web manifest built from its `app.json` (name, description, icon) and links it into `index.html` as it is served. An app that ships its own manifest link is left alone. **Install** opens the app with a banner. Where the browser supports an **Install** button, the banner offers one. Otherwise it points to the browser menu: **Install app** in Chrome and Edge, **File → Add to Dock** in Safari. Browsers install only over a secure connection, so open JClaw at its `https://` address.

## Creating an app

You do not build an app by hand. You describe it, and the agent builds it. Click **Create app** and fill in the brief:

- **App name** and **Author** (both optional): these become the manifest's `name` and `creator`.
- **What should the app do?**: the description the agent builds from.
- **Designated agent** (optional): the one agent this app may invoke (the manifest `agent` field).
- **Cost**: **Free** (the default), **Subscription**, or **Fixed price**. The paid kinds take a currency and amount. The choice becomes the display-only `price` label.

Submitting **Build in Chat →** does not fire a build immediately. It prefills a request into the [Chat](/chat) composer that invokes the **app-creator** skill. You review it and send it there. The skill builds `index.html`, `app.json`, and a generated `icon.svg` in the agent's workspace. It then installs the bundle into `public/apps/<slug>/`, so the Apps page serves it.

:::note How the build runs
By default the app-creator skill delegates the actual build to an external coding harness (`runtime=acp`: Pi / Claude Code / Codex). It falls back to a direct build when no harness is configured. Its default output is a **self-contained single-file static app** (inline `<style>` + `<script>`, client-side storage). It only reaches for a Nuxt static build when the app genuinely needs multiple routes or shared state.
:::

## Updating an app

The pencil button on a card starts an update scoped to that app. Describe what should change. Optionally reassign the designated agent or change the **Cost** (it keeps the current pricing unless you pick another). Then **Update in Chat →** hands the request to the app-creator skill in Chat. The skill edits `public/apps/<slug>/` in place and bumps the version: patch for fixes, minor for features, major for breaking changes.

## Deleting an app

The trash button removes an app behind a danger confirmation. An app is purely a `public/apps/<slug>/` directory with no database state. So deleting the directory removes the app completely and irreversibly.

:::gotcha Apps are trusted static content
An app you install runs in your browser with access to whatever it fetches. Treat an app the way you would treat any static site you host. The app-creator skill's default self-contained shape keeps the surface small. But you own what ends up under `public/apps/`.
:::

## Where to go next

- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp): the app-creator skill, and the tools it uses to build and install apps.
- [Chat](/chat): where every create and update request lands for you to review and send.
- [Agents](/guide#agents): configure the agent an app is allowed to invoke.