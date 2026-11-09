# Layout patterns

Markup recipes for each page archetype. Copy the structure, then reword the
content. Every pattern assumes the geometry from `print-spec.md` and tokens
from `brand-presets.md`.

## Contents

1. Shared scaffolding
2. `cover`
3. `landscape`
4. `stat-band`
5. `pillars`
6. `pillar-detail`
7. `steps`
8. `ladder`
9. `architecture`
10. `engagement`
11. Anti-patterns

## 1. Shared scaffolding

Every page is a `<section class="page">`. Put the header and footer in a shared
partial so they never drift.

```html
<section class="page">
  <header class="page-head">
    <span class="eyebrow">04 · PILLAR 01 · PEOPLE</span>
    <span class="wordmark">ABUNDENT</span>
  </header>

  <!-- page body -->

  <footer class="page-foot">
    <span class="doc-id">ABUNDENT — AGENTIC AI AS A SERVICE</span>
    <span class="folio">04 / 08</span>
  </footer>
</section>
```

```css
.page-head {
  display: flex; justify-content: space-between; align-items: baseline;
  margin-bottom: 8mm;
}
.eyebrow, .wordmark, .doc-id, .folio {
  font-family: var(--sans);
  font-size: var(--step--1);
  letter-spacing: 0.09em;
  text-transform: uppercase;
}
.page-foot {
  position: absolute; left: 19mm; right: 19mm; bottom: 11mm;
  display: flex; justify-content: space-between; align-items: baseline;
}
```

A citation footer goes above the folio line, spanning the measure:

```html
<p class="citation">
  Sources: Gartner press release, 25 June 2025. Vendor websites, accessed
  November 2026. Trademarks belong to their owners.
</p>
```

```css
.citation {
  position: absolute; left: 19mm; right: 19mm; bottom: 18mm;
  font-size: 7.5pt; line-height: 1.4; color: var(--muted);
  border-top: 0.25pt solid var(--rule); padding-top: 2mm;
}
```

Note the 7.5 pt citation size. This is the one place 9 pt is relaxed, because a
citation must be legible but subordinate. Never let it exceed 8 pt.

## 2. `cover`

The cover has four zones stacked vertically: wordmark bar, display statement,
supporting paragraph, and a three-column pillar strip pinned near the bottom.

```html
<section class="page cover">
  <header class="page-head">
    <span class="wordmark big">ABUNDENT</span>
    <span class="eyebrow">EDITION 2026</span>
  </header>

  <p class="eyebrow accent-y">AGENTIC AI AS A SERVICE</p>

  <h1 class="display">
    Human-led.<br>Agent-run.<br>Data-proven.
  </h1>

  <p class="standfirst">
    Complete business transformation to an agentic architecture across people,
    process and technology. Delivered by Abundent. Powered by JClaw.
  </p>

  <div class="pillar-strip">
    <div><span class="eyebrow">01 · PEOPLE</span><p>Empathy-led growth and support</p></div>
    <div><span class="eyebrow">02 · PROCESS</span><p>Agentic workflows and automation</p></div>
    <div><span class="eyebrow">03 · TECHNOLOGY</span><p>JClaw enterprise agentic harness</p></div>
  </div>

  <footer class="page-foot">
    <span class="doc-id">KUALA LUMPUR · MALAYSIA</span>
    <span class="doc-id">POWERED BY JCLAW</span>
  </footer>
</section>
```

```css
.cover .display {
  font-size: 59pt; line-height: 0.95; letter-spacing: -0.025em;
  margin: 22mm 0 0; font-weight: 400;
}
.cover .standfirst {
  font-size: var(--step-1); line-height: 1.45;
  max-width: 150mm; margin-top: 16mm;
}
.pillar-strip {
  position: absolute; left: 19mm; right: 19mm; bottom: 40mm;
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 8mm;
}
```

Design notes:

- The display statement is three short sentences stacked with hard breaks.
  Letting it wrap naturally produces an uncontrolled rag at this size.
- The pillar strip sits in the bottom third with generous space above. The
  empty middle is the design; do not fill it.
- The cover carries no folio and no citation line.

## 3. `landscape`

The thesis page. One hard claim in display type, supporting paragraph, then a
row of statistics and a two-column comparison table.

