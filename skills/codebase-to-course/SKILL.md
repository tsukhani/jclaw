---
name: codebase-to-course
description: >-
  Turn any codebase into a beautiful, interactive, single-page HTML course that teaches how the code works to non-technical vibe coders. Trigger on phrases like "turn this into a course", "explain this codebase interactively", "teach me how this code works", "make a course from this project", or "interactive tutorial from this code". Produces a self-contained HTML file with scroll-based modules, animated visualizations, embedded quizzes, and code↔plain-English side-by-side translations. Adapted from zarazhangrui/codebase-to-course.
metadata:
  version: "1.1.0"
  author: Tarun Sukhani
  jclaw-icon: "🎓"
  jclaw-tools: filesystem exec web_fetch documents
  jclaw-commands: ""
---
# Codebase-to-Course

Transform any codebase into a stunning, interactive, single-page HTML course. The output is **one self-contained `.html` file** — inline CSS and JS, only external dependency is Google Fonts — that teaches how the code works through scroll-based modules, animated visualizations, embedded quizzes, and plain-English translations.

**Output location:** write the finished course to `codebase-to-course/<project-slug>.html` at the root of the workspace, then deliver to the user with a markdown link `[project-slug.html](<codebase-to-course/project-slug.html>)`.

## First-Run Welcome

If the user invokes this skill without naming a codebase, introduce yourself:

> **I can turn any codebase into an interactive course that teaches how it works — no coding knowledge required.**
>
> Just point me at a project:
> - **A GitHub link** — e.g., "make a course from https://github.com/user/repo"
> - **A local folder in the workspace** — e.g., "turn ./my-project into a course"
>
> I'll read through the code, figure out how everything fits together, and generate a beautiful single-page HTML course with animated diagrams, plain-English code explanations, and interactive quizzes.

For a **GitHub URL**: clone into a temp directory with `exec` (`git clone <url> /tmp/<repo-slug>`) before starting analysis. For a **local path**: use it directly. Do **not** ask the user to explain the product — figure it out yourself by reading the README, entry points, and UI code.

## Who This Is For

The target learner is a **"vibe coder"** — someone who builds software by instructing AI coding tools in natural language, without a traditional CS education. They may have built this project themselves (without reading the code) or found an interesting open-source repo and want to understand how it's built.

**Assume zero technical background.** Every CS concept — variables, APIs, databases, callbacks — needs to be explained in plain language. No jargon without definition. No "as you probably know." Tone: smart friend explaining things, not a professor lecturing.

**Their goals are practical, not academic:**
- **Steer AI coding tools** better — make smarter architectural and tech-stack decisions
- **Detect when AI is wrong** — spot hallucinations, catch bad patterns
- **Intervene when AI gets stuck** — break out of bug loops, debug faster
- Build software with **production-level quality and reliability**
- Be **technically fluent** enough to talk to engineers without feeling lost
- **Acquire the vocabulary of software** — precise terms so they can describe requirements clearly to AI agents (e.g., "namespace package" instead of "shared folder thing")

**They are NOT trying to become software engineers.** They want coding as a superpower that amplifies what they're already good at.

## Why This Approach Works

This skill inverts traditional CS education. Old model: memorize concepts for years → eventually build something → finally see the point (most quit before step 3). This model: **build something first → experience it working → now understand how it works.**

The learner already has context traditional students lack — they've *used* the app, they know what it does. The course meets them where they are: *"You know that button you click? Here's what happens under the hood."* Every module answers **"why should I care?"** before "how does it work?" — and the answer is always practical.

---

## The Process

### Phase 1 — Codebase Analysis

Before writing any HTML, deeply understand the codebase. Use `filesystem` (listFiles/readFile) and `exec` (`grep`, `find`, `cat`) to read all key files, trace data flows, and map the architecture. Thoroughness here pays off.

**What to extract:**
- The main "actors" (components, services, modules) and their responsibilities
- The primary user journey — what happens end-to-end when someone uses the app
- Key APIs, data flows, and communication patterns
- Clever engineering patterns (caching, lazy loading, error handling, chunking)
- Real bugs or gotchas (if visible in git history or comments)
- The tech stack and why each piece was chosen

