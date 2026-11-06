# Print specification

Everything needed to set up a page so it prints correctly. Read this before
writing any CSS.

## Contents

1. Trim sizes
2. Margins and the safe zone
3. Bleed
4. Page box CSS
5. Columns and the baseline grid
6. Color
7. Typography for paper
8. Folios and the running footer
9. Image resolution
10. What a print shop will check

## 1. Trim sizes

| Name | Millimeters | Points | Notes |
| --- | --- | --- | --- |
| A4 | 210 x 297 | 595.28 x 841.89 | Default for Malaysia, the EU, the UK, Australia |
| US Letter | 215.9 x 279.4 | 612 x 792 | Default for the US and Canada |
| A5 | 148 x 210 | 419.53 x 595.28 | Half A4, booklet pages |
| Square | 210 x 210 | 595.28 x 595.28 | Brochures handed out at events |
| A4 landscape | 297 x 210 | 841.89 x 595.28 | Spreads and gatefolds |

Use A4 unless the operator's market is the US. A page count that is even and a
multiple of four is what allows a saddle-stitched print run; for a digital-only
PDF an even count is enough.

## 2. Margins and the safe zone

Two different margins matter, and confusing them is the most common print
error.

- **Bleed margin**: artwork that runs off the page extends past the trim line.
  This is not a content margin.
- **Safe margin**: nothing that must survive trimming sits outside it.

Recommended safe margins for A4, based on a 19 mm side margin which is a
comfortable editorial measure:

| Edge | Safe margin | Why |
| --- | --- | --- |
| Left and right | 19 mm | Keeps the text column off the trim and gives the eye air |
| Top | 15 mm | Room for an eyebrow and the wordmark |
| Bottom | 11 mm | Room for a footer line plus a folio |

For a saddle-stitched booklet, add creep allowance: inner margins grow by about
1 mm per 16 pages. For a digital PDF this does not apply.

For a bound document of more than 40 pages, make the inner margin 3 mm wider
than the outer one. Below 40 pages, keep them equal.

## 3. Bleed

Screen PDFs need no bleed. A file sent to a print shop does.

- Standard bleed: 3 mm on every edge.
- With bleed, the page box becomes 216 x 303 mm for A4.
- Crop marks sit outside the bleed, so the full media box is about
  226 x 313 mm.

Render the screen-first PDF at true trim size. If the operator asks for a
print-shop file, produce a second PDF at the bleed size and say which one is
which. Do not deliver only a bleed version to someone who wants to read it on
screen, because it will look wrong in every viewer.

## 4. Page box CSS

```css
@page { size: 210mm 297mm; margin: 0; }

html, body { margin: 0; padding: 0; }

.page {
  width: 210mm;
  height: 297mm;
  box-sizing: border-box;      /* so padding does not add to the height */
  padding: 15mm 19mm 11mm;
  background: var(--paper);
  color: var(--ink);
  position: relative;          /* anchors absolutely positioned folios */
  overflow: hidden;            /* turns overflow into visible clipping */
  page-break-after: always;
  break-after: page;
  page-break-inside: avoid;
  break-inside: avoid;
}

.page:last-of-type { page-break-after: auto; break-after: auto; }
```

Three details that get missed:

- `box-sizing: border-box` is what stops a padded 297 mm page from becoming
  297 mm plus padding and spilling onto a second sheet.
- `page-break-after` is the legacy property, `break-after` is the modern one.
  Chrome honours both; keep both so the file survives a different renderer.
- `:last-of-type` removes the break after the final page, which is the only way
  to avoid a blank trailing sheet.

Never set `height: 100vh`. In a paged context that resolves against the
viewport, not the sheet, and it produces wildly wrong page heights.

## 5. Columns and the baseline grid

A 19 mm margin on A4 leaves a 172 mm measure. Divide it into columns with a
gutter rather than using percentages, so the vertical rules land on whole
millimeters.

A three-column grid on A4 with a 19 mm margin:

```
column width = (172 - 2 x 8) / 3 = 52 mm
```

Use `gap: 8mm` and let the browser compute the columns, but verify the resulting
widths numerically rather than trusting the layout.

Vertical rhythm: pick a baseline of 4 mm and set every vertical margin in
multiples of it. Text set on a rhythm looks intentional; text with arbitrary
margins looks assembled.

## 6. Color