```html
<section class="page">
  <header class="page-head">
    <span class="eyebrow">02 · THE LANDSCAPE</span>
    <span class="wordmark">ABUNDENT</span>
  </header>

  <h2 class="display-sm">Everyone is selling agents.<br>Few are selling transformation.</h2>
  <p class="lede">Agentic AI has moved from pilots to procurement. But the
     market splits the problem into pieces and leaves you to stitch them
     together.</p>

  <div class="stat-row">
    <div><b class="stat">&gt;40%</b><p class="stat-read">of agentic AI projects will be
      cancelled by the end of 2027, on rising costs and unclear business value.</p></div>
    <div><b class="stat">~130</b><p class="stat-read">of the thousands of vendors claiming
      agentic AI offer real agentic capability.</p></div>
    <div><b class="stat">15%</b><p class="stat-read">of day-to-day work decisions will be
      made autonomously by agentic AI by 2028, up from 0% in 2024.</p></div>
  </div>

  <table class="compare">
    <tr><th>Who offers what today</th><th></th></tr>
    <tr><td>Global consultancies</td><td>Agent platforms bundled with industry solutions.</td></tr>
    <tr><td>Hyperscalers</td><td>Runtime, memory and identity for teams that build their own.</td></tr>
    <tr><td>SaaS vendors</td><td>Agents orchestrated inside their own ecosystems.</td></tr>
    <tr><td>Advisory firms</td><td>Organisation redesign without a platform to run it on.</td></tr>
  </table>
</section>
```

```css
.display-sm { font-size: 41pt; line-height: 1.0; letter-spacing: -0.02em; margin: 0 0 5mm; }
.lede { font-size: var(--step-1); line-height: 1.45; max-width: 165mm; }
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8mm; margin: 8mm 0; }
.stat { font-family: var(--serif); font-size: 30pt; line-height: 1; display: block; }
.stat-read { font-size: 9.5pt; line-height: 1.4; margin: 2mm 0 0; }
.compare { width: 100%; border-collapse: collapse; font-size: 9.5pt; }
.compare th { text-align: left; border-bottom: 0.75pt solid var(--ink); padding: 2mm 0; }
.compare td { padding: 2.5mm 0; border-bottom: 0.25pt solid var(--rule); vertical-align: top; }
.compare td:first-child { width: 42mm; font-weight: 600; }
```

The comparison table carries the argument better than prose does. Use the left
cell for the category and the right for what that category actually provides.

## 4. `stat-band`

Use when one page's job is to prove a claim with numbers.

```html
<div class="stat-band four">
  <div class="stat-cell">
    <b class="stat">80%</b>
    <p class="stat-read">of common service issues will be resolved by agentic AI
       without human help by 2029.</p>
    <span class="stat-source">Gartner, March 2025</span>
  </div>
  <!-- three more cells -->
</div>
```

```css
.stat-band { display: grid; gap: 8mm; margin: 6mm 0; }
.stat-band.four { grid-template-columns: repeat(4, 1fr); }
.stat-band.three { grid-template-columns: repeat(3, 1fr); }
.stat-cell { border-top: 0.75pt solid var(--ink); padding-top: 3mm; }
.stat-band .stat { font-size: 25pt; }
.stat-source {
  display: block; margin-top: 2mm;
  font-size: 8pt; letter-spacing: 0.04em; color: var(--muted);
}
```

Every cell in a stat band needs its own source, even when cells share a source.
A reader who is checking one number should not have to hunt for the credit.

## 5. `pillars`

Three parallel strands, each with a FROM panel and a TO panel. This is the
workhorse page of a transformation brochure.

```html
<div class="pillars">
  <article class="pillar">
    <span class="pillar-num">01 · PEOPLE</span>
    <h3>Empathy-led growth</h3>
    <div class="from-to">
      <div class="from">
        <span class="tag">FROM</span>
        <p>Efficiency scripts: handle time, tickets closed, cost per contact.</p>
      </div>
      <div class="to">
        <span class="tag accent-y">TO</span>
        <p>Empathy-led growth and support, measured on Key Caring and Key
           Fairness Indicators.</p>
      </div>
    </div>
  </article>
  <!-- two more -->
</div>
```