**Figure out what the app does yourself** by reading the README, main entry points, and UI code. The course should open by explaining what the app does in plain language before diving into how it works. The first module should start with a concrete user action: *"Imagine you paste a YouTube URL and click Analyze — here's what happens under the hood."*

### Phase 2 — Curriculum Design

Structure the course as **4–6 modules**. Only go to 7–8 if the codebase genuinely has that many distinct concepts. Fewer, better modules beat more, thinner ones.

The arc always starts from what the learner already knows (the user-facing behavior) and zooms inward. Think of it as progressively peeling back layers.

**Module menu (pick what serves the codebase — this is NOT a checklist):**

| # | Purpose | Why it matters for a vibe coder |
|---|---|---|
| 1 | "Here's what this app does, and what happens when you use it" | Grounds everything in something concrete. Trace a core user action into the code. |
| 2 | Meet the actors | Know which components exist so you can tell AI "put this logic in X, not Y" |
| 3 | How the pieces talk | Understand data flow so you can debug "it's not showing up" problems |
| 4 | The outside world (APIs, databases) | Know what's external so you can evaluate costs, rate limits, failure modes |
| 5 | The clever tricks | Learn patterns (caching, chunking, error handling) so you can request them from AI |
| 6 | When things break | Build debugging intuition so you can escape AI bug loops |
| 7 | The big picture | See the full architecture so you can decide what to build next |

**Every module must connect back to a practical skill** — steering AI, debugging, making decisions. If a module doesn't help the learner DO something better, cut it or reframe it.

**Each module contains:**
- 3–6 screens (sub-sections that flow within the module)
- At least one code↔English translation
- At least one interactive element (quiz, visualization, animation)
- 1–2 "aha!" callout boxes with universal CS insights
- A metaphor that grounds the technical concept in everyday life

**Mandatory across the whole course:**
- **Group Chat Animation** — at least one. iMessage-style conversation between components. Creatively frame at least one module as a chat between actors.
- **Message Flow / Data Flow Animation** — at least one. Step-by-step packet animation between actors.
- **Code ↔ English Translation Blocks** — at least one per module.
- **Quizzes** — at least one per module (multiple-choice, scenario, or spot-the-bug).
- **Glossary Tooltips** — on every technical term, first use per module.

**Do NOT present the curriculum for approval — just build it.** The user wants a course, not a planning document. They'll give feedback after seeing the result.

### Phase 3 — Build the HTML

Produce ONE self-contained HTML file: `<!DOCTYPE html>` → `<head>` (fonts, inline `<style>` with the full design system) → `<body>` (nav + all module sections) → inline `<script>` with all interactive-element engines. No external CSS or JS files, no build step. The file must open and work offline with only the Google Fonts CDN link.

Write the file with `filesystem.writeFile` to `codebase-to-course/<project-slug>.html`. Because the file can be large, build it with `writeFile` for the opening shell, then `appendFile` for each module and for the closing `</body></html>`. Emit one module per `appendFile` call — this keeps each tool response small and lets you author arbitrarily long courses.

### Phase 4 — Review and Deliver

Deliver the file via a markdown link: `[<slug>.html](<codebase-to-course/<slug>.html>)`. Briefly summarize in chat:
- Course title and module count
- Which "clever tricks" from the codebase you highlighted
- Invite the user to open it and request revisions

---

## Design Identity

The visual design should feel like a **beautiful developer notebook** — warm, inviting, distinctive. Non-negotiable principles:

- **Warm palette** — off-white backgrounds like aged paper, warm grays, NO cold whites or blues
- **Bold accent** — one confident accent color (vermillion, coral, teal, amber, forest — NEVER purple gradients)
- **Distinctive typography** — display font with personality (Bricolage Grotesque, or similar bold geometric face — NEVER Inter, Roboto, Arial, Space Grotesk). Body: DM Sans. Code: JetBrains Mono.
- **Generous whitespace** — modules breathe. Max 3–4 short paragraphs per screen.
- **Alternating backgrounds** — even/odd modules alternate between two warm background tones
- **Dark code blocks** — IDE-style with Catppuccin-inspired syntax highlighting on deep indigo-charcoal (`#1E1E2E`)
- **Depth without harshness** — subtle warm shadows, never pure black

When writing `<head>` and `<style>`, read `references/design-system.md` with the filesystem tool's readFile (path: `skills/codebase-to-course/references/design-system.md`) for the `:root` block to paste and the fonts `<link>`.

### Global layout rules

- Page scroll: `html { scroll-snap-type: y proximity; scroll-behavior: smooth; }` — **proximity, not mandatory**.
- Each module: `.module { min-height: 100dvh; scroll-snap-align: start; padding: var(--space-16) var(--space-6); padding-top: calc(var(--nav-height) + var(--space-12)); }` with `100vh` fallback.
- Even-numbered modules use `--color-bg`, odd-numbered use `--color-bg-warm` (alternating visual rhythm).
- Code blocks wrap — `pre, code { white-space: pre-wrap; word-break: break-word; overflow-x: hidden; }`. **Never** a horizontal scrollbar.
- Body background: subtle radial gradient with accent at ~3% opacity.

When styling dark code blocks, take the syntax-highlighting classes from `references/design-system.md`.

---

## Content Philosophy

**Show, don't tell.** Every screen is at least 50% visual. Max 2–3 sentences per text block. If something can be a diagram, animation, or interactive element — it shouldn't be a paragraph.

**Quizzes test doing, not knowing.** No "What does API stand for?" Instead: *"A user reports stale data after switching pages. Where would you look first?"* Quizzes test whether the learner can USE what they learned on a new problem.

**No recycled metaphors.** Each concept gets a metaphor that fits that specific idea. A database is a library with a card catalog. Auth is a bouncer checking IDs. Rate limiting is a nightclub with a capacity limit. **Never use the same metaphor twice. NEVER default to the restaurant metaphor — it's overused.** The best metaphors feel *inevitable* for the concept, not forced.

**Original code only.** Code snippets are **exact copies** from the real codebase — never modified, trimmed, or simplified. Choose naturally short (5–10 line) sections that illustrate the concept. The learner should be able to open the actual file and see the same code.

**Each English line in a translation block corresponds to 1–2 code lines.** Highlight the *why*, not just the *what* — "Include our API key so the server knows who we are" beats "Set the Authorization header."

---

When building an interactive element, read `references/interactive-elements.md` with the filesystem tool's readFile (path: `skills/codebase-to-course/references/interactive-elements.md`); its HTML/CSS/JS is canonical — inline it into the output file.

---

When writing the nav and progress bar (always include them) and each module `<section>`, read `references/html-template.md` with the filesystem tool's readFile (path: `skills/codebase-to-course/references/html-template.md`).

---

## Gotchas Checklist (review before handing over)

- [ ] `scroll-snap-type` is **`y proximity`**, not `mandatory` (mandatory traps scrolling)
- [ ] Every module has `min-height: 100dvh` with `100vh` fallback
- [ ] Even/odd modules alternate between `--color-bg` and `--color-bg-warm`
- [ ] Every technical term on first use per module is wrapped in `.term` with `data-definition`
- [ ] Tooltips are `position: fixed` and appended to `document.body` (not clipped by `overflow: hidden`)
- [ ] At least one group chat animation somewhere in the course
- [ ] At least one flow animation somewhere in the course
- [ ] Every module has at least one `.translation-block` and one quiz
- [ ] No apostrophes / single quotes inside `data-steps` labels (use `&apos;` or flip delimiters)
- [ ] Code snippets are exact copies from the codebase — no trimming, no simplification
- [ ] No recycled metaphors across modules; no restaurant metaphor
- [ ] Accent color is bold and confident — NOT a purple gradient
- [ ] Display font is Bricolage Grotesque (or equivalent personality face) — NOT Inter / Roboto / Arial / Space Grotesk
- [ ] Body text max 3–4 short paragraphs per screen
- [ ] Every quiz explanation tells the learner *why*, not just right/wrong
- [ ] Nav dots count === module count
- [ ] Google Fonts `<link>` preconnects and the full family URL are in `<head>`

---

## Delivery

After running Phase 3 and passing the gotchas checklist, reply to the user with:

1. A markdown download link: `[<slug>.html](<codebase-to-course/<slug>.html>)`
2. A two-line summary: course title, module count, one interesting pattern from the codebase you highlighted
3. An invitation: "Open it and let me know what you'd like to tweak."

---

*Adapted from [zarazhangrui/codebase-to-course](https://github.com/zarazhangrui/codebase-to-course) for JClaw. Original skill by Zara.*