| Model | When |
| --- | --- |
| sRGB | Screen-first PDF. This is the default and is correct. |
| CMYK | Only when a print shop demands it, which is rarer than people assume |

Print in sRGB and let the shop convert, unless the operator says otherwise. A
naive RGB to CMYK conversion shifts saturated colors badly, and a brochure
rendered in sRGB with a good palette converts better than one pre-converted
without a profile.

Rules for the palette itself:

- Paper is never `#FFFFFF`. Use an off-white such as `#F3F2ED`. Pure white
  looks like an unprinted screen, not paper.
- Body ink is never `#000000`. Use `#16171C` or similar.
- Keep one accent. Two accents on an 8-page document compete with each other.
- Check contrast: body text against paper should exceed 12:1 for print legibility,
  which is a stricter bar than the web's 4.5:1.

Optional CMYK check, if requested:

```bash
# Rasterize, convert, and report ink coverage
gs -dSAFER -dBATCH -dNOPAUSE -sDEVICE=tiff32nc -r300 \
   -sOutputFile=page.tif input.pdf
# Total area coverage above 300% will smudge on uncoated stock.
```

## 7. Typography for paper

Print is read at a different distance and under different light than a screen.

| Element | Size | Leading |
| --- | --- | --- |
| Body copy | 10 to 11.5 pt | 1.4 to 1.55 |
| Secondary body, captions | 9 to 10 pt | 1.35 to 1.45 |
| Uppercase labels and eyebrows | 8.5 to 10 pt, tracking +0.08em to +0.12em | 1.2 |
| Subheads | 13 to 16 pt | 1.2 |
| Section headlines | 24 to 36 pt | 1.05 |
| Cover display | 44 to 64 pt | 0.95 |

Never set body copy below 9 pt. A print shop will flag it and a reader will
skip it.

Type scale for a 8-page document, using a 1.25 ratio from an 11 pt base:

```
9 pt, 11 pt, 13.75 pt, 17.2 pt, 21.5 pt, 26.9 pt, 33.6 pt, 42 pt, 52.5 pt
```

Set it as variables so the whole document moves together:

```css
:root {
  --step--1: 9pt;  --step-0: 11pt; --step-1: 13.75pt; --step-2: 17.2pt;
  --step-3: 21.5pt; --step-4: 26.9pt; --step-5: 33.6pt; --step-6: 42pt;
  --step-7: 52.5pt;
}
```

Use `hyphens: none`. Justified text without hyphenation produces rivers of
white space; ragged-right reads better in a brochure and needs no hyphenation.

## 8. Folios and the running footer

A folio is the page number as printed. A footer carries it plus the document
name and any disclaimers.

Layout for an A4 page:

```
+--------------------------------------------------+
| 15mm                                             |
|                                                  |
|   content, 172mm wide, 271mm tall                |
|                                                  |
| 11mm                                     03 / 08 |
+--------------------------------------------------+
```

- Position the folio in the bottom right, inside the safe margin.
- Put the document identifier in the bottom left on the same baseline.
- Set both at 9 to 9.5 pt, in the label style with positive tracking.
- Suppress the folio on the cover. Either omit it or use a different line there.
- Run one continuous sequence across the document. Never restart at a section
  break, and never mix `03 / 08` with `03` in the same file.

CSS pattern:

```css
.folio {
  position: absolute;
  bottom: 11mm; right: 19mm;
  font-size: var(--step--1);
  letter-spacing: 0.09em;
  color: var(--muted);
}
```

## 9. Image resolution

- Photographic images at 300 ppi at final placed size, minimum 200 ppi.
- Line art, charts and logos as vectors where possible.
- A 2382 x 3369 px image placed on A4 is about 288 ppi. That is the resolution
  a Chrome screenshot of a 288 dpi page produces.
- Do not upscale. A 72 ppi image enlarged to fill a page is the most visible
  quality failure in a brochure.

## 10. What a print shop will check

Run through this list before claiming a file is print-ready:

1. Page count is even, and a multiple of four for saddle stitch.
2. Trim size stated in millimeters and consistent across pages.
3. Bleed present if requested, and 3 mm wide.
4. All fonts embedded. No live references to a system font.
5. No content in the trim zone.
6. Images at adequate resolution.
7. Color model stated.
8. One PDF, not a folder of per-page files.

If any item is unknown, say so rather than assuming it passed.
