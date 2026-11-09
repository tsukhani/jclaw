# Render and verify

Three procedures: mining a reference PDF, rendering HTML to PDF, and verifying
the result. The scripts below are verified working on macOS with Google Chrome,
Ghostscript and PyMuPDF installed.

## Contents

1. Prerequisites
2. Mining a reference PDF (reverse engineering)
3. Rendering HTML to PDF
4. Verifying the rendered PDF
5. Visual inspection without a viewer
6. Troubleshooting
7. Metric alignment: matching a reference to the millimetre
8. Typographic lint: fail the build, do not warn

## 1. Prerequisites

Check these before starting. Each absence changes the pipeline.

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --version
python3 -c "import fitz; print('PyMuPDF', fitz.__doc__)"
python3 -c "import pypdf; print('pypdf ok')"
which gs qpdf magick
```

Expected on this machine: Chrome present, PyMuPDF 1.27, pypdf present,
Ghostscript, qpdf and ImageMagick at `/opt/homebrew/bin`.

If Chrome is missing, `documents renderDocument` can produce a PDF, but it does
not honor `@page size` reliably and it will not embed print backgrounds the way
Chrome does. Prefer Chrome. If only `documents` is available, say in your report
that page geometry was not validated by a print engine.

## 2. Mining a reference PDF

Run this when the operator hands you an existing brochure to match. It returns
the trim size, margins, type scale, palette and per-page text positions.

### 2a. Geometry and type scale

```python
# mine_reference.py — usage: python3 mine_reference.py ref.pdf
import sys, fitz
from collections import Counter

d = fitz.open(sys.argv[1])
print("pages:", len(d))

b = d[0].mediabox
w, h = float(b.width), float(b.height)
print("trim: %.1f x %.1f mm" % (w / 72 * 25.4, h / 72 * 25.4))

for pi in range(len(d)):
    p = d[pi]
    xs, ys = [], []
    sizes = Counter()
    for blk in p.get_text("dict")["blocks"]:
        if blk["type"] != 0:
            continue
        for line in blk["lines"]:
            xs.append((line["bbox"][0], line["bbox"][2]))
            ys.append((line["bbox"][1], line["bbox"][3]))
            for s in line["spans"]:
                if s["text"].strip():
                    sizes[round(s["size"], 1)] += 1
    if not xs:
        continue
    print("page %d margins L%.1f R%.1f T%.1f B%.1f mm" % (
        pi + 1,
        min(a for a, _ in xs) / 72 * 25.4,
        (w - max(b2 for _, b2 in xs)) / 72 * 25.4,
        min(a for a, _ in ys) / 72 * 25.4,
        (h - max(b2 for _, b2 in ys)) / 72 * 25.4,
    ))
    print("   sizes:", sizes.most_common(8))
```

### 2b. Palette

Sampling a rendered raster is more reliable than reading vector fills, because
some generators embed the page as a single image.

```python
# palette.py — usage: python3 palette.py ref.pdf
import sys, fitz
from PIL import Image
from collections import Counter
from io import BytesIO

d = fitz.open(sys.argv[1])
for pi in range(len(d)):
    pix = d[pi].get_pixmap(dpi=40)
    im = Image.open(BytesIO(pix.tobytes("png"))).convert("RGB")
    c = Counter(im.getdata())
    n = sum(c.values())
    top = [("#%02X%02X%02X" % k, round(100 * v / n, 1)) for k, v in c.most_common(6)]
    print("p%d" % (pi + 1), top)
```

Reading the output:

- The first entry is the paper color. Expect 60 to 75% of pixels.
- Any color between 2% and 12% is a real design token, likely the accent or a
  tinted panel.
- Entries below 0.5% are anti-aliasing noise. Ignore them.
- If a page shows two dominant darks (for example `#16171C` and `#262932`),
  the document uses an inverted panel somewhere on that page.

### 2c. Layout positions

Print the text lines with coordinates to recover the column grid and the
vertical rhythm. This is how the 52 mm three-column grid and the 8 mm gutters
in the Abundent preset were found.

```python
# grid.py — usage: python3 grid.py ref.pdf 1 4 8
import sys, fitz

d = fitz.open(sys.argv[1])
for pi in [int(x) - 1 for x in sys.argv[2:]]:
    print("=== page", pi + 1)
    for blk in d[pi].get_text("dict")["blocks"]:
        if blk["type"] != 0:
            continue
        for line in blk["lines"]:
            t = "".join(s["text"] for s in line["spans"]).strip()
            if t:
                print("  x%6.1f y%6.1f w%6.1f sz%5.1f | %s" % (
                    line["bbox"][0], line["bbox"][1],
                    line["bbox"][2] - line["bbox"][0],
                    line["spans"][0]["size"], t[:64]))
```

Group the `x` values: identical `x` positions across lines are column starts.
The difference between successive column starts, minus the text width, is the
gutter.

## 3. Rendering HTML to PDF

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf=brochure-designer/output.pdf \
  "file://$PWD/brochure-designer/output.html"
```

Flags that matter:

| Flag | Why |
| --- | --- |
| `--headless` | No window, no UI chrome in the output |
| `--no-pdf-header-footer` | Removes Chrome's URL and date furniture |
| `--print-to-pdf=PATH` | Absolute or relative path; use `file://` for the input |

Do **not** pass `--print-to-pdf-paper-width` or any paper override. The size
comes from the `@page` rule. An override and an `@page` size disagreeing is the
most common cause of a Letter-sized output from an A4 document.

Chrome writes harmless noise to stderr on macOS:

```
task_policy_set TASK_CATEGORY_POLICY: (os/kern) invalid argument (4)
Trying to load the allocator multiple times.
```

Ignore both. Judge success by the exit code and by the PDF existing with a
non-zero size.

Background colors are included by `--print-to-pdf`. They are **not** included
if the operator later prints from the browser dialog, where "Background
graphics" must be checked. Mention this in the delivery note.

## 4. Verifying the rendered PDF

Run all four checks. Report the numbers.

### 4a. Page count and geometry

```python
# verify.py — usage: python3 verify.py out.pdf 8 210 297
import sys, fitz
from collections import Counter

path = sys.argv[1]
want_pages = int(sys.argv[2])
want_w = float(sys.argv[3])
want_h = float(sys.argv[4])

d = fitz.open(path)
ok = True

if len(d) != want_pages:
    print("FAIL page count: got %d want %d" % (len(d), want_pages))
    ok = False
else:
    print("OK   page count %d" % len(d))

fonts = set()
for pi in range(len(d)):
    p = d[pi]
    b = p.mediabox
    w = float(b.width) / 72 * 25.4
    h = float(b.height) / 72 * 25.4
    if abs(w - want_w) > 0.6 or abs(h - want_h) > 0.6:
        print("FAIL page %d size %.1f x %.1f mm" % (pi + 1, w, h))
        ok = False
    for f in p.get_fonts():
        fonts.add((f[1], f[3]))

    # overflow check: text must sit inside the content box
    x0, _, x1, _ = p.rect
    for blk in p.get_text("dict")["blocks"]:
        if blk["type"] != 0:
            continue
        bb = blk["bbox"]
        if bb[2] > x1 + 0.5 or bb[0] < x0 - 0.5:
            print("WARN page %d text outside width: %.1f to %.1f" % (pi + 1, bb[0], bb[2]))

print("fonts:", sorted(fonts))
print("RESULT:", "PASS" if ok else "FAIL")
```

### 4b. Font embedding

```python
# fonts_check.py — usage: python3 fonts_check.py out.pdf
import sys, fitz
d = fitz.open(sys.argv[1])
for pi in range(len(d)):
    for ext, name, typ, base, res, enc in [
        (f[1], f[3], f[2], f[3], f[4], f[5]) for f in d[pi].get_fonts()
    ]:
        print("p%d %-12s %-10s embedded=%s" % (pi + 1, name, ext, ext not in ("n/a", "")))
```

An `ext` of `n/a` means the font is referenced, not embedded. That will render
wrong on any machine lacking the face, which includes most print shops.

