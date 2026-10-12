# Getting Started

Welcome to JClaw. This user guide explains how JClaw works. It helps you get the most from the platform.

## What is JClaw?

JClaw is a workbench for building, running, and watching AI agents. You configure agents (their model, system prompt, and tools), give them work through [Chat](/chat) or external channels, and watch what they do across the rest of the app.

JClaw Pro is a single-operator workbench: one **admin** login, the built-in **main** agent, and any custom agents you create.

## How the rest of the guide is organized

The whole product builds on a single core loop: you send a message in [Chat](/chat) and an agent answers. Every other capability in JClaw is a layer on that loop:

1. **[Chat](/guide#chat)** is the base experience. Pick an agent, send messages, get answers.
2. **[Prompts](/guide#prompts)** is your library of saved, reusable prompts. You drop them into the composer.
3. **[Agents](/guide#agents)** is where you configure the entities that answer you: model, prompt, tools, skills.
4. **[Conversations & Channels](/guide#conversations-and-channels)** covers your chat history. It also shows how to make agents reachable from Slack, Telegram, and WhatsApp, not only from the web app.
5. **[Subagents](/guide#subagents)**, **[Tasks](/guide#tasks)**, and **[Reminders](/guide#reminders)** are three kinds of "work outside the current chat turn": parallel work now, scheduled work later, and pure scheduled notifications. [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) compares them side by side, if you do not know which one fits.
6. **[Scrapes](/guide#scrapes)** is where you start, watch, and read background scrapes. A background scrape reads a website page by page, well beyond what fits in one chat turn.
7. **[Skills, Tools & MCP Servers](/guide#skills-tools-mcp)** shows how you extend what agents can do beyond plain text: web search, shell exec, external systems.
8. **[Apps](/guide#apps)** turns a repeatable request into a small self-contained web app. The agent builds it. JClaw hosts it.
9. **[Settings](/guide#settings)** is the operator's control panel for everything above.
10. **[Memories](/guide#memory)** is what your agents captured about you and your work. It is also where you curate it.
11. **[Logs & Dashboard](/guide#logs-and-dashboard)** shows what happens across the whole platform.

Read in that order on your first visit. Skim when you hunt for a specific thing.

## Your first five minutes

1. **Set a theme.** The toggle is in the top-right of every page (system / light / dark).
2. **Visit [Settings](/settings) and add at least one LLM provider.** Without an API key (or a configured local provider such as Ollama), no agent can answer. See the [Settings](/guide#settings) section of this guide for what each provider needs.
3. **Visit [Agents](/agents) and create or enable an agent.** Pick a model, describe its role in the `AGENT.md` workspace file, and turn on the tools you need. The default agent template is a good starting point.
4. **Open [Chat](/chat), pick that agent from the Agent dropdown in the header, and say hello.** The platform saves your conversation. You can reopen it later from [Conversations](/conversations).

That is the minimum loop. Everything else in this guide shows how to do more with it.

### Staying signed in

Your session lasts an hour of **inactivity**, not an hour in total. Every request pushes the window out. So a tab you keep working in never signs you out. Leave it alone for an hour, and you must log in again.

Changing or resetting the admin password signs out every other session immediately. That is deliberate. It is what makes a password change a way to revoke access you did not intend to give, not only a way to change what you type. Upgrading to v0.17.80 or later also asks everyone to sign in once more. This is because sessions issued before that release predate the check.

## The sidebar at a glance

The left sidebar is grouped by intent:

| Group | What lives there |
| --- | --- |
| _(top)_ | [Dashboard](/), the home overview above the groups. |
| **Chat** | [Chats](/chat) (live conversations), [Prompts](/prompts) (saved, reusable prompts you run into the composer), [Channels](/channels) (external chat surfaces such as Slack), [Conversations](/conversations) (every prior thread). |
| **Ops** | [Agents](/agents), [Subagents](/subagents), [Scrapes](/scrapes) (background website crawls), [Apps](/apps), [Tasks](/tasks), [Reminders](/reminders) (your scheduled nudges), [Skills](/skills), [Tools](/tools), [MCP Servers](/mcp-servers). |
| **Admin** | [Settings](/settings), [Memories](/memories) (captured agent memories), [Logs](/logs). |
| **Help** | Feedback, Guided Tour, this **User Guide**. |

At the bottom, two diagnostic rows: your JClaw version (the pip shows API status: green online, red offline, amber when a newer release is available, and the amber pip links to **Settings → Maintenance**) and the Play framework version (a green/amber pip shows whether it matches the pinned `.play-version`). Red on the first dot means the backend is unreachable; most pages fail until it recovers. Below them are your username and **Sign out** (an exit icon when the sidebar is collapsed).

Up in the top bar, the **Search…** box on the right, beside the theme toggle, opens the command palette. <kbd>⌘</kbd>+<kbd>K</kbd> (<kbd>Ctrl</kbd>+<kbd>K</kbd> on Windows and Linux) opens or closes it from any page.

## The Guided Tour

The first time you sign in, JClaw offers a short on-rails tour that highlights the main pages in sequence. You can launch it again at any time from the sidebar under **Help → Guided Tour**.

The tour is fast (a few minutes) and complements this guide:

- **Guided Tour**: "where is everything?"
- **User Guide** (this): "how do I use it?"

## Where to go next

The natural next step is [Chat](/guide#chat), the base loop everything else builds on.

:::tip
Every page title in the top breadcrumb takes you back to that page. The **JClaw** crumb is always home (the [Dashboard](/)).
:::

:::note Beta software
JClaw is still pre-1.0. UI labels and behaviors may shift between releases. If something you see does not match what this guide says, the **Feedback** link in the sidebar is the fastest way to report it.
:::