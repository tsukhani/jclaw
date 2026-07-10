# Getting Started

Welcome to JClaw! This user guide will help you understand how JClaw works and make the most of its functionality.

## What is JClaw?

JClaw is a workbench for building, running, and observing AI agents. You configure agents (their model, system prompt, and tools), give them work to do through [Chat](/chat) or external channels, and watch what they do across the rest of the app.

JClaw Personal Edition is a single-operator workbench: one **admin** login, the built-in **main** agent, and any custom agents you create.

## How the rest of the guide is organized

The whole product layers on a single core loop: you send a message in [Chat](/chat) and an agent answers. Every other capability in JClaw is a layer on top of that:

1. **[Chat](/guide#chat)** is the base experience — pick an agent, send messages, get answers.
2. **[Agents](/guide#agents)** is where you configure the entities answering you — model, prompt, tools, skills.
3. **[Conversations & Channels](/guide#conversations-and-channels)** is your chat history *and* how to make agents reachable from Slack, Telegram, and WhatsApp instead of just the web app.
4. **[Subagents](/guide#subagents)**, **[Tasks](/guide#tasks)**, and **[Reminders](/guide#reminders)** are three flavors of "stuff happens outside the current chat turn" — parallel work *now*, scheduled work *later*, and pure scheduled notifications. [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) is the side-by-side comparison if you're not sure which fits.
5. **[Skills, Tools & MCP Servers](/guide#skills-tools-mcp)** is how you extend what agents can do beyond plain text — web search, shell exec, external systems.
6. **[Settings](/guide#settings)** is the operator's control panel for everything above.
7. **[Logs & Dashboard](/guide#logs-and-dashboard)** is how you watch what's happening across the whole platform.

Read in that order if it's your first time. Skim if you're hunting for a specific thing.

## Your first five minutes

1. **Set a theme.** The toggle is in the top-right of every page (system / light / dark).
2. **Visit [Settings](/settings) and add at least one LLM provider.** Without an API key (or a local provider like Ollama configured), no agent can answer. See the [Settings](/guide#settings) section of this guide for what each provider needs.
3. **Visit [Agents](/agents) and create or enable an agent.** Pick a model, write a short system prompt, and turn on whatever tools you need. The default agent template is a sensible starting point.
4. **Open [Chat](/chat), pick that agent in the sidebar, and say hello.** Your conversation appears in the sidebar and you can come back to it later.

That's the minimum loop. Everything else in this guide is how to do more with it.

## The sidebar at a glance

The left sidebar is grouped by intent:

| Group | What lives there |
| --- | --- |
| _(top)_ | [Dashboard](/) — the home overview, sits above the groups. |
| **Chat** | [Chat](/chat) (live conversations), [Channels](/channels) (external chat surfaces like Slack), [Conversations](/conversations) (every prior thread). |
| **Ops** | [Agents](/agents), [Subagents](/subagents), [Tasks](/tasks), [Reminders](/reminders) (your scheduled nudges), [Skills](/skills), [Tools](/tools), [MCP Servers](/mcp-servers). |
| **Admin** | [Settings](/settings), [Memories](/memory) (captured agent memories), [Logs](/logs). |
| **Help** | Feedback, Guided Tour, this **User Guide**. |

At the bottom, two diagnostic rows: your JClaw version (with a green/red pip for API status) and the Play framework version (with a green/amber pip showing whether it matches the pinned `.play-version`). Red on the first dot means the backend is unreachable — most pages will fail until it recovers.

## The Guided Tour

The first time you sign in, JClaw offers a short on-rails tour that highlights the main pages in sequence. You can re-launch it any time from the sidebar under **Help → Guided Tour**.

The tour is fast (a few minutes) and complementary to this guide:

- **Guided Tour** — "where is everything?"
- **User Guide** (this) — "how do I use it?"

## Where to go next

The natural next step is [Chat](/guide#chat) — the base loop everything else builds on.

:::tip
Every page title in the top breadcrumb takes you back to that page. The **JClaw** crumb is always home (the [Dashboard](/)).
:::

:::note Beta software
JClaw is still pre-1.0. UI labels and behaviors may shift between releases. If something you see doesn't match what this guide says, the **Feedback** link in the sidebar is the fastest way to flag it.
:::