### 4c. Text extraction sanity check

Extract the text and confirm every headline from the page plan appears, and
that no placeholder text (`Lorem`, `TODO`, `XXX`) survived.

```bash
python3 -c "
import fitz,sys
d=fitz.open('brochure-designer/output.pdf')
t='\n'.join(p.get_text() for p in d)
for bad in ['Lorem','TODO','XXX','{{','}}']:
    if bad in t: print('FAIL placeholder present:',bad)
print('chars',len(t))
"
```

If text extraction returns nothing at all, the page was rasterized. That is a
failure: a brochure with no text layer cannot be searched, copied or
accessibility-checked.

### 4d. Raster visual check

```python
# thumbs.py — usage: python3 thumbs.py out.pdf
import sys, fitz, os
d = fitz.open(sys.argv[1])
os.makedirs("/tmp/brochure-thumbs", exist_ok=True)
for i in range(len(d)):
    d[i].get_pixmap(dpi=72).save("/tmp/brochure-thumbs/p%d.png" % (i + 1))
print("wrote", len(d), "thumbnails to /tmp/brochure-thumbs")
```

Then read the images to confirm the layout, rather than trusting the CSS.

## 5. Visual inspection without a viewer

When you cannot view an image directly, build a coarse block map to check the
overall composition. This catches a page with no clear focal point, a page whose
content has collapsed into a single column, and a page with an accidental empty
band.

```python
# blockmap.py — usage: python3 blockmap.py out.pdf 1
import sys, fitz
from io import BytesIO
from PIL import Image

d = fitz.open(sys.argv[1])
for pi in [int(x) - 1 for x in sys.argv[2:]]:
    pix = d[pi].get_pixmap(dpi=40)
    im = Image.open(BytesIO(pix.tobytes("png"))).convert("RGB").resize((70, 99))
    px = im.load()
    print("=== page", pi + 1)
    for y in range(0, 99, 2):
        row = ""
        for x in range(70):
            r, g, b = px[x, y]
            if r > 225 and g > 220 and b > 210:
                row += "."
            elif r > 140 and g < 110 and b < 90:
                row += "R"        # accent
            elif r < 75 and g < 75 and b < 85:
                row += "#"        # ink
            elif r < 110 and g < 115 and b < 125:
                row += "D"        # graphite
            else:
                row += "o"        # mid tone or tint
        print(row)
```

Read the map for these signatures:

- A page where every row is dense from edge to edge has no breathing room.
- A page with more than 15 consecutive empty rows mid-page has a layout gap.
- An accent (`R`) appearing on more than 20% of a page means the accent is
  overused.

## 6. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Output is 8.5 x 11 in, not A4 | Paper override flag, or `@page` missing `size` | Remove the flag, keep `size` in CSS |
| 9 pages instead of 8 | Content overflowed one page | Reduce copy or a type step; never add a page |
| Blank final page | Trailing `page-break-after` | Add `.page:last-of-type { page-break-after: auto; }` |
| Text is scaled down ~4% | Chrome applied "fit to printable area" | Set `@page { margin: 0 }` so there is nothing to fit to |
| Backgrounds absent | Rendered by a tool that drops backgrounds | Use `--print-to-pdf`, which keeps them |
| Accent color shifts | Pre-converted CMYK without a profile | Render sRGB and let the print shop convert |
| Chinese or symbol glyphs disappear | Font lacks the glyphs | Switch to a face with full coverage; check for "Missing character" warnings |
| Images blurry | Low ppi source enlarged | Use a higher resolution source; never upscale |
| Text extraction empty | Page rasterized | Render HTML to PDF, do not screenshot it |

## 7. Metric alignment: matching a reference to the millimetre

Use this when the brief says "faithful recreation" rather than "inspired by".
Verified on 2026-11-06 rebuilding the Abundent brochure to a mean anchor error
of 0.85 mm across 366 text anchors.

The loop is four steps. Do not skip step 1; every later step depends on it.

### 7.1 Detect that the reference is a raster

