# Prompts

The **Prompts Library** is your personal collection of saved, reusable prompts — the paragraphs you keep re-typing into [Chat](/chat). Save one once, then **Run** it into the composer whenever you need it, tweaking before you send. The [Prompts](/prompts) page is where you build, organize, search, and share that collection.

Reach for a saved prompt when you have a phrasing that works — a code-review rubric, a summarization style, a brainstorming kickoff — and you'd rather pick it from a shelf than reconstruct it each time. Prompts are operator-level: one library, shared across all your agents (not scoped per agent or per conversation).

## What a prompt is

Each prompt is a **title** plus the **prompt text** itself, filed under exactly one **category** and tagged with any number of free-form **tags**:

| Field        | Meaning                                                                                                   |
|--------------|-----------------------------------------------------------------------------------------------------------|
| **Title**    | Short label shown on the card and matched by search. Required.                                             |
| **Content**  | The prompt text that gets loaded into the composer when you Run it. Required.                              |
| **Category** | Exactly one, from a fixed set: **Coding, Writing, Analysis, Creative, Business, Custom**. The scannable, filterable axis. |
| **Tags**     | Optional, free-form, comma-separated. The unlimited cross-cutting axis — your own labels.                 |

The two axes are deliberately different. **Categories** are a closed taxonomy (not editable) so grouping and filtering stay consistent; **tags** are a folksonomy for whatever personal labels you want. That's why there's no "custom category" beyond the built-in **Custom** bucket — open-ended organization is what tags are for.

## The Prompts page

The [Prompts](/prompts) page is a grid of prompt cards. In the **All** view they're grouped into a labelled section per category; pick a single category to see just that one.

- **Category pills** — filter the grid by category. Each pill carries an absolute count of prompts in that category (whole library, not the current search).
- **Search** — the search box filters live across each prompt's **title, text, and tags**.
- **Run / Edit / Delete** — every card's actions (see below).
- **New prompt · Import · Export** — the toolbar, top-left.

## Creating a prompt

Click **New prompt**. Creation is a two-step flow, but you can stop after either step:

1. **Describe it** — type a one-line description of what you want the prompt to do and hit **Generate**. An LLM drafts a title, category, prompt text, and tags from your description.
2. **Review** — the generated fields pre-fill the same form you'd fill by hand. Edit anything, then **Save**.

Prefer to write it yourself? Just fill the form in step 2 directly — Generate is optional.

:::note Generate needs a provider
The **Generate** step calls your **main agent's** model, so it only works once that agent has a working LLM provider. Without one the page reports *"Prompt generation is unavailable — configure an LLM provider for the main agent first."* — set one up under [Settings → LLM Providers](/guide#settings). Saving prompts by hand needs no provider.
:::

## Running a prompt

**Run** (on a card) is the whole point of the library: it opens [Chat](/chat) with the prompt's text pre-loaded into the composer, ready for you to edit before sending. It does **not** send automatically — you get the last word. (Under the hood it's the same `?compose=` hand-off the [Apps](/guide#apps) page uses, so no special chat wiring is involved.)

The active agent is whichever one you have selected in Chat — a saved prompt carries text, not an agent binding, so the same prompt works with any agent.

## Editing and deleting

The pencil on a card reopens the form pre-filled; **Save** updates the prompt in place (its *updated* time bumps, and the library re-sorts newest-edited first). The trash button deletes it behind a confirmation — deletion can't be undone.

## Import and export

Your whole library is portable as a single JSON document:

- **Export** downloads `jclaw-prompts.json` — every prompt, in one file.
- **Import** reads such a file back. After you pick it, choose a mode:
  - **Merge** — appends the imported prompts to your existing library.
  - **Replace all** — wipes the current library first, then imports.

Import accepts either a full export document or a bare JSON array of prompts, so a hand-authored list works too.

:::gotcha Replace is destructive
**Replace all** deletes every prompt you currently have before importing — there's no undo. Reach for **Merge** unless you truly mean to start the library over from the imported file.
:::

## Where to go next

- [Chat](/guide#chat) — where every prompt you Run lands for you to edit and send.
- [Agents](/guide#agents) — the agents a prompt runs against; any prompt works with any of them.
- [Settings](/guide#settings) — configure the LLM provider that powers the **Generate** step.