```css
.pillars { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8mm; }
.pillar { border-top: 1.5pt solid var(--ink); padding-top: 3mm; }
.pillar-num { font-size: 9pt; letter-spacing: 0.1em; color: var(--muted); }
.from-to { display: grid; gap: 4mm; }
.from p, .to p { font-size: 9.5pt; line-height: 1.4; margin: 1.5mm 0 0; }
.from .tag { color: var(--muted); }
.to .tag { color: var(--accent); }
.tag { font-size: 8.5pt; letter-spacing: 0.12em; font-weight: 600; }
```

The FROM panel is set muted and the TO panel in ink with the accent label. The
contrast carries the transformation without needing an arrow graphic.

## 6. `pillar-detail`

One pillar, given a full page: a claim in display type, a scoring grid with
numbers set large, and a three-step rating method.

```html
<h2 class="display-sm">KPIs are for machines,<br>not humans.</h2>

<div class="score-grid">
  <div class="score-col">
    <h3 class="score-head">KCIs <span>Key caring indicators</span></h3>
    <p class="score-sub">Instances of the three types of empathy</p>
    <dl>
      <div><dt>Cognitive</dt><dd>2</dd></div>
      <div><dt>Emotional</dt><dd>3</dd></div>
      <div><dt>Compassionate</dt><dd>7</dd></div>
    </dl>
  </div>
  <!-- second column: KFIs -->
</div>
```

```css
.score-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10mm; }
.score-head { font-size: 20pt; margin: 0; }
.score-head span { font-size: 11pt; font-weight: 400; letter-spacing: 0.06em; }
.score-col dl > div {
  display: flex; justify-content: space-between; align-items: baseline;
  border-bottom: 0.25pt solid var(--rule); padding: 3mm 0;
}
.score-col dt { font-size: 11.5pt; font-weight: 600; }
.score-col dt + dd { margin: 0; font-size: 19pt; font-family: var(--serif); }
```

When a number is a weight or a score rather than a measurement, set it in the
serif at large size. It reads as a value, not as data.

## 7. `steps`

A horizontal numbered process. Three to five steps, no more, or the labels
crowd.

```html
<ol class="steps">
  <li>
    <span class="step-num">STEP 1</span>
    <h3>Map the domain</h3>
    <p>Event-storming with your teams surfaces events, decisions and pain points.</p>
  </li>
  <!-- more -->
</ol>
```

```css
.steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6mm;
         list-style: none; padding: 0; margin: 6mm 0; }
.steps li { border-top: 1pt solid var(--ink); padding-top: 3mm; }
.step-num { font-size: 8.5pt; letter-spacing: 0.1em; color: var(--accent); }
.steps h3 { font-size: 13pt; margin: 2mm 0; }
.steps p { font-size: 9pt; line-height: 1.4; }
```

Steps read left to right and imply sequence. If the items are not sequential,
use the `pillars` pattern instead, which does not imply order.

## 8. `ladder`

Progressive levels from least to most autonomy. Set each level as a horizontal
rung with a code, a name, and a definition.

```html
<div class="ladder">
  <div class="rung"><b>L0</b><span>Assist</span><p>Drafts, summarizes and retrieves.</p></div>
  <div class="rung"><b>L1</b><span>Recommend</span><p>Proposes; a person decides.</p></div>
  <div class="rung"><b>L2</b><span>Act with approval</span><p>Runs once approved.</p></div>
  <div class="rung"><b>L3</b><span>Act and report</span><p>Executes; a person reviews.</p></div>
  <div class="rung"><b>L4</b><span>Autonomous</span><p>Within guardrails, fully audited.</p></div>
</div>
```

```css
.ladder { display: grid; grid-template-columns: repeat(5, 1fr); gap: 5mm; margin: 6mm 0; }
.rung { border-top: 1.5pt solid var(--ink); padding-top: 3mm; }
.rung b { display: block; font-family: var(--serif); font-size: 17pt; }
.rung span { display: block; font-size: 10pt; font-weight: 600; margin: 1mm 0 1.5mm; }
.rung p { font-size: 8.5pt; line-height: 1.35; margin: 0; color: var(--muted); }
```

To communicate a ramp rather than five equal options, step the top rule
thickness from 2.5 pt at L0 down to 0.5 pt at L4, or tint each rung
progressively. The visual gradient is the argument.

## 9. `architecture`

A layered stack read top to bottom: channels, platform, models, data.

```html
<div class="stack">
  <div class="layer channels">Web · Mobile · Email · Chat · Contact centre</div>

  <div class="layer platform">
    <h3>JClaw <span>enterprise agentic harness</span></h3>
    <div class="modules">
      <div><b>Orchestration</b><p>Multi-agent workflows across bounded contexts.</p></div>
      <div><b>Integrations</b><p>APIs, connectors and MCP into your systems.</p></div>
      <div><b>Knowledge</b><p>Memory and retrieval over your own documents.</p></div>
      <div><b>Guardrails</b><p>Policies, limits and human sign-off gates.</p></div>
      <div><b>Identity</b><p>Agents act with least-privilege access.</p></div>
      <div><b>Observability</b><p>Traces, costs, agent KPIs and scorecards.</p></div>
    </div>
  </div>

  <div class="layer models">Open-weight on your infrastructure · Frontier via API</div>
  <div class="layer data">ERP · CRM · Core systems · Warehouse · Documents</div>
</div>
```

```css
.stack { display: grid; gap: 3mm; }
.layer {
  border: 0.5pt solid var(--rule); padding: 3mm 4mm;
  font-size: 9pt; letter-spacing: 0.06em; text-transform: uppercase;
  display: flex; align-items: center; justify-content: center;
}
.layer.platform { display: block; text-transform: none; letter-spacing: 0; }
.modules { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4mm; margin-top: 3mm; }
.modules b { font-size: 10pt; }
.modules p { font-size: 8.5pt; line-height: 1.35; margin: 1mm 0 0; color: var(--muted); }
```

The outer layers are single lines in caps. The platform layer is the only one
that opens up into modules. That asymmetry is what shows the platform as the
substantial thing and the edges as thin interfaces.

## 10. `engagement`

Closing page: a numbered path across the top, three tiers in the middle, and a
contact block in a tinted panel.

```html
<div class="path">
  <div><span>01</span><h3>Discover</h3><p>Readiness diagnostic and opportunity map.</p></div>
  <!-- 02 Design, 03 Deploy, 04 Run -->
</div>

<div class="tiers">
  <div><h4>Agentic Pilot</h4><p>One workflow, one team.</p></div>
  <div><h4>Agentic Function</h4><p>A whole function, redesigned.</p></div>
  <div><h4>Agentic Enterprise</h4><p>A multi-function programme.</p></div>
</div>

<div class="contact">
  <p class="cta">Start with an Agentic Readiness Diagnostic.</p>
  <p class="contact-lines">[EMAIL] · [PHONE_NUMBER]</p>
</div>
```

```css
.contact {
  background: var(--sand); padding: 6mm; margin-top: 6mm;
  display: grid; grid-template-columns: 1fr 1fr; gap: 8mm; align-items: end;
}
.cta { font-size: 15pt; line-height: 1.25; margin: 0; }
.contact-lines { font-size: 10pt; margin: 0; text-align: right; }
```

The contact panel is the one element on the page allowed a fill. A tinted panel
draws the eye, so use it once per document.

## 11. Anti-patterns

These are the mistakes that make a brochure look amateur. Each one is
recognizable at a glance and each has a fix.

| Anti-pattern | Why it fails | Fix |
| --- | --- | --- |
| Three or more type sizes above 30 pt on one page | Nothing is emphasized when everything is | One display line, others subordinate |
| Centered body paragraphs | Ragged on both sides, hard to read | Left-align all body copy |
| Text over a photograph without a scrim | Unpredictable contrast | Add a solid or gradient scrim under the text |
| Five colors in one row of icons | Reads as clip art | One accent, one muted tone |
| Equal weight for FROM and TO | The transformation disappears | Mute FROM, accent TO |
| Icons used as bullets | Adds noise, no information | Use a rule or a number instead |
| Dense full-page table at 8 pt | Reader gives up | Split across two pages or cut rows |
| Contact details only in the last 5 mm | Trimmed or missed | Give the contact block its own tinted panel |
| Mixed folio styles | Looks assembled from pieces | One folio system, document-wide |
| Decorative curves and swooshes | Dates instantly | Rules, blocks and type only |

## Fill-the-sheet pattern (learned 2026-11-06)

When text ends well above the footer, wrap the page body in `<div class="fill">` inside a `display:flex; flex-direction:column` page and give `.fill { flex:1 1 auto; justify-content:space-between }`. Absolutely positioned blocks (contact panels) collide with in-flow content once the page becomes flex, so move them into the flow.
