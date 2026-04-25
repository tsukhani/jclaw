---
name: codebase-to-course
description: Turn any codebase into a beautiful, interactive, single-page HTML course that teaches how the code works to non-technical vibe coders. Trigger on phrases like "turn this into a course", "explain this codebase interactively", "teach me how this code works", "make a course from this project", or "interactive tutorial from this code". Produces a self-contained HTML file with scroll-based modules, animated visualizations, embedded quizzes, and code↔plain-English side-by-side translations. Adapted from zarazhangrui/codebase-to-course.
version: 1.0.0
author: main
tools: [filesystem, exec, web_fetch, documents]
commands: []
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

### Design System (paste the `:root` block into the `<style>` tag verbatim, adapt accent color as needed)

```css
:root {
  /* BACKGROUNDS */
  --color-bg:             #FAF7F2;
  --color-bg-warm:        #F5F0E8;
  --color-bg-code:        #1E1E2E;
  --color-text:           #2C2A28;
  --color-text-secondary: #6B6560;
  --color-text-muted:     #9E9790;
  --color-border:         #E5DFD6;
  --color-border-light:   #EEEBE5;
  --color-surface:        #FFFFFF;
  --color-surface-warm:   #FDF9F3;

  /* ACCENT — pick ONE. Default: vermillion.
     Alternatives: coral (#E06B56), teal (#2A7B9B), amber (#D4A843), forest (#2D8B55). */
  --color-accent:         #D94F30;
  --color-accent-hover:   #C4432A;
  --color-accent-light:   #FDEEE9;
  --color-accent-muted:   #E8836C;

  /* SEMANTIC */
  --color-success:        #2D8B55;
  --color-success-light:  #E8F5EE;
  --color-error:          #C93B3B;
  --color-error-light:    #FDE8E8;
  --color-info:           #2A7B9B;
  --color-info-light:     #E4F2F7;

  /* ACTOR COLORS — assign to main components for chat bubbles & diagrams */
  --color-actor-1: #D94F30;   /* vermillion */
  --color-actor-2: #2A7B9B;   /* teal */
  --color-actor-3: #7B6DAA;   /* muted plum */
  --color-actor-4: #D4A843;   /* golden */
  --color-actor-5: #2D8B55;   /* forest */

  /* FONTS */
  --font-display: 'Bricolage Grotesque', Georgia, serif;
  --font-body:    'DM Sans', -apple-system, sans-serif;
  --font-mono:    'JetBrains Mono', 'Fira Code', Consolas, monospace;

  /* TYPE SCALE (1.25 ratio) */
  --text-xs: .75rem; --text-sm: .875rem; --text-base: 1rem;
  --text-lg: 1.125rem; --text-xl: 1.25rem; --text-2xl: 1.5rem;
  --text-3xl: 1.875rem; --text-4xl: 2.25rem; --text-5xl: 3rem; --text-6xl: 3.75rem;

  /* LINE HEIGHTS */
  --leading-tight: 1.15; --leading-snug: 1.3;
  --leading-normal: 1.6; --leading-loose: 1.8;

  /* SPACING */
  --space-1: .25rem; --space-2: .5rem; --space-3: .75rem; --space-4: 1rem;
  --space-5: 1.25rem; --space-6: 1.5rem; --space-8: 2rem; --space-10: 2.5rem;
  --space-12: 3rem; --space-16: 4rem; --space-20: 5rem; --space-24: 6rem;

  /* LAYOUT */
  --content-width: 800px;
  --content-width-wide: 1000px;
  --nav-height: 50px;
  --radius-sm: 8px; --radius-md: 12px; --radius-lg: 16px; --radius-full: 9999px;

  /* SHADOWS — warm-tinted, never pure black */
  --shadow-sm: 0 1px 2px rgba(44,42,40,.05);
  --shadow-md: 0 4px 12px rgba(44,42,40,.08);
  --shadow-lg: 0 8px 24px rgba(44,42,40,.10);
  --shadow-xl: 0 16px 48px rgba(44,42,40,.12);

  /* ANIMATION */
  --ease-out: cubic-bezier(.16,1,.3,1);
  --ease-in-out: cubic-bezier(.65,0,.35,1);
  --duration-fast: 150ms; --duration-normal: 300ms; --duration-slow: 500ms;
  --stagger-delay: 120ms;
}
```

### Fonts `<link>` to put in `<head>`

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400;12..96,600;12..96,700;12..96,800&family=DM+Sans:ital,opsz,wght@0,9..40,300;0,9..40,400;0,9..40,500;0,9..40,600;0,9..40,700;1,9..40,400;1,9..40,500&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
```

### Global layout rules

- Page scroll: `html { scroll-snap-type: y proximity; scroll-behavior: smooth; }` — **proximity, not mandatory**.
- Each module: `.module { min-height: 100dvh; scroll-snap-align: start; padding: var(--space-16) var(--space-6); padding-top: calc(var(--nav-height) + var(--space-12)); }` with `100vh` fallback.
- Even-numbered modules use `--color-bg`, odd-numbered use `--color-bg-warm` (alternating visual rhythm).
- Code blocks wrap — `pre, code { white-space: pre-wrap; word-break: break-word; overflow-x: hidden; }`. **Never** a horizontal scrollbar.
- Body background: subtle radial gradient with accent at ~3% opacity.

### Syntax highlighting (Catppuccin-inspired, for dark code blocks)

```css
.code-keyword  { color: #CBA6F7; }  /* purple — if/else/return/function */
.code-string   { color: #A6E3A1; }  /* green — "strings" */
.code-function { color: #89B4FA; }  /* blue — function names */
.code-comment  { color: #6C7086; }  /* muted gray — // comments */
.code-number   { color: #FAB387; }  /* peach — numbers */
.code-property { color: #F9E2AF; }  /* yellow — object keys */
.code-operator { color: #94E2D5; }  /* teal — =, =>, + */
.code-tag      { color: #F38BA8; }  /* pink — HTML tags */
.code-attr     { color: #F9E2AF; }  /* yellow — HTML attributes */
.code-value    { color: #A6E3A1; }  /* green — attribute values */
```

---

## Content Philosophy

**Show, don't tell.** Every screen is at least 50% visual. Max 2–3 sentences per text block. If something can be a diagram, animation, or interactive element — it shouldn't be a paragraph.

**Quizzes test doing, not knowing.** No "What does API stand for?" Instead: *"A user reports stale data after switching pages. Where would you look first?"* Quizzes test whether the learner can USE what they learned on a new problem.

**No recycled metaphors.** Each concept gets a metaphor that fits that specific idea. A database is a library with a card catalog. Auth is a bouncer checking IDs. Rate limiting is a nightclub with a capacity limit. **Never use the same metaphor twice. NEVER default to the restaurant metaphor — it's overused.** The best metaphors feel *inevitable* for the concept, not forced.

**Original code only.** Code snippets are **exact copies** from the real codebase — never modified, trimmed, or simplified. Choose naturally short (5–10 line) sections that illustrate the concept. The learner should be able to open the actual file and see the same code.

**Each English line in a translation block corresponds to 1–2 code lines.** Highlight the *why*, not just the *what* — "Include our API key so the server knows who we are" beats "Set the Authorization header."

---

## Interactive Element Patterns

All HTML/CSS/JS below is canonical. Inline them directly into the single output file.

### 1. Code ↔ English Translation Block (mandatory per module)

```html
<div class="translation-block animate-in">
  <div class="translation-code">
    <span class="translation-label">CODE</span>
    <pre><code><span class="code-line"><span class="code-keyword">const</span> response = <span class="code-keyword">await</span> <span class="code-function">fetch</span>(url, {</span>
<span class="code-line">  <span class="code-property">method</span>: <span class="code-string">'POST'</span>,</span>
<span class="code-line">  <span class="code-property">headers</span>: { <span class="code-string">'Authorization'</span>: apiKey }</span>
<span class="code-line">});</span></code></pre>
  </div>
  <div class="translation-english">
    <span class="translation-label">PLAIN ENGLISH</span>
    <div class="translation-lines">
      <p class="tl">Send a request to the URL and wait for a response.</p>
      <p class="tl">We're sending data (POST), not just asking for it (GET).</p>
      <p class="tl">Include our API key so the server knows who we are.</p>
      <p class="tl">End of the request setup.</p>
    </div>
  </div>
</div>
```

```css
.translation-block { display: grid; grid-template-columns: 1fr 1fr; gap: 0;
  border-radius: var(--radius-md); overflow: hidden; box-shadow: var(--shadow-md);
  margin: var(--space-8) 0; }
.translation-code { background: var(--color-bg-code); color: #CDD6F4;
  padding: var(--space-6); font-family: var(--font-mono); font-size: var(--text-sm);
  line-height: 1.7; position: relative; overflow-x: hidden; }
.translation-code pre, .translation-code code {
  white-space: pre-wrap; word-break: break-word; overflow-x: hidden; }
.translation-english { background: var(--color-surface-warm); padding: var(--space-6);
  font-size: var(--text-sm); line-height: 1.7;
  border-left: 3px solid var(--color-accent); }
.translation-label { position: absolute; top: var(--space-2); right: var(--space-3);
  font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .1em; opacity: .5; }
@media (max-width: 768px) {
  .translation-block { grid-template-columns: 1fr; }
  .translation-english { border-left: none; border-top: 3px solid var(--color-accent); }
}
```

### 2. Multiple-Choice Quiz (mandatory per module)

```html
<div class="quiz-container" id="quiz-module3">
  <div class="quiz-question-block"
       data-correct="option-b"
       data-explanation-right="Exactly — because X is responsible for Y in this architecture."
       data-explanation-wrong="Not quite. Think about where Y lives in the codebase...">
    <h3 class="quiz-question">Question text here?</h3>
    <div class="quiz-options">
      <button class="quiz-option" data-value="option-a" onclick="selectOption(this)">
        <div class="quiz-option-radio"></div><span>Answer A</span>
      </button>
      <button class="quiz-option" data-value="option-b" onclick="selectOption(this)">
        <div class="quiz-option-radio"></div><span>Answer B (correct)</span>
      </button>
      <button class="quiz-option" data-value="option-c" onclick="selectOption(this)">
        <div class="quiz-option-radio"></div><span>Answer C</span>
      </button>
    </div>
    <div class="quiz-feedback"></div>
  </div>
  <button class="quiz-check-btn" onclick="checkQuiz('quiz-module3')">Check Answers</button>
  <button class="quiz-reset-btn" onclick="resetQuiz('quiz-module3')">Try Again</button>
</div>
```

JS engine (put in the inline `<script>`):

```javascript
window.selectOption = function(btn) {
  const block = btn.closest('.quiz-question-block');
  block.querySelectorAll('.quiz-option').forEach(o => o.classList.remove('selected'));
  btn.classList.add('selected');
};
window.checkQuiz = function(id) {
  document.getElementById(id).querySelectorAll('.quiz-question-block').forEach(block => {
    const selected = block.querySelector('.quiz-option.selected');
    const feedback = block.querySelector('.quiz-feedback');
    if (!selected) return;
    const ok = selected.dataset.value === block.dataset.correct;
    block.querySelectorAll('.quiz-option').forEach(o => o.classList.remove('correct','incorrect'));
    selected.classList.add(ok ? 'correct' : 'incorrect');
    feedback.textContent = ok ? block.dataset.explanationRight : block.dataset.explanationWrong;
    feedback.className = 'quiz-feedback show ' + (ok ? 'success' : 'error');
  });
};
window.resetQuiz = function(id) {
  document.getElementById(id).querySelectorAll('.quiz-option').forEach(o =>
    o.classList.remove('selected','correct','incorrect'));
  document.getElementById(id).querySelectorAll('.quiz-feedback').forEach(f => f.className = 'quiz-feedback');
};
```

### 3. Group Chat Animation (at least one per course)

iMessage-style conversation between components — messages appear one by one with typing indicators.

```html
<div class="chat-window" id="chat-module2">
  <div class="chat-messages">
    <div class="chat-message" data-msg="0" data-sender="actor-a" style="display:none">
      <div class="chat-avatar" style="background: var(--color-actor-1)">A</div>
      <div class="chat-bubble">
        <span class="chat-sender" style="color: var(--color-actor-1)">Actor A</span>
        <p>Hey Background, I need the data for this item.</p>
      </div>
    </div>
    <!-- more messages with incrementing data-msg -->
  </div>
  <div class="chat-typing" style="display:none">
    <div class="chat-avatar">?</div>
    <div class="chat-typing-dots">
      <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
    </div>
  </div>
  <div class="chat-controls">
    <button class="btn chat-next-btn">Next Message</button>
    <button class="btn chat-all-btn">Play All</button>
    <button class="btn chat-reset-btn">Replay</button>
    <span class="chat-progress"></span>
  </div>
</div>
```

JS engine — auto-initializes every `.chat-window`:

```javascript
document.querySelectorAll('.chat-window').forEach(win => {
  const msgs = [...win.querySelectorAll('.chat-message')];
  const typing = win.querySelector('.chat-typing');
  const progress = win.querySelector('.chat-progress');
  let idx = 0, playing = false;
  const showNext = () => {
    if (idx >= msgs.length) return;
    typing.style.display = 'flex';
    setTimeout(() => {
      typing.style.display = 'none';
      msgs[idx].style.display = 'flex';
      idx++;
      if (progress) progress.textContent = `${idx}/${msgs.length}`;
    }, 700);
  };
  win.querySelector('.chat-next-btn')?.addEventListener('click', showNext);
  win.querySelector('.chat-all-btn')?.addEventListener('click', () => {
    if (playing) return; playing = true;
    const tick = () => {
      if (idx >= msgs.length) { playing = false; return; }
      showNext(); setTimeout(tick, 1400);
    };
    tick();
  });
  win.querySelector('.chat-reset-btn')?.addEventListener('click', () => {
    msgs.forEach(m => m.style.display = 'none'); idx = 0; playing = false;
    if (progress) progress.textContent = `0/${msgs.length}`;
  });
});
```

### 4. Message Flow / Data Flow Animation (at least one per course)

**⚠️ Single quotes in `data-steps` labels break parsing.** Either avoid apostrophes, use `&apos;`, or flip attribute delimiters to double-quoted with escaped inner quotes.

```html
<div class="flow-animation" data-steps='[
  {"highlight":"flow-actor-1","label":"User clicks the button"},
  {"highlight":"flow-actor-1","label":"Frontend sends request","packet":true,"from":"actor-1","to":"actor-2"},
  {"highlight":"flow-actor-2","label":"Backend calls the database","packet":true,"from":"actor-2","to":"actor-3"}
]'>
  <div class="flow-actors">
    <div class="flow-actor" id="flow-actor-1"><div class="flow-actor-icon">A</div><span>Actor 1</span></div>
    <div class="flow-actor" id="flow-actor-2"><div class="flow-actor-icon">B</div><span>Actor 2</span></div>
    <div class="flow-actor" id="flow-actor-3"><div class="flow-actor-icon">C</div><span>Actor 3</span></div>
  </div>
  <div class="flow-packet"></div>
  <div class="flow-step-label">Click "Next Step" to begin</div>
  <div class="flow-controls">
    <button class="btn flow-next-btn">Next Step</button>
    <button class="btn flow-reset-btn">Restart</button>
    <span class="flow-progress"></span>
  </div>
</div>
```

JS engine:

```javascript
document.querySelectorAll('.flow-animation').forEach(flow => {
  const steps = JSON.parse(flow.dataset.steps);
  const label = flow.querySelector('.flow-step-label');
  const progress = flow.querySelector('.flow-progress');
  let idx = 0;
  const reset = () => {
    flow.querySelectorAll('.flow-actor').forEach(a => a.classList.remove('active'));
  };
  const step = () => {
    if (idx >= steps.length) return;
    reset();
    const s = steps[idx];
    const actor = flow.querySelector('#' + s.highlight);
    if (actor) actor.classList.add('active');
    label.textContent = s.label;
    idx++;
    if (progress) progress.textContent = `${idx}/${steps.length}`;
  };
  flow.querySelector('.flow-next-btn').addEventListener('click', step);
  flow.querySelector('.flow-reset-btn').addEventListener('click', () => {
    idx = 0; reset(); label.textContent = 'Click "Next Step" to begin';
    if (progress) progress.textContent = `0/${steps.length}`;
  });
});
```

```css
.flow-actor.active {
  box-shadow: 0 0 0 3px var(--color-accent), 0 0 20px rgba(217,79,48,.2);
  transform: scale(1.05);
  transition: all var(--duration-normal) var(--ease-out);
}
```

### 5. Glossary Tooltips (mandatory on every technical term, first use per module)

Mark up EVERY technical term on first use in each module (API, DOM, callback, async, endpoint, middleware, etc.). Keep definitions to 1–2 sentences in everyday language. Use a metaphor in the definition when it helps.

```html
<p>The extension uses a
  <span class="term" data-definition="A service worker is a background script that runs independently of the web page — like a behind-the-scenes assistant that's always on, even when you're not looking at the page.">service worker</span>
  to handle API calls.</p>
```

Tooltip JS — uses `position: fixed` on `document.body` so the tooltip is NEVER clipped by `overflow: hidden` ancestors (critical — translation blocks have `overflow: hidden`):

```javascript
let activeTooltip = null;
function positionTooltip(term, tip) {
  const rect = term.getBoundingClientRect();
  const tipWidth = 300;
  let left = rect.left + rect.width/2 - tipWidth/2;
  left = Math.max(8, Math.min(left, window.innerWidth - tipWidth - 8));
  tip.style.left = left + 'px';
  document.body.appendChild(tip);
  const tipHeight = tip.offsetHeight;
  if (rect.top - tipHeight - 8 < 0) {
    tip.style.top = (rect.bottom + 8) + 'px'; tip.classList.add('flip');
  } else {
    tip.style.top = (rect.top - tipHeight - 8) + 'px'; tip.classList.remove('flip');
  }
}
document.querySelectorAll('.term').forEach(term => {
  const tip = document.createElement('span');
  tip.className = 'term-tooltip';
  tip.textContent = term.dataset.definition;
  term.addEventListener('mouseenter', () => {
    if (activeTooltip && activeTooltip !== tip) { activeTooltip.classList.remove('visible'); activeTooltip.remove(); }
    positionTooltip(term, tip);
    requestAnimationFrame(() => tip.classList.add('visible'));
    activeTooltip = tip;
  });
  term.addEventListener('mouseleave', () => {
    tip.classList.remove('visible');
    setTimeout(() => { if (!tip.classList.contains('visible')) tip.remove(); }, 150);
    activeTooltip = null;
  });
  term.addEventListener('click', (e) => {
    e.stopPropagation();
    if (activeTooltip && activeTooltip !== tip) { activeTooltip.classList.remove('visible'); activeTooltip.remove(); }
    if (tip.classList.contains('visible')) { tip.classList.remove('visible'); tip.remove(); activeTooltip = null; }
    else { positionTooltip(term, tip); requestAnimationFrame(() => tip.classList.add('visible')); activeTooltip = tip; }
  });
});
document.addEventListener('click', () => {
  if (activeTooltip) { activeTooltip.classList.remove('visible'); activeTooltip.remove(); activeTooltip = null; }
});
```

```css
.term { border-bottom: 1.5px dashed var(--color-accent-muted); cursor: pointer; position: relative; }
.term:hover, .term.active { border-bottom-color: var(--color-accent); color: var(--color-accent); }
.term-tooltip {
  position: fixed;           /* CRITICAL — not absolute */
  background: var(--color-bg-code); color: #CDD6F4;
  padding: var(--space-3) var(--space-4); border-radius: var(--radius-sm);
  font-size: var(--text-sm); font-family: var(--font-body); line-height: var(--leading-normal);
  width: max(200px, min(320px, 80vw)); box-shadow: var(--shadow-lg);
  pointer-events: none; opacity: 0; transition: opacity var(--duration-fast);
  z-index: 10000;
}
.term-tooltip.visible { opacity: 1; }
```

### 6. Callout Boxes ("aha!" moments — max 2 per module)

```html
<div class="callout callout-accent">
  <div class="callout-icon">💡</div>
  <div class="callout-content">
    <strong class="callout-title">Key Insight</strong>
    <p>This pattern — splitting responsibilities into focused roles — is one of the most important ideas in software engineering. Engineers call it "separation of concerns."</p>
  </div>
</div>
```

Variants: `callout-accent` (CS insight), `callout-info` (good to know), `callout-warning` (common mistake).

### 7. "Spot the Bug" Challenge

Show code with a deliberate bug. User clicks the buggy line. Reveal explains the issue. Each `.bug-line` has `onclick="checkBugLine(this, true|false)"`; the correct line also carries class `bug-target`. Reveal feedback in `.bug-feedback`.

### 8. Other useful patterns (add when they fit)

- **Pattern/Feature Cards** (`.pattern-cards` grid) — for tech-stack or pattern highlights
- **Visual File Tree** (`.file-tree` with `.ft-folder`/`.ft-file`) — instead of paragraphs describing directory structure
- **Icon-Label Rows** (`.icon-rows`) — for bullet-point-style lists, but visual
- **Numbered Step Cards** (`.step-cards`) — for sequences
- **Interactive Architecture Diagram** (`.arch-diagram`) — full-system overview with click-to-describe components
- **Layer Toggle Demo** (`.layer-demo`) — show how HTML/CSS/JS or data/logic/UI layers stack
- **Scenario Quiz** — same engine as multiple-choice, wrapped in `.scenario-block` with longer context
- **Drag-and-Drop Matching** — for matching concepts to descriptions (must include touch handlers for mobile)

---

## Navigation & Progress (always include)

```html
<nav class="nav">
  <div class="progress-bar" role="progressbar" aria-valuenow="0"></div>
  <div class="nav-inner">
    <span class="nav-title">Course Title</span>
    <div class="nav-dots">
      <button class="nav-dot" data-target="module-1" data-tooltip="Module 1 Name" role="tab" aria-label="Module 1"></button>
      <!-- one per module -->
    </div>
  </div>
</nav>
```

JS:

```javascript
// Progress bar
const progressBar = document.querySelector('.progress-bar');
function updateProgressBar() {
  const h = document.documentElement.scrollHeight - window.innerHeight;
  progressBar.style.width = ((window.scrollY / h) * 100) + '%';
}
window.addEventListener('scroll', () => requestAnimationFrame(updateProgressBar), { passive: true });

// Nav dots click → scroll to module
document.querySelectorAll('.nav-dot').forEach(dot => {
  dot.addEventListener('click', () => {
    document.getElementById(dot.dataset.target)?.scrollIntoView({ behavior: 'smooth' });
  });
});

// Keyboard navigation
const modules = [...document.querySelectorAll('.module')];
function currentModuleIndex() {
  const y = window.scrollY + 100;
  for (let i = modules.length - 1; i >= 0; i--) {
    if (modules[i].offsetTop <= y) return i;
  }
  return 0;
}
function nextModule() { const i = currentModuleIndex(); if (i < modules.length - 1) modules[i+1].scrollIntoView({ behavior: 'smooth' }); }
function prevModule() { const i = currentModuleIndex(); if (i > 0) modules[i-1].scrollIntoView({ behavior: 'smooth' }); }
document.addEventListener('keydown', (e) => {
  if (['INPUT','TEXTAREA'].includes(e.target.tagName)) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowRight') { nextModule(); e.preventDefault(); }
  if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') { prevModule(); e.preventDefault(); }
});

// Scroll-triggered reveals
const observer = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) { entry.target.classList.add('visible'); observer.unobserve(entry.target); }
  });
}, { rootMargin: '0px 0px -10% 0px', threshold: 0.1 });
document.querySelectorAll('.animate-in').forEach(el => observer.observe(el));
```

---

## Module HTML Template

```html
<section class="module" id="module-N" style="background: var(--color-bg-warm);">
  <div class="module-content">
    <header class="module-header animate-in">
      <span class="module-number">0N</span>
      <h1 class="module-title">Module Title</h1>
      <p class="module-subtitle">One-line description of what this module teaches</p>
    </header>
    <div class="module-body">
      <section class="screen animate-in">
        <h2 class="screen-heading">Screen Title</h2>
        <p>Content...</p>
        <!-- Interactive elements, translation blocks, etc. -->
      </section>
      <!-- more screens -->
    </div>
  </div>
</section>
```

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