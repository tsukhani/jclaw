# Prompts

The **Prompts Library** is your personal collection of saved, reusable prompts: the paragraphs you keep re-typing into [Chat](/chat). Save one once, then use **Run** to load it into the composer whenever you need it. You can adjust it before you send. The [Prompts](/prompts) page is where you build, organize, search, and share that collection.

Reach for a saved prompt when you have a phrasing that works: a code-review rubric, a summarization style, a brainstorming kickoff. Use one when you would rather pick it from a shelf than rebuild it each time. The library is yours: you can run any prompt in it with any agent. A prompt can also belong to one of your agents (see [Prompts an agent owns](/guide#prompts-agent-prompts)).

## What a prompt is

Each prompt is a **title** plus the **prompt text** itself. It is filed under exactly one **category** and tagged with any number of free-form **tags**. It also has one **owner**:

| Field | Meaning |
| --- | --- |
| **Title** | Short label shown on the card and matched by search. Required. |
| **Prompt text** | The text loaded into the composer when you Run it. Required. |
| **Category** | Exactly one, from a fixed set: **Coding, Writing, Analysis, Creative, Business, Custom**. This is the scannable, filterable axis. |
| **Tags** | Optional, free-form, comma-separated. This is the unlimited cross-cutting axis: your own labels. |
| **Owner** | You, or one of your agents. You pick it when you create the prompt, and you can change it when you edit the prompt. |

The two axes are deliberately different. **Categories** are a closed taxonomy (they are not editable), so grouping and filtering stay consistent. **Tags** are a folksonomy for whatever personal labels you want. That is why there is no "custom category" beyond the built-in **Custom** bucket: open-ended organization is what tags are for.

## The Prompts page

The [Prompts](/prompts) page is a grid of prompt cards. In the **All** view, the cards are grouped into a labeled section per category. Pick a single category to see just that one. A library with no prompts at all is given a set of sample prompts when JClaw starts. A library that holds any prompt is left alone. So, if you empty it entirely, the samples come back at the next restart.

- **Category pills**: filter the grid by category. Each pill carries an absolute count of prompts in that category (whole library, not the current search).
- **Search**: the search box filters live across each prompt's **title, text, and tags**.
- **Copy / Edit / Delete / Run**: the actions on every card. **Copy** puts the prompt text on your clipboard. The others are covered below.
- **Agent: name**: a line on the card of a prompt that an agent owns. A prompt of your own has no such line.
- **Export · Import · New prompt**: the toolbar, left-aligned below the intro text.

## Creating a prompt

Click **New prompt**. Creation is a two-step flow:

1. **Describe it**: type a one-line description of what you want the prompt to do, and hit **Generate**. An LLM drafts a title, category, prompt text, and tags from your description.
2. **Review**: the generated fields pre-fill the form. Edit anything, pick the **Owner**, then click **Create**.

The **Owner** field decides who the new prompt belongs to. It starts at **You (the operator)**, which makes the prompt yours. To give the prompt to an agent, pick that agent's name from the list. Under the field, the form says: *"An agent that owns a prompt can read, change and delete it. You can change the owner later, when you edit the prompt."*

For a new prompt, the form opens only after **Generate**. To add a prompt without it, write it in a JSON file and use **Import** (see below).

:::note Generate needs a provider
The **Generate** step calls your **main agent's** model. So it only works once that agent has a working LLM provider. Without one, the page reports *"Prompt generation is unavailable — configure an LLM provider for the main agent first."* Set one up under [Settings → LLM Providers](/guide#settings). Editing a saved prompt, and **Import**, need no provider.
:::

## Running a prompt

**Run** (on a card) is the whole point of the library. It opens [Chat](/chat) with the prompt's text pre-loaded into the composer, ready for you to edit before sending. It does **not** send automatically. You get the last word. (Under the hood, it is the same `?compose=` hand-off the [Apps](/guide#apps) page uses, so no special chat wiring is involved.)

The active agent is whichever one you have selected in Chat. A saved prompt carries text, not an agent binding, so the same prompt works with any agent.

You can also reach the library from the chat composer with the `/prompt` slash command. `/prompt <search words>` picks a saved prompt that matches those words. On the web, it prefills the composer just like **Run**. On Telegram and Slack, the bot replies with the prompt text for you to copy, because a bot there cannot prefill a message box. Off the web, it answers only the binding's owner. So a Slack binding with no owner user id refuses it, and so does WhatsApp, where no sender counts as the owner (see [Slash commands](/guide#chat-slash-commands)). On Slack, type it as `!prompt`.

## Editing and deleting

The pencil on a card reopens the form, pre-filled. **Save** updates the prompt in place. Its *updated* time bumps, and the library re-sorts newest-edited first. The trash button deletes the prompt behind a confirmation. Deletion cannot be undone.

### Changing the owner {#change-owner}

In the edit form, the **Owner** field is the same list as in the new prompt form. It starts at the prompt's current owner. If that owner is an agent the list does not show, such as a subagent, the form adds it to the list. Under the field, the form says: *"An agent that owns a prompt can read, change and delete it. To give this prompt to another owner, pick the owner and click Save."*

- To give the prompt to an agent, pick that agent's name.
- To make the prompt yours, pick **You (the operator)**.
- If you do not touch the field, **Save** leaves the owner as it is.

After the change, the new owner can list, read, change and delete the prompt. The agent that owned it before cannot reach it any more, unless that agent is the Main Agent, which reaches every prompt.

Only you and the Main Agent can change the owner of a prompt. Any other agent cannot give its prompt away, and cannot take a prompt of yours or of another agent.

## Prompts an agent owns {#agent-prompts}

A prompt belongs to an agent in one of three ways:

- You picked that agent as the **Owner**, when you created the prompt or when you edited it (see [Changing the owner](/guide#prompts-change-owner)).
- An agent saved the prompt through JClaw's API. The Main Agent can name any agent as the owner of a prompt it saves, as you can. A prompt that any other agent saves is always its own.
- The Main Agent changed the owner of a saved prompt to that agent through JClaw's API, as you can in the edit form.

The card of such a prompt shows **Agent:** and the agent's name. You see every prompt on this page, whoever owns it, and you can run, edit or delete any of them. So can the Main Agent.

Any other agent sees only the prompts it owns. It can list, read, change and delete those, whether it saved them itself, or they were created for it or given to it. It cannot list, read, change or delete a prompt of yours or of another agent.

When you delete an agent, the prompts it owned stay in the library. They become yours, and the **Agent:** line goes away.

## Import and export

Your whole library is portable as a single JSON document:

- **Export** downloads `jclaw-prompts.json`: every prompt, in one file.
- **Import** reads such a file back. After you pick it, choose a mode:
  - **Merge**: appends the imported prompts to your existing library.
  - **Replace all**: wipes the current library first, then imports.

Import accepts either a full export document or a bare JSON array of prompts. So, a hand-authored list works too. An export holds every prompt, the ones your agents own included, but not who owns each one. An imported prompt is always yours.

:::gotcha Replace is destructive
**Replace all** deletes every prompt in the library before importing, the ones your agents own included. There is no undo. Use **Merge** unless you truly mean to start the library over from the imported file.
:::

## Where to go next

- [Chat](/guide#chat): where every prompt you Run lands, for you to edit and send.
- [Agents](/guide#agents): the agents a prompt runs against. Any prompt works with any of them.
- [Settings](/guide#settings): configure the LLM provider that powers the **Generate** step.