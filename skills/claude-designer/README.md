# claude-designer

A portable design skill. Gives any Claude-powered coding tool (Claude Code, Cursor, Cline, Continue, etc.) the full workflow and aesthetic sensibility for producing thoughtful design artifacts in HTML.

---

## What this skill does

Takes "design something" requests — decks, prototypes, landing pages, animated videos, dashboards, mockups — and produces polished HTML output by:

- Asking the right questions up front
- Reading and lifting from existing design context (codebase, Figma, screenshots, brand)
- Committing to a coherent visual system
- Offering variations that span real design dimensions, not just color swaps
- Avoiding AI slop tropes (gradient heroes, glass cards, purple-to-pink, emoji in UI)
- Using ready-made scaffolds for the fiddly bits (deck scaling, device frames, animation timelines)

---

## Install

### Claude Code

Drop this whole folder into `.claude/skills/claude-designer/` inside your project, or into `~/.claude/skills/claude-designer/` for a global install. Claude Code will pick up the SKILL.md via progressive disclosure — the frontmatter tells it when to trigger.

### Cursor / other AI IDEs

Point your AI rules file (`.cursorrules`, `.windsurfrules`, `AGENTS.md`, etc.) at this folder. Copy the contents of `SKILL.md` into your rules, or reference the folder path. The reference files under `references/` can be read on demand.

### Manual / any LLM

Include the contents of `SKILL.md` in your system prompt. When a request matches the trigger description, load the relevant reference file from `references/` and any needed starter from `assets/starters/`.

---

## File structure

```
claude-designer/
├── SKILL.md              Main workflow + design philosophy (always in context)
├── README.md             This file
├── references/           Deliverable-specific guides (read on demand)
│   ├── decks.md          Slide decks
│   ├── prototypes.md     Interactive hi-fi prototypes
│   ├── animated-video.md Timeline-based motion
│   ├── design-canvas.md  Side-by-side static variations
│   ├── frontend-design.md Aesthetic direction when no brand exists
│   ├── tweaks.md         In-page variant controls
│   ├── react-setup.md    Pinned React+Babel CDN + scope gotchas
│   └── questions.md      Question banks per deliverable type
└── assets/
    └── starters/         Copy-in scaffolds
        ├── deck_stage.js
        ├── design_canvas.jsx
        ├── ios_frame.jsx
        ├── android_frame.jsx
        ├── macos_window.jsx
        ├── browser_window.jsx
        └── animations.jsx
```

---

## How it works (progressive disclosure)

1. **Metadata** — The YAML frontmatter in `SKILL.md` is always in context. It tells the LLM when to trigger.
2. **SKILL.md body** — Loaded when the skill triggers. ~300 lines of workflow + philosophy.
3. **References** — The LLM reads these on demand based on what's being built.
4. **Starters** — Copied into the user's project as scaffolds. Not read, just installed.

Total metadata cost when idle: ~150 words.

---

## Differences from running inside a dedicated design tool

This skill is designed to work in generic coding environments that have file read/write, shell access, and a browser preview. It does NOT assume:

- A proprietary "preview iframe" distinct from the user's browser
- A `done` tool that auto-surfaces the file and returns console errors
- A `fork_verifier_agent` background subagent
- A `questions_v2` native UI for batch questions
- A `copy_starter_component` tool (starters are in `assets/starters/` — copy them with normal file tools)
- A `gen_pptx` native exporter
- `show_to_user` for directing attention (just tell the user to open the file)

Where the original workflow used these, this skill falls back to:

- **Verification** → open the HTML file in a browser, check the console, manually inspect
- **Questions** → ask a batch in chat at the start; don't block on a structured form
- **Starters** → `cp assets/starters/<name> ./project/` via normal file tools
- **Showing the user** → write the file and tell them the path
- **PPTX export** → print-to-PDF from the deck's `@media print` rules; PPTX needs separate tooling

---

## When not to use this skill

- Production app code — this skill produces design artifacts, not shipping code. If the user is building a real React component library for production, standard coding practices apply, not this skill's single-file HTML approach.
- Pure logic / data tasks — no visual output = no design skill needed.
- Simple one-off answers — "what color is #3b82f6?" doesn't need the workflow.

---

## Credits

Distilled from the Claude Design system prompt, restructured for portability.
