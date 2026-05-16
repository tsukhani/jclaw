# Getting Started

Welcome to JClaw. This page orients you to what's where and points you at the deeper sections of the guide when you're ready.

## What is JClaw?

JClaw is a workbench for building, running, and observing AI agents. You configure agents (their model, system prompt, and tools), give them work to do through [Chat](/chat) or external channels, and watch what they do across the rest of the app.

Everything you create — agents, conversations, tasks, subagent runs — is **scoped to your login**. You can't see another operator's resources, and they can't see yours.

## Your first five minutes

1. **Set a theme.** The toggle is in the top-right of every page (system / light / dark).
2. **Visit [Settings](/settings) and add at least one model provider.** Without an API key (or a local provider like Ollama configured), no agent can answer. See the [Settings](/guide#settings) section of this guide for what each provider needs.
3. **Visit [Agents](/agents) and create or enable an agent.** Pick a model, write a short system prompt, and turn on whatever tools you need. The default agent template is a sensible starting point.
4. **Open [Chat](/chat), pick that agent in the sidebar, and say hello.** Your conversation appears in the sidebar and you can come back to it later.

That's the minimum loop. The rest of this guide is how to do more — schedule background work, fan out subagents, plug in external chat surfaces, install skills.

## The sidebar at a glance

The left sidebar is grouped by intent:

| Group | What lives there |
| --- | --- |
| **Chat** | [Chat](/chat) (live conversations), [Channels](/channels) (external chat surfaces like Slack), [Conversations](/conversations) (every prior thread). |
| **Ops** | [Agents](/agents), [Subagents](/subagents), [Tasks](/tasks), [Skills](/skills), [Tools](/tools), [MCP Servers](/mcp-servers). |
| **Admin** | [Settings](/settings), [Logs](/logs). |
| **Help** | Feedback, Guided Tour, this **User Guide**. |

A green pip at the bottom of the sidebar shows the API status. Red means the backend can't be reached — most pages will fail until it recovers.

## The Guided Tour

The first time you sign in, JClaw offers a short on-rails tour that highlights the main pages in sequence. You can re-launch it any time from the sidebar under **Help → Guided Tour**.

The tour is fast (a few minutes) and complementary to this guide:

- **Guided Tour** — "where is everything?"
- **User Guide** (this) — "how do I use it?"

## Where to go next

- New to agents? Start with [Agents](/guide#agents) and then [Chat](/guide#chat).
- Want background or recurring work? Read [Tasks](/guide#tasks) and [Subagents](/guide#subagents).
- Connecting an external chat surface (Slack, Telegram, WhatsApp)? Read [Conversations & Channels](/guide#conversations-and-channels).
- Plugging in tools, skills, or external MCP servers? Read [Skills, Tools & MCP Servers](/guide#skills-tools-mcp).
- Configuring providers, API keys, performance caps? Read [Settings](/guide#settings).

:::tip
Every page title in the top breadcrumb takes you back to that page. The **JClaw** crumb is always home (the [Dashboard](/)).
:::

:::note Alpha software
JClaw is still pre-1.0. UI labels and behaviors may shift between releases. If something you see doesn't match what this guide says, the **Feedback** link in the sidebar is the fastest way to flag it.
:::
