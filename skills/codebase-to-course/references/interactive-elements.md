Contents: 1. Translation block, 2. Multiple-choice quiz, 3. Group chat, 4. Flow animation, 5. Glossary tooltips, 6. Callout boxes, 7. Spot the bug, 8. Other patterns.

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
