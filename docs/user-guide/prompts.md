# Prompts

The **Prompts Library** is your personal collection of saved, reusable prompts: the paragraphs you keep re-typing into [Chat](/chat). Save one once, then use **Run** to load it into the composer whenever you need it. You can adjust it before you send. The [Prompts](/prompts) page is where you build, organize, search, and share that collection.

Reach for a saved prompt when you have a phrasing that works: a code-review rubric, a summarization style, a brainstorming kickoff. Use one when you would rather pick it from a shelf than rebuild it each time. Prompts are operator-level: one library is shared across all your agents, not scoped per agent or per conversation.

## What a prompt is

Each prompt is a **title** plus the **prompt text** itself. It is filed under exactly one **category** and tagged with any number of free-form **tags**:

| Field | Meaning |
| --- | --- |
| **Title** | Short label shown on the card and matched by search. Required. |
| **Prompt text** | The text loaded into the composer when you Run it. Required. |
| **Category** | Exactly one, from a fixed set: **Coding, Writing, Analysis, Creative, Business, Custom**. This is the scannable, filterable axis. |
| **Tags** | Optional, free-form, comma-separated. This is the unlimited cross-cutting axis: your own labels. |

The two axes are deliberately different. **Categories** are a closed taxonomy (they are not editable), so grouping and filtering stay consistent. **Tags** are a folksonomy for whatever personal labels you want. That is why there is no "custom category" beyond the built-in **Custom** bucket: open-ended organization is what tags are for.

## The Prompts page

The [Prompts](/prompts) page is a grid of prompt cards. In the **All** view, the cards are grouped into a labeled section per category. Pick a single category to see just that one. A library with no prompts at all is given a set of sample prompts when JClaw starts. A library that holds any prompt is left alone. So, if you empty it entirely, the samples come back at the next restart.

- **Category pills**: filter the grid by category. Each pill carries an absolute count of prompts in that category (whole library, not the current search).
- **Search**: the search box filters live across each prompt's **title, text, and tags**.
- **Copy / Edit / Delete / Run**: the actions on every card. **Copy** puts the prompt text on your clipboard. The others are covered below.
- **Export · Import · New prompt**: the toolbar, left-aligned below the intro text.

## Creating a prompt

Click **New prompt**. Creation is a two-step flow, but you can stop after either step:

1. **Describe it**: type a one-line description of what you want the prompt to do, and hit **Generate**. An LLM drafts a title, category, prompt text, and tags from your description.
2. **Review**: the generated fields pre-fill the same form you would fill by hand. Edit anything, then **Save**.

Prefer to write it yourself? Fill the form in step 2 directly. Generate is optional.

:::note Generate needs a provider
The **Generate** step calls your **main agent's** model. So it only works once that agent has a working LLM provider. Without one, the page reports *"Prompt generation is unavailable — configure an LLM provider for the main agent first."* Set one up under [Settings → LLM Providers](/guide#settings). Saving prompts by hand needs no provider.
:::

## Running a prompt

**Run** (on a card) is the whole point of the library. It opens [Chat](/chat) with the prompt's text pre-loaded into the composer, ready for you to edit before sending. It does **not** send automatically. You get the last word. (Under the hood, it is the same `?compose=` hand-off the [Apps](/guide#apps) page uses, so no special chat wiring is involved.)

The active agent is whichever one you have selected in Chat. A saved prompt carries text, not an agent binding, so the same prompt works with any agent.

You can also reach the library from the chat composer with the `/prompt` slash command. `/prompt <search words>` picks a saved prompt that matches those words. On the web, it prefills the composer just like **Run**. On Telegram and Slack, the bot replies with the prompt text for you to copy, because a bot there cannot prefill a message box. Off the web, it answers only the binding's owner. So a Slack binding with no owner user id refuses it, and so does WhatsApp, where no sender counts as the owner (see [Slash commands](/guide#chat-slash-commands)). On Slack, type it as `!prompt`.

## Editing and deleting

The pencil on a card reopens the form, pre-filled. **Save** updates the prompt in place. Its *updated* time bumps, and the library re-sorts newest-edited first. The trash button deletes the prompt behind a confirmation. Deletion cannot be undone.

## Import and export

Your whole library is portable as a single JSON document:

- **Export** downloads `jclaw-prompts.json`: every prompt, in one file.
- **Import** reads such a file back. After you pick it, choose a mode:
  - **Merge**: appends the imported prompts to your existing library.
  - **Replace all**: wipes the current library first, then imports.

Import accepts either a full export document or a bare JSON array of prompts. So, a hand-authored list works too.

:::gotcha Replace is destructive
**Replace all** deletes every prompt you currently have before importing. There is no undo. Use **Merge** unless you truly mean to start the library over from the imported file.
:::

## Where to go next

- [Chat](/guide#chat): where every prompt you Run lands, for you to edit and send.
- [Agents](/guide#agents): the agents a prompt runs against. Any prompt works with any of them.
- [Settings](/guide#settings): configure the LLM provider that powers the **Generate** step.