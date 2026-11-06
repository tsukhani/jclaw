# tools/typolint

Fail-the-build typographic lint for print brochures. Pure Python, requires
PyMuPDF (`pip install pymupdf`).

```bash
skills/brochure-designer/tools/typolint out.pdf            # human report
skills/brochure-designer/tools/typolint out.pdf --json     # machine report
```

Exit code 0 = pass, 1 = at least one FAIL. WARN never fails the build.

## Rules

| Code | Severity | Fires when |
| --- | --- | --- |
| `RASTER` | FAIL | No text layer, page was rasterized |
| `TYPE_SCALE` | FAIL | More than 10 distinct type sizes after clustering |
| `BODY_SIZE` | FAIL | Dominant body size below 9 pt |
| `TEXT_TOO_SMALL` | FAIL | 200+ characters below the absolute 7 pt floor |
| `TEXT_TOO_SMALL` | WARN | 200+ characters in the 7-9 pt band (footnotes are fine) |
| `MEASURE_WIDE` | FAIL | Body measure p90 above 90 characters |
| `MEASURE_NARROW` | FAIL | Column over 110 mm wide holding under 45 characters |
| `LEADING_TIGHT` | FAIL | Body line pitch below 1.25 em |
| `LEADING_LOOSE` | FAIL | Body line pitch above 1.75 em |
| `FAMILIES` | FAIL | More than two type families |
| `ORPHAN_HEADING` | FAIL | Page ends on a display heading with no body under it |
| `PLACEHOLDER` | FAIL | lorem / TODO / `{{` survived into the render |
| `FONT_EMBED` | FAIL | A non-base-14 font is referenced but not embedded |
| `FONT_EMBED` | WARN | A base-14 face is not embedded (valid, but may be substituted) |
| `OVER_BUDGET` | FAIL | A page exceeds its stage 3 body-word budget |
| `BUDGET_ARITY` | FAIL | `--budget` listed a different page count than the PDF has |

## Flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--body-min` | 9.0 | Print legibility floor in points |
| `--max-sizes` | 10 | Maximum number of type steps |
| `--measure-min` | 45 | Minimum characters per line |
| `--measure-max` | 90 | Maximum characters per line |
| `--footnote-min` | 7.0 | Absolute floor; anything smaller always fails |
| `--budget` | off | Per-page body-word budgets, one integer each, `0` to skip |
| `--json` | off | Emit a JSON report instead of text |

## Verification status

Every rule was tested against a deliberately broken fixture on 2026-11-06; each
one fires on its own fixture and stops firing when the defect is fixed. The
default thresholds were tuned against two 8-page A4 brochures in this workspace
so that correct work passes: `brochure-designer/abundent-agentic-ai.pdf` and
`brochure-designer/abundent-agentic-transformation.pdf` both report PASS.
