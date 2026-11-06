# Brand presets

A preset is a `:root` block of role-named tokens plus a type stack. Copy one
whole, then override only what the brief requires.

## Contents

1. Why role-named tokens
2. The Abundent preset (verified against the 2026 brochure)
3. Neutral editorial preset
4. Creating a preset from a reference
5. Type stacks that work in print
6. Checking a preset before you build

## 1. Why role-named tokens

Name colors by what they do, never by what they look like. `--paper` survives a
palette change; `--cream-bg` does not. Every rule in the document references a
token, so swapping a preset is a single-block edit.

The standard set:

| Token | Role |
| --- | --- |
| `--paper` | Page background |
| `--ink` | Primary body text |
| `--muted` | Secondary text, captions, source lines |
| `--sand` | Tinted panel background |
| `--rule` | Hairline borders and dividers |
| `--accent` | The single accent |
| `--serif` | Voice face |
| `--sans` | Label and UI face |

Seven colors is enough for any brochure. More than that and the page starts to
look like it has no point of view.

## 2. The Abundent preset

Reverse engineered from `Abundent Agentic AI Brochure.pdf` on 2026-11-06 by
pixel sampling and vector inspection, not by eye.

```css
:root {
  /* Roles */
  --paper:   #F3F2ED;  /* warm off-white, 69.5% of page pixels */
  --ink:     #16171C;  /* near-black body text */
  --muted:   #6B6E78;  /* captions and source lines */
  --deep:    #262932;  /* second-dark, for inverted panels */
  --graphite:#3A3D46;  /* third tone, for diagrams */
  --sand:    #EAE6DD;  /* tinted panel fill */
  --sand-2:  #E3DFD4;  /* deeper tint, cover blocks */
  --tan:     #C2B9A8;  /* rule and diagram line */
  --rule:    #D7CFC2;  /* hairline divider */
  --accent:  #A83C16;  /* rust, the single accent */

  /* Type stacks */
  --serif: "Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif;
  --sans:  "Helvetica Neue", Helvetica, Arial, sans-serif;

  /* Scale, 1.25 from an 11pt base */
  --step--2: 7.5pt; --step--1: 9pt;  --step-0: 11pt;
  --step-1: 13.75pt; --step-2: 17.2pt; --step-3: 21.5pt;
  --step-4: 26.9pt; --step-5: 33.6pt; --step-6: 42pt;
  --step-7: 52.5pt; --display: 59pt;
}
```

Verified facts about this document, for reference when rebuilding it:

| Property | Value |
| --- | --- |
| Trim | 210.1 x 297.1 mm (A4) |
| Side margins | 19 mm, both sides |
| Top margin | 14.5 mm |
| Bottom margin | 11 mm |
| Cover display size | 59 pt |
| Page headline size | 34 to 41 pt |
| Standfirst | 13.2 pt |
| Body | 10 to 11.5 pt |
| Labels and eyebrows | 9.3 to 10.5 pt |
| Citation line | 7.5 pt |
| Columns | 3, 52 mm each with 8 mm gutters |
| Folio format | `NN / 08` |
| Accent usage | Cover mark, eyebrow labels, FROM/TO tags, callout panel |

The accent appears on roughly 5 to 10% of page pixels on the pages that use it,
and on none of the interior continuation pages. That restraint is the reason
the palette reads as considered rather than decorated.

## 3. Neutral editorial preset

For a document with no house palette, or when the client has not decided.

```css
:root {
  --paper:   #FAFAF7;
  --ink:     #1A1A1A;
  --muted:   #767676;
  --deep:    #222222;
  --graphite:#4A4A4A;
  --sand:    #F0EEE8;
  --sand-2:  #E8E5DC;
  --tan:     #C8C4B8;
  --rule:    #DEDAD0;
  --accent:  #1F4E79;  /* deep blue, professional without being cold */

  --serif: "Charter", "Georgia", serif;
  --sans:  "Helvetica Neue", Arial, sans-serif;

  --step--2: 7.5pt; --step--1: 9pt;  --step-0: 11pt;
  --step-1: 13.75pt; --step-2: 17.2pt; --step-3: 21.5pt;
  --step-4: 26.9pt; --step-5: 33.6pt; --step-6: 42pt;
  --step-7: 52.5pt; --display: 59pt;
}
```

## 4. Creating a preset from a reference

Do not sample by eye. Run the extraction script in `render-verify.md`,
which returns the dominant colors with their pixel shares and the exact text
sizes. Then assign roles:

1. The most common light color becomes `--paper`.
2. The most common dark color becomes `--ink`.
3. Any saturated color becomes `--accent`, if it appears on 2% or more of a page.
4. Warm neutrals between paper and ink become `--sand` and `--rule`.
5. Colors appearing in under 0.5% of pixels are probably anti-aliasing
   artifacts, not design tokens. Ignore them.

State the resulting mapping to the operator, with the hex values, so they can
correct you if a color means something specific to their brand.

## 5. Type stacks that work in print

Only use faces that are installed on the machine, or the PDF will silently
substitute and the layout will shift. Verify with
`ls /System/Library/Fonts/Supplemental/` before committing to a stack.

Serif options, verified present on this machine:

| Stack | Character |
| --- | --- |
| `"Iowan Old Style", Georgia, serif` | Warm, editorial, strong at display sizes |
| `Georgia, serif` | Safe, slightly clumsy at large sizes, excellent at 10 pt |
| `"Hoefler Text", Georgia, serif` | Classical, high contrast, needs large sizes |
| `Baskerville, Georgia, serif` | Refined display face, weak at body sizes |
| `Charter, Georgia, serif` | Sturdy, screen and print, honest at body sizes |

Sans options:

| Stack | Character |
| --- | --- |
| `"Helvetica Neue", Helvetica, Arial, sans-serif` | Neutral, reliable for labels |
| `"SF Pro Text", -apple-system, sans-serif` | Modern, good tracking at small sizes |
| `Arial, Helvetica, sans-serif` | Universally available, unremarkable |

Pairing rules:

- Serif for headlines and body, sans for labels only. This is the safest and
  most editorial combination.
- One family with weight contrast works for technology and finance briefs.
- Never put a serif label on a sans headline or the reverse. Pick one direction.

## 6. Checking a preset before you build

Three quick checks that catch most palette errors:

1. **Contrast.** Body ink against paper should exceed 12:1. The Abundent pair
   `#16171C` on `#F3F2ED` is about 15:1, which is comfortable.
2. **Accent count.** Load the preset into a page, render it, and count how many
   distinct saturated colors appear. More than one means the accent is not doing
   its job.
3. **Grayscale test.** Convert a rendered page to grayscale. If the layout still
   reads clearly, the palette is doing structural work rather than carrying the
   whole design.