Many generated PDFs carry an invisible text layer over a full-page JPEG, and
the text layer's font metrics are **synthetic**, not the real ones. Detect this
before trusting any `get_text("dict")` font size:

```python
d = fitz.open(ref)
print(d[0].get_images(full=True))
print(d[0].get_fonts())
```

If `get_images` returns one image at roughly page resolution (2382 x 3369 for
A4) and `get_fonts()` returns a single face named `Glyphless`, the page is a
picture. Every visible measurement must then come from the raster, by
thresholding to a binary ink mask:

```python
a = np.asarray(Image.open(io.BytesIO(d[pg].get_pixmap(dpi=300).tobytes("png")))
               .convert("L")).astype(float)
ink = a < 200          # 170-200 works for near-black text on off-white paper
```

Getting this wrong is the single biggest source of error. On the reference
build, trusting the text layer's 59 pt led to a face that was 9% too narrow.

### 7.2 Identify the typeface by aspect ratio, not by width

A correct face reproduces a string's width-to-cap-height ratio at **any** size,
because the ratio is size-invariant. Width alone is not, so match the ratio.

1. Pull one known string's ink box from the reference raster.
2. Render the same string in each candidate at 100 pt on white, at 200-300 dpi.
3. Threshold both to ink, crop to the ink box, then rescale each to a common
   cap height (120 px worked) and compare widths.

```python
def norm(mask, target_h=120):
    h, w = mask.shape
    im = Image.fromarray((mask * 255).astype("uint8"))
    return np.asarray(im.resize((max(1, int(w * target_h / h)), target_h))) > 127
```

The winner is the one whose normalized width matches. On the reference build
Helvetica Neue 400 gave 6.983 against the original's 6.967; every serif was off
by more than 15%, which settled a serif-versus-sans argument that width testing
alone had left ambiguous.

### 7.3 Calibrate size and tracking jointly

Size and `letter-spacing` both change width, so solving one at a time
oscillates. Sweep both against one string with a known target width **and**
known ink height, and minimize the sum of relative errors:

```python
err = abs(w - target_w) / target_w + abs(h - target_h) / target_h
```

Render the sweep in one HTML file with `line-height` large enough to separate
adjacent rows, then band-detect the raster. With `line-height: 1.7` and ink
thresholding, bands never merge.

Do a separate sweep per type class. On the reference build the cover display,
section heads, ledes and body copy resolved to different sizes **and** different
weights:

| Class | Face | Size | Tracking |
| --- | --- | --- | --- |
| Cover display | HN 500 | 59 pt | 0 |
| Section head | HN 500 | 34.5 pt | 0 |
| Page-2 head | HN 500 | 31 pt | 0 |
| Lede | HN 400 | 13.2 pt | -0.005em |
| Body | HN 400 | 11.7 / 10.1 pt | 0 |
| Labels | HN 600 | 9.8 pt | +0.08em |
| Wordmark | HN 400 | 12.3 pt | +0.13em |

Note that body copy was 400 while display was 500. Setting `font-weight: 500`
on `body` as a shortcut widened every paragraph and cost more than it saved.

### 7.4 Iterate using content-keyed anchors

Comparing band positions page-wide is noisy because a solid block and a line of
text produce different bands. Compare **text-anchor positions**, keyed by the
line's own words so the two documents pair correctly even when one wraps
differently:

```python
def anchors(doc, pg):
    out = {}
    for blk in doc[pg].get_text("dict")["blocks"]:
        if blk["type"] != 0: continue
        for l in blk["lines"]:
            t = "".join(s["text"] for s in l["spans"]).strip()
            if t:
                out.setdefault(re.sub(r"\W+", "", t.lower())[:34], []).append(
                    (round(l["bbox"][1] / 72 * 25.4, 1), t))
    return out

for pg in range(8):
    lo, lm = anchors(ref, pg), anchors(out, pg)
    ds = [abs(lm[k][0][0] - lo[k][0][0]) for k in lo if k in lm]
    print("P%d n=%d mean %.2f mm" % (pg + 1, len(ds), sum(ds) / len(ds)))
```

Report the mean in millimetres per page and overall. Anything above about
1.5 mm is a real defect worth chasing; below 1 mm the difference is invisible.

Use the same diff to find **missing** elements: any key present in the
reference but absent from the rebuild. This is how three pillar icons on page 3
and the whole stepped ladder on page 6 were caught.

### 7.5 Set line pitch in millimetres, not ratios

Unitless `line-height` is relative to font size, so a 10.1 pt paragraph and a
13.2 pt paragraph with `line-height: 1.49` land on different baselines and drift
against a reference that used a fixed leading. Measure the reference pitch —
the vertical distance between the first ink line and the second — and set it
absolutely:

```css
.lede     { font-size: 13.2pt; line-height: 6.95mm; }   /* pitch 6.95 mm */
.body     { font-size: 11.7pt; line-height: 5.80mm; }
.caption  { font-size: 10.1pt; line-height: 4.80mm; }
body      { font-size:  9.2pt; line-height: 4.30mm; }
```

This removed a persistent 2-4 mm drift on the second and later lines of every
wrapped paragraph.

### 7.6 Watch for orphaned CSS declarations

An edit that removes a selector but leaves its declaration block produces a
rule that silently applies to the wrong element. On the reference build a stray
`display:grid; grid-template-columns:...` line, left behind when its
`.ladder-cap{` selector was lost, made a caption grid render at the top of a
page. Symptom: a whole cluster of lines 180 mm from where they belong, and the
band count still matching.

Check the stylesheet for lines starting with a property that follows a closing
brace with no selector:

```bash
grep -n -B1 -E "^  [a-z-]+:" brochure.css | grep -A1 "^--$" | head
```

Or simply read the region you just edited. Cheaper than chasing the symptom.

### 7.7 Check margins on the ink, not the text layer

`get_text()` bbox values come from the synthetic text layer and can differ from
the visible ink by a millimetre. Verify margins by thresholding the raster:

```python
a = np.asarray(Image.open(BytesIO(d[pg].get_pixmap(dpi=200).tobytes("png")))
               .convert("L")).astype(float)
H, W = a.shape
m = a < 170
r, c = np.where(m.any(axis=1))[0], np.where(m.any(axis=0))[0]
print("L%.1f R%.1f T%.1f B%.1f" % (
    c.min() / W * 210, (W - c.max()) / W * 210,
    r.min() / H * 297, (H - r.max()) / H * 297))
```

Run this on the reference too and target its numbers, not the nominal 19 mm.
The reference build's footers sat at bottom 10.4 mm, not the 11 mm the spec
suggested.

## 8. Typographic lint: fail the build, do not warn

Sections 4 and 7 catch geometry. This section catches typography, which is where
a technically valid PDF still reads badly. The rule is enforcement, not advice:
a broken page fails the build and gets re-rendered.

```bash
skills/brochure-designer/tools/typolint out.pdf
skills/brochure-designer/tools/typolint out.pdf --json   # machine-readable
```

Exit 0 is a pass. Exit 1 means at least one FAIL. A run prints a header line,
then one line per FAIL, then WARNs, then `note` lines carrying the measured
values so the numbers reach the operator.

### What it checks

| Code | Severity | Fires when |
| --- | --- | --- |
| `RASTER` | FAIL | No text layer: the page was screenshotted, not rendered |
| `TYPE_SCALE` | FAIL | More than 10 distinct sizes after clustering within 0.5 pt |
| `BODY_SIZE` | FAIL | Dominant body size below 9 pt |
| `TEXT_TOO_SMALL` | FAIL | 200+ characters under the absolute 7 pt floor |
| `TEXT_TOO_SMALL` | WARN | 200+ characters in the 7-9 pt band, acceptable for sources only |
| `MEASURE_WIDE` | FAIL | Body measure p90 above 90 characters |
| `MEASURE_NARROW` | FAIL | Column over 110 mm wide holding fewer than 45 characters |
| `LEADING_TIGHT` | FAIL | Body line pitch below 1.25 em |
| `LEADING_LOOSE` | FAIL | Body line pitch above 1.75 em |
| `FAMILIES` | FAIL | More than two type families |
| `ORPHAN_HEADING` | FAIL | Page ends on a display heading with no body beneath it |
| `PLACEHOLDER` | FAIL | `lorem`, `TODO`, `{{`, `[insert` or `TBD` survived |
| `FONT_EMBED` | FAIL | A non-base-14 font is referenced but not embedded |
| `FONT_EMBED` | WARN | A base-14 face is unembedded: legal, but may be substituted |

