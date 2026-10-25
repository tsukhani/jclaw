---
name: writing-standards
description: >-
  Apply ASD-STE100 (Simplified Technical English) and ISO 24495-1 (plain language) by content type. Use when writing or editing technical and maintenance procedures, contracts and legal text, training and onboarding material, or general documentation.
metadata:
  version: "1.0.0"
  author: main
  jclaw-icon: "📝"
  jclaw-tools: ""
  jclaw-commands: ""
---
# Writing standards

Route written output through ASD-STE100 and ISO 24495-1 depending on the content type, instead of applying one standard everywhere. This skill is conditional: it activates when the request is to write or edit documentation, procedures, contracts, or similar content. It does not override the unconditional humanized-writing rules already in AGENT.md; those apply to all output first, then this skill adds the content-type-specific discipline on top.

## Step 1: classify the content type

Read the request and classify the output into one of these types. The classification drives everything else.

| Content type | Standard to apply | How strict |
| --- | --- | --- |
| Technical / maintenance procedures (aerospace, defense, S1000D, ATA) | ASD-STE100 (full) | Very strict, near-mechanical |
| Contracts, legal, government, forms, T&C | ISO 24495-1 | Moderate, judgment-based |
| Training and onboarding material | ISO 24495-1 with light STE discipline | Moderate |
| Marketing, customer-facing, general reports | ISO 24495-1 principles only | Light |
| Casual chat, internal notes, one-line replies | Neither (skip this skill) | None |

If the content type is ambiguous, ask the user, or default to ISO 24495-1 principles only.

## Step 2: shared baseline rules

These apply to every content type except casual chat. They are the overlap between the two standards.

- Write short sentences (aim for 20 words or fewer).
- One idea per sentence; one instruction per step.
- Use active voice and direct, concrete verbs.
- Address the reader as "you".
- Use the imperative for instructions.
- Use simple, common words. Avoid jargon and buzzwords.
- Keep structure clear: headings, numbered steps, short paragraphs.
- No contractions ("do not", not "don't").

## Step 3: ASD-STE100 rule set (technical procedures only)

Apply all of these when the content type is a technical or maintenance procedure.

Vocabulary:

- Use only approved STE words from the STE dictionary. Reject or replace words not on the approved list.
- One word carries one meaning. Use the approved meaning only.
- Use one approved technical name per concept, consistently.
- Replace vague or long verbs with approved short ones: "make sure" not "ensure", "use" not "utilize", "start" not "commence", "do" not "perform", "remove" not "eliminate".
- Use "before you" or "after you" instead of "prior to" or "subsequent to".

Grammar and structure:

- Maximum sentence length: 20 words for procedural sentences, 25 for descriptive.
- One instruction per sentence. Never chain two actions with "and".
- Tenses: simple present, simple past, simple future, and present progressive only. Present perfect is allowed for a limited set of verbs. No other tenses.
- Do not use gerunds (-ing) as nouns. Use "the" plus noun instead ("the test", not "testing").
- Use articles ("a", "an", "the") where they clarify meaning.
- Write instructions in the imperative: "Remove the panel."

Style:

- Break long procedures into numbered steps.
- Put a warning or caution before the step it applies to.
- Use "do not" for prohibitions, never "never".

## Step 4: ISO 24495-1 rule set (contracts, legal, training, general)

Apply these when the content type is contracts, legal, government, training, or general documentation.

The four governing principles (ISO 24495-1): the reader gets what they need (relevant), can find it (findable), understands it (understandable), and can use it (usable).

Relevance and findability:

- Lead with what the reader needs to do or know first.
- Use descriptive headings so readers can find a section without reading the whole document.
- Order information by the reader's task, not by your internal process.

Understandability:

- Use everyday words; replace legal and technical jargon with plain equivalents.
- Define any term the reader must know, the first time it appears.
- Use short sentences and short paragraphs.
- Prefer active voice. Name the actor ("you must return the form", not "the form must be returned").

Usability:

- Make the next step obvious. Give exact actions, dates, and numbers.
- Remove information the reader does not need for the task at hand.
- Use lists and tables where they make information easier to scan.

Multilingual note: ISO 24495-1 is language-neutral. When translating, apply the same principles in the target language, not a literal word-for-word translation of the English.

## Step 5: light STE discipline (training and onboarding only)

For training and onboarding material, apply the shared baseline plus these STE habits, without enforcing the full approved-word dictionary:

- One instruction per step.
- Short sentences.
- Active, imperative voice for procedures.
- "Make sure", "use", "start", "do" instead of longer equivalents.
- No gerunds as nouns.

## Step 6: verify

Before delivering, check the output against the checklist for its content type.

STE100 checklist (technical procedures):

- Every verb is an approved STE word.
- Every sentence is 20 words or fewer.
- Every step has one instruction only.
- Tenses limited to the four allowed.
- No gerunds used as nouns.
- Warnings and cautions placed before their step.

ISO 24495-1 checklist (contracts, legal, training, general):

- The main action or finding appears first.
- Headings let the reader find each section.
- Jargon is replaced or defined.
- Sentences and paragraphs are short.
- The next step is explicit and actionable.

## Edge cases

- Content that is both technical and customer-facing (for example a user guide): apply STE discipline to the procedure steps and ISO principles to the surrounding explanation.
- A request to write a one-line reply or casual note: skip this skill entirely.
- Unprompted output from a scheduled task must state the standard explicitly in the task description, since this skill only self-activates when the request signals documentation or procedure writing.

## Output location

This skill does not generate files by default. When the output is a document, write it to the workspace root and provide a markdown link as usual.

