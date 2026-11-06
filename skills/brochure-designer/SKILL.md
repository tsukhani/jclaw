---
name: brochure-designer
description: >-
  Design and produce print-ready multi-page brochures, prospectuses, service guides and company profiles as A4 or Letter PDFs: sets the trim, bleed and margin geometry, applies a brand preset, lays out editorial pages from reusable page archetypes (cover, stat band, three-pillar, comparison table, autonomy ladder, architecture diagram, engagement path), renders through headless Chrome, and verifies the PDF page by page for geometry, embedded fonts, overflow and folio correctness, then runs a fail-the-build typographic lint over the rendered PDF checking type scale, measure, leading, family count, orphaned headings and font embedding. Use whenever the ask is a brochure, prospectus, company profile, capability statement, course catalogue PDF, print-ready one-pager, or a marketing document that must be printed or emailed as a finished PDF rather than a webpage or a slide deck.
metadata:
  author: main
  jclaw-icon: "📄"
  jclaw-tools: filesystem exec
  jclaw-commands: typolint
  version: "1.0.5"
---
# Brochure Designer

Produce a finished, print-ready brochure PDF from a brief. The medium is HTML
and CSS; the deliverable is a PDF with correct print geometry, a coherent type
scale, and a verified page count.

This skill owns one discipline the other design skills do not: **print**. A
brochure is not a website that happens to be paginated. It has a fixed trim
size, bleed, a safe margin, folios, a running footer, and no reflow. Treat a
screen layout that merely looks good as a failure.

Outputs are written to `brochure-designer/` at the root of the agent's
workspace (e.g. `brochure-designer/abundent-agentic-ai.html` and the rendered
`brochure-designer/abundent-agentic-ai.pdf`).

## When this skill is the wrong choice

State the mismatch and stop, rather than forcing the wrong artifact.

| The ask | Use instead |
| --- | --- |
| Slide deck presented on screen | `bento-slides`, `gamma-presenter`, `powerpoint-pptx` |
| Landing page or web one-pager | `claude-designer`, `ui-ux-pro-max` |
| Architecture or process diagram on its own | `diagram-design` |
| Academic paper | `research-paper-writer-pro` |
| DOCX report with headings and tables | `documents` renderDocument |

## Workflow

Follow these stages in order. Do not skip stage 3 or stage 7. Those are where
brochures actually fail.

### Stage 1 — Establish the brief

Extract these before designing. If the operator supplied a reference PDF, read
it first and reverse-engineer it rather than guessing (see stage 2).

| Field | Default when unstated |
| --- | --- |
| Page count | 8, and even, so it prints as a signature |
| Trim size | A4 portrait (210 x 297 mm) |
| Reading use | Screen-first PDF that also prints |
| Brand preset | Ask, do not invent a palette silently |
| Language | American English, unless told otherwise |

### Stage 2 — Mine any reference PDF

When given an existing brochure, do not eyeball it. Run the extraction pass in
`skills/brochure-designer/references/render-verify.md` section 2, which returns
the real trim size, margins, column pitch, font sizes and the exact palette.
Reverse engineering is cheaper and more accurate than matching by eye.

If the brief says **faithful recreation** rather than inspired-by, also work
through section 7 of that file. It gives the metric-alignment loop: detect that
the reference is a raster, identify the typeface by aspect ratio, calibrate size
and tracking jointly, then iterate against content-keyed text anchors until the
mean per-page error is under 1 mm. Report those numbers to the operator.

### Stage 3 — Build the page plan before any HTML

Read `skills/brochure-designer/references/page-plan.md` first. It carries the
spread-role system, the word budgets and the content inventory, and this stage
is where those three do their work.

Write the plan as a numbered list of pages, one line each: page number, role,
archetype, density, body-word budget, heading and folio. Read
`skills/brochure-designer/references/layout-patterns.md` for the archetypes. A
page that does not map to an archetype is a page whose content you have not
finished thinking about.

Four passes, in order, before you write a line of HTML:

**3a. Assign a spread role to every page.** `opening`, `feature`, `text-heavy`,
`gallery` or `closing`. No role may appear three times in a row: three
consecutive gallery pages read as a catalogue, three consecutive text-heavy
pages read as a report. Also track a coarse density (`light`, `medium`,
`heavy`) and keep no three consecutive pages at the same density. Alternating a
dense page with a scannable one is what renews the reader's attention, and it is
the difference between a brochure and a document.