### Why the measure check is physical, not a column count

Counting columns from left edges does not work. Indented paragraphs, hanging
captions and right-aligned stat numbers all produce plausible extra column
starts, and on a real two-column brochure this reported 4, then 5, then 8
columns on the same file.

Judge the measure physically instead. A narrow column legitimately holds few
characters because it is narrow, so `MEASURE_NARROW` fires only when a column
wider than 110 mm holds fewer than 45 characters, which means the type is too
large or the tracking is wrong, not that the grid is narrow. `MEASURE_WIDE` is
unconditional above 90 characters, because no grid makes 95 characters
comfortable.

### Thresholds are calibrated, not guessed

Defaults were tuned against two real 8-page A4 brochures so that correct work
passes cleanly:

| File | Result |
| --- | --- |
| `abundent-agentic-ai.pdf` | PASS: 11.7 pt body, 10 type steps, 1 family, 1.41 em leading |
| `abundent-agentic-transformation.pdf` | PASS: 10.0 pt body, 10 steps, 2 families, 1.42 em leading, with WARNs on 7.5 and 8.8 pt source blocks |

Both brochures are legitimate designs, so a lint that flagged them would have
been wrong. The first version of this script did flag them, on three counts:

- it counted 20 type sizes because it treated 11.6, 11.7 and 11.8 pt as
  separate steps. They are one step; clustering within 0.5 pt fixed it.
- it failed the measure at 37 characters, reading a 41 mm column in a
  three-column grid as a defect. It was a correct design; the physical rule
  above fixed it.
- it flagged 108-113 character source footers as uncontrolled long lines. Those
  are set as full-width 9.2 pt source blocks, which is a deliberate and correct
  choice in a reference line. The unconditional long-line rule was removed; the
  p90 measure rule covers the real failure.

Override a threshold with a flag only when the brief justifies it, and say which
flag you overrode in the delivery note.

### Verification status

Every rule was driven by a deliberately broken fixture on 2026-11-06: an
over-sized type scale, a 5 pt body, a 150-character measure, 1.0 em leading,
three type families, `Lorem ipsum`, and a heading stranded at a page foot. Each
one fired on its own fixture and stopped firing once the defect was corrected,
and the unbroken control fixture passed. The lint is enforcement, not decoration.

### Enforcing the stage 3 word budgets

Pass the per-page budgets to the lint so the ceiling set at stage 3 survives to
the render. One integer per page, in plan order:

```bash
skills/brochure-designer/tools/typolint out.pdf --budget 40 230 190 260 250 190 200 210
```

Use `0` for a page that has no budget. The report carries a `note` line per page
showing usage as a percentage, and `OVER_BUDGET` FAILs any page above its
ceiling. `BUDGET_ARITY` FAILs if the count of integers does not match the page
count, which catches a plan that drifted from the document.

The distinction matters: this is a **copy** check, not a typesetting one. A page
over budget gets words cut or moved to an under-budget page. It never gets its
type shrunk, because that trades a visible problem for an invisible one and the
lint would then flag the resulting type size too.

### A FAIL is a stop, not a discussion

Do not deliver a PDF that reports FAIL, and do not lower a threshold to make it
pass. Fix the page, re-render, re-run. If the operator wants the exception, get
it in writing and record it in the delivery note.

## Empty-band detector (learned 2026-11-06)

Rasterize each page at 50 dpi and find runs of rows with under 0.3% ink. A run of 25 mm or more mid-page means content collapsed: fix the layout, do not add a page. This caught 46-70 mm gaps on 3 pages of the Abundent rebuild that a visual check missed.
