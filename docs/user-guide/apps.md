# Apps

**Apps** are operator-hosted mini-apps — small, self-contained web apps that JClaw serves directly, shown as a home-screen-style grid on the [Apps](/apps) page. They're a different shape from everything else in the guide: an app isn't an agent, a task, or a tool. It's a plain static site (HTML/CSS/JS). What makes it interesting is *how it gets built* — you describe what you want, and the agent's [app-creator skill](/guide#skills-tools-mcp) builds and installs it for you.

Reach for an app when you want a small, reusable, point-and-click tool that lives outside the chat — a calculator, a form, a generator, a dashboard over local data — rather than something you re-ask the agent to do each time.

## What an app is

Each app is a directory under `public/apps/<slug>/` that carries, at minimum, an `index.html` and an `app.json` manifest. **The filesystem is the whole registry** — there's no database row. Adding an app means adding its directory; removing an app means removing its directory.

An app is a **static bundle**: no server, no database. It may use client-side storage (`localStorage` / `IndexedDB`) or call an external API, but it must not depend on JClaw's backend. Once installed, it's served at `/apps/<slug>/` and opens in a new browser tab from its card.

The `<slug>` (the directory name) must match `^[a-z0-9][a-z0-9-]*$` — lowercase letters, digits, and hyphens only — so it can never traverse out of `public/apps/`.

## The manifest (`app.json`)

Every app self-describes through a small `app.json` alongside its `index.html`. A malformed or missing manifest just makes the app invisible on the Apps page — never a server error.

| Field         | Meaning                                                                                          |
|---------------|--------------------------------------------------------------------------------------------------|
| `name`        | Display name on the card. Falls back to the slug when absent.                                     |
| `version`     | Shown under the name (e.g. `v1.0.2`). Defaults to `0.0.0`.                                         |
| `creator`     | Author label shown beside the version. Optional.                                                  |
| `description` | Short blurb describing what the app does. Optional.                                                |
| `icon`        | Filename of an icon inside the app directory (e.g. `icon.svg`). Cards fall back to a placeholder tile when absent or unloadable. |
| `price`       | A **metadata-only** pricing label (`Free`, `$20`, `$9/mo`). Purely a badge — JClaw charges nothing. |
| `agent`       | Optional id of the single agent this app is allowed to invoke. Omit for a non-invoking app.        |

## The Apps page

The [Apps](/apps) page is a grid of app cards, styled like a phone home screen. Each card shows the app's icon, name, version, creator, and price label; clicking it opens the app in a new tab.

- **Search** — a floating search bar at the bottom filters the grid by app name in real time.
- **Update** (pencil, top-left on hover) — hands the app to the app-creator skill for an edit (see below).
- **Delete** (trash, top-right on hover) — removes the app behind a confirmation.

## Creating an app

You don't build an app by hand — you describe it, and the agent builds it. Click **Create app** and fill in the brief:

- **App name** and **Author** (both optional) — become the manifest's `name` and `creator`.
- **What should the app do?** — the description the agent builds from.
- **Designated agent** (optional) — the one agent this app may invoke (the manifest `agent` field).
- **Pricing label** (optional) — a display-only `price`.

Submitting **Build in Chat →** doesn't fire a build immediately. It prefills a request into the [Chat](/chat) composer that invokes the **app-creator** skill — you review it and send it there. The skill builds `index.html`, `app.json`, and a generated `icon.svg` in the agent's workspace, then installs the bundle into `public/apps/<slug>/` so the Apps page serves it.

:::note How the build runs
By default the app-creator skill delegates the actual build to an external coding harness (`runtime=acp` — Pi / Claude Code / Codex), falling back to a direct build when no harness is configured. Its default output is a **self-contained single-file static app** (inline `<style>` + `<script>`, client-side storage); it only reaches for a Nuxt static build when the app genuinely needs multiple routes or shared state.
:::

## Updating an app

The pencil button on a card starts an update scoped to that app. Describe what should change — and optionally reassign the designated agent or the pricing label — then **Update in Chat →** hands the request to the app-creator skill in Chat. The skill edits `public/apps/<slug>/` in place and bumps the version (patch for fixes, minor for features, major for breaking changes).

## Deleting an app

The trash button removes an app behind a danger confirmation. Because an app is purely a `public/apps/<slug>/` directory with no database state, deleting the directory removes the app completely and irreversibly.

:::gotcha Apps are trusted static content
An app you install runs in your browser with access to whatever it fetches. Treat an app the way you'd treat any static site you host — the app-creator skill's default self-contained shape keeps the surface small, but you own what ends up under `public/apps/`.
:::

## Where to go next

- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) — the app-creator skill and the tools it uses to build and install apps.
- [Chat](/chat) — where every create/update request lands for you to review and send.
- [Agents](/guide#agents) — configure the agent an app is allowed to invoke.