**3b. Set a word budget for every page and hold it.** The budgets per archetype
are tabulated in `references/page-plan.md` section 2, calibrated against two
delivered 8-page A4 brochures. A budget is a ceiling on body copy, excluding
eyebrow, folio, footer and citation. When a page runs over, cut the weakest
sentence, move a clause to a page that is under budget, or demote a paragraph
to a stat reading. **Never shrink the type to fit.** A page well under budget is
not a problem: space is a design element, and a page at 60 percent looks
deliberate where the same page at 100 percent looks crowded.

**3c. Build the content inventory before drafting.** List every claim, number,
name and quotation, and mark each `must-run` (operator or source supplied:
reproduce verbatim, never round or smooth), `agent-drafted` (your prose: must be
defensible from supplied material) or `placeholder` (needs a real value:
mark it and list it in the delivery note). This is the defence against the worst
brochure failure, which is not a bad layout but a confident sentence with a
number nobody can source. It survives review because it reads well. See
`references/page-plan.md` section 3 for the format and the verification duty.

**3d. Apply the structural rules:**

- Page 1 is a cover and carries no folio.
- Page 2 is a landscape or thesis page that earns the reader's attention.
- Pillars get paired pages: an opening page and a continuation page.
- The final page carries the contact block and the closing folio.
- Every page carries exactly one dominant idea. Two competing headlines on a
  page means the page should be split.

Keep the finished plan in the workspace beside the HTML. The operator will ask
why page five exists, and the plan answers in one line.

### Stage 4 — Set the print geometry

Use the page setup rules in `skills/brochure-designer/references/print-spec.md`.
The non-negotiables:

```css
@page { size: 210mm 297mm; margin: 0; }
.page {
  width: 210mm; height: 297mm; box-sizing: border-box;
  padding: 15mm 19mm 11mm;   /* top, right and left, bottom */
  page-break-after: always; break-after: page;
  overflow: hidden;          /* catches runaway content at render time */
}
.page:last-of-type { page-break-after: auto; }
```

`overflow: hidden` is deliberate. It turns silent overflow into visible
clipping that stage 7 catches, instead of a page that quietly reflows into a
ninth page.

### Stage 5 — Apply the brand preset

Read `skills/brochure-designer/references/brand-presets.md` and copy the
preset's CSS custom properties into a `:root` block. Never hardcode a hex value
inside a rule. Every color comes from a variable so the preset stays swappable.

If no preset fits, create one: sample the palette from the source material,
name each token by role (`--paper`, `--ink`, `--accent`), and state the mapping
to the operator.

### Stage 6 — Typeset

Hold to a modular scale rather than ad-hoc sizes. Set the scale in `--step-N`
variables and use only those. Keep body copy at 10 to 11.5 pt for print; below
9 pt is unreadable on paper and will be rejected by a print shop.

- Set headline leading tight (0.95 to 1.05) and body leading loose (1.4 to 1.55).
- Use one serif for voice and one sans for labels, or a single family with
  weight contrast. Do not mix three families.
- Set line pitch as an absolute length in millimetres, not a unitless ratio,
  when recreating a reference. A ratio rescales with font size and drifts
  against a document that used fixed leading.
- Do not set a global `font-weight` on `body` as a shortcut. Display and body
  copy often use different weights; a blanket 500 will widen every paragraph.
- Apply `letter-spacing: -0.02em` to display sizes above 30 pt, and slightly
  positive tracking to uppercase labels at 9 to 10 pt.
- Use `text-wrap: balance` on headlines and `text-wrap: pretty` on paragraphs.
- Hyphenate nothing. Set `hyphens: none` and let the measure do the work.

### Stage 7 — Render and verify

Render with headless Chrome, then run the verification pass. Both are written
out in `skills/brochure-designer/references/render-verify.md`.

Verification is not optional and is not a visual glance. Confirm, with numbers:

1. Page count equals the plan.
2. Every page reports the intended trim size in millimeters.
3. Fonts are embedded subsets, not system references.
4. No page's text bounding box exceeds the content box.
5. The folio sequence is contiguous and matches the plan.
6. Every source citation named in the brief appears in a page footer.

