# Slide Decks

Use this reference when the deliverable is a slide presentation, pitch deck, keynote-style talk, or any fixed-aspect-ratio sequence of "slides."

---

## Canvas size and scaling

Slide decks must implement their own JS scaling so the content fits any viewport. Standard approach:

- **Fixed-size canvas**: 1920×1080 (16:9) by default. Don't improvise smaller unless asked.
- **Full-viewport stage** wraps the canvas and letterboxes it on black using `transform: scale()`.
- **Prev/next controls sit OUTSIDE the scaled element** so they stay usable on small screens.

**Do not hand-roll this.** Copy `assets/starters/deck_stage.js` into your project and use the `<deck-stage>` web component. Each slide is a direct child `<section>` of `<deck-stage>`. The starter handles:

- Scaling to any viewport
- Keyboard navigation (arrow keys, space)
- Tap/click navigation on mobile
- Slide counter overlay
- `localStorage` persistence of current slide
- Print-to-PDF (one page per slide via `@media print`)
- Auto-tagging `data-screen-label` and `data-om-validate` on slides
- Speaker notes sync via `postMessage`

Usage:

```html
<script src="deck_stage.js"></script>
<deck-stage>
  <section>Slide 1 content</section>
  <section>Slide 2 content</section>
  <section>Slide 3 content</section>
</deck-stage>
```

---

## Slide labels (for debugging and comments)

Put `data-screen-label` attributes on top-level slide elements:

```html
<section data-screen-label="01 Title">...</section>
<section data-screen-label="02 Agenda">...</section>
<section data-screen-label="03 Problem">...</section>
```

**1-indexed, matching what the user sees.** If the user says "slide 5", they mean the 5th slide (label `"05"`), never array position `[4]`. Humans don't speak 0-indexed.

The deck_stage starter auto-adds these if you omit them.

---

## Typography scale for 1920×1080

At 1920×1080, text is much larger than on a webpage. Use these as baselines, not maximums:

- **Display/title**: 96–160px
- **Section headers**: 64–96px
- **Body text**: 32–48px (minimum 24px)
- **Captions/fine print**: 20–28px

Apply `text-wrap: pretty` or `text-wrap: balance` to headings for clean line breaks.

---

## Visual system

Commit to a system up front. Before writing slides, decide:

1. **Layouts** — what are the 3–5 slide templates? (Title / section-divider / content / full-bleed image / split / quote / end)
2. **Palette** — 1–2 background colors max across the deck. Use one as the "default" and one for section dividers or emphasis.
3. **Type hierarchy** — one display face for titles, one text face for body (or a single well-made sans in multiple weights).
4. **Imagery treatment** — are you using photos? illustrations? abstract geometric shapes? No imagery and relying on typography?
5. **Rhythm** — mix text-heavy slides with visual-heavy ones. A deck that's all bullet lists is a wall of text.

State this plan in a comment at the top of the file so it's easy to check yourself against.

---

## Speaker notes

Only add speaker notes when the user explicitly asks. When using them, put less text on slides and focus on impactful visuals. Speaker notes are full conversational scripts of what the speaker will say.

To add them, include this in `<head>`:

```html
<script type="application/json" id="speaker-notes">
[
  "Slide 1 notes — full script of what to say",
  "Slide 2 notes — can be multiple paragraphs",
  "Slide 3 notes"
]
</script>
```

The `deck_stage.js` starter reads this tag and posts `{slideIndexChanged: N}` to the parent on init and on every slide change, so external note viewers stay in sync.

---

## Density and editing

- No filler bullets. Three sharp points > seven vague ones.
- No bullet point that's a complete sentence restating the title.
- Use images, diagrams, quotes, or big numbers to break up text.
- Section dividers should feel like a beat — use a different background, larger type, and whitespace.
- End slide: a single thought, or a call to action, or a thank-you. Not a recap bullet list.

---

## Variation patterns for decks

When giving deck options:

- **Overall aesthetic**: minimal editorial / bold branded / dense infographic / image-forward
- **Type treatment**: elegant serif display / utilitarian sans / heavy mono / mixed
- **Palette**: monochrome + one accent / warm editorial / saturated brand / dark mode
- **Content density**: tight (6 slides) / balanced (12) / comprehensive (20+)

Expose variants as toggle controls or produce a "Deck Explorations" file that has 2–3 complete short decks side by side (3–4 slides each).

---

## Export to PDF

The `deck_stage.js` starter includes `@media print` rules that render one slide per page. The user can print to PDF from the browser (Cmd+P / Ctrl+P). Mention this in your summary if PDF export matters.

If you need to produce a PPTX instead, see whether your tooling environment has a native exporter. Otherwise, the HTML-with-print-to-PDF path is cleanest.