Then run the typographic lint, which fails the build rather than warning:

```bash
skills/brochure-designer/tools/typolint brochure-designer/output.pdf
```

It checks the things a visual glance misses: a sprawling type scale, a measure
outside 45-90 characters, leading below 1.25 or above 1.75 em, more than two
type families, a display heading stranded at a page foot, and any non-base-14
font that was not embedded. Exit code 1 means at least one FAIL. Fix the page
and re-render; do not ship a FAIL. A WARN is advisory, but say why you accepted
it. Run `--json` when you want to record the numbers.

Pass the stage 3 budgets so the copy ceiling stays enforced to the end:

```bash
skills/brochure-designer/tools/typolint brochure-designer/output.pdf \
  --budget 40 230 190 260 250 190 200 210
```

One integer per page, in order; use `0` for a page with no budget. It reports
every page's usage as a percentage and FAILs with `OVER_BUDGET` when a page
exceeds its ceiling. The budget is a copy instruction, not a typesetting one: a
page over budget gets words cut or moved, never the type shrunk.

Report the numeric results of both passes to the operator. "Looks right" is not
a report.

### Stage 8 — Deliver

Link both artifacts. The PDF is the deliverable; the HTML is the editable
source the operator will want when they change a sentence. Always hand over
both, and say which is which.

## Page archetypes

Full markup recipes live in `references/layout-patterns.md`. The catalog:

| Archetype | Purpose |
| --- | --- |
| `cover` | Wordmark, eyebrow, display statement, three-line pillar strip, footer |
| `landscape` | Thesis page: one hard claim, a stat band, a comparison table |
| `pillars` | Three parallel strands, each with a FROM and a TO panel |
| `pillar-detail` | One pillar given a full page with its own diagram and scoring grid |
| `stat-band` | Three or four large numbers with a one-line reading and a source line |
| `steps` | A horizontal or vertical numbered process, three to five steps |
| `ladder` | Progressive levels, each with a name and a one-line definition |
| `architecture` | Layered stack: channels on top, platform in the middle, data below |
| `engagement` | Numbered path plus three engagement tiers and a contact block |

## Hard rules

- **Never let content reflow into an unplanned page.** Fix the copy or the type
  scale. Do not raise the page count to absorb overflow.
- **Never put body text in the bottom 11 mm or the outer 19 mm.** That is the
  margin zone, and print shops trim into it.
- **Never use pure `#000000` for body text.** It reads harsh on uncoated stock.
  Use a near-black such as `#16171C`.
- **Every statistic carries its source.** Put the source in the page footer. A
  brochure that states numbers without attribution is a brochure the reader
  discounts entirely.
- **One folio system for the whole document.** Either `04 / 08` or `04`, never
  both, and never restart mid-document.
- **No JavaScript.** A brochure must render identically in a print shop's
  engine. Static HTML and CSS only.
- **Do not claim the file is CMYK-ready for a print shop** unless the operator
  asked for CMYK conversion and stage 7 ran the Ghostscript pass. State the
  color model you actually produced.

## Error handling

| Symptom | Cause | Fix |
| --- | --- | --- |
| Page count exceeds plan | Content overflow | Reduce copy or drop a type step; do not change page size |
| Text clipped at a page edge | `overflow: hidden` caught an overflow | Same as above |
| Chrome writes Letter despite `@page` | Missing `size` in the `@page` rule, or a paper flag on the command | Keep `size` in CSS and pass no paper flag |
| Fonts not embedded | A web font URL failed to load | Use an installed system face, or inline the font as base64 |
| Blank trailing page | A trailing `page-break-after` | The `:last-of-type` rule above prevents it |
| Background color missing in print | Print backgrounds disabled | `--print-to-pdf` includes them; the browser print dialog needs "Background graphics" |

## References

| File | Read when |
| --- | --- |
| `references/print-spec.md` | Setting up geometry, bleed, margins, color, folios |
| `references/page-plan.md` | Building the page plan: roles, rhythm, word budgets, content inventory |
| `references/layout-patterns.md` | Choosing and building a page archetype |
| `references/brand-presets.md` | Applying or creating a palette and type stack |
| `references/render-verify.md` | Rendering to PDF, mining a reference PDF, verifying output |
| `tools/README.md` | Looking up a typolint rule code or its threshold |
