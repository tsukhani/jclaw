---
name: research-paper-writer-pro
description: Drafts research papers and typesets them in one house style — A4 single-column LaTeX article in Palatino with author–year APA references, numbered propositions and equations, booktabs tables and matplotlib figures — then compiles them to PDF and checks the result.
version: 1.0.0
author: clawhub.ai/earthwalking/research-paper-writer-pro
tools: [exec, filesystem]
commands: []
icon: 📝
---

# Research Paper Writer

Produces a complete paper as a LaTeX project and a compiled PDF in one fixed look. The look is not negotiable per paper; the content and the section plan are. Two genres are supported: a **formal conceptual paper** (theory synthesis with numbered results and a worked application) and an **empirical paper** (IMRAD). Both use the same template.

## The look

Every paper this skill emits has this appearance. Do not deviate unless the operator asks.

- **Page.** A4, 11pt, single column, 1-inch margins. Palatino for text and math (`newpxtext`, `newpxmath`). Paragraphs separated by a blank line, no first-line indent (`parskip`).
- **Title block.** Bold title, a subtitle in regular weight on the next line, then author, affiliation, e-mail as a link, and the date, all centred. No abstract box: the abstract is a narrower `\small` block under a centred bold "Abstract", followed by a full-width **Keywords:** line with semicolon-separated terms.
- **Running head.** Short title at the left of every page after the first, author initial and surname at the right, a thin rule beneath; page number centred in the footer.
- **Headings.** Numbered `\section` and `\subsection`, bold, article-class sizes. Back-matter sections (`Data, code, and AI assistance`, `Numerical illustration and reproducibility`, `References`) are unnumbered. The appendix is lettered (`A.1`, `A.2`).
- **Results.** `proposition`, `corollary`, `lemma`, `theorem` in the plain theorem style: bold label with a descriptive name in parentheses, italic body. `definition` and `assumption` in upright text. Proofs live in the appendix, one subsection per result.
- **Mathematics.** Displayed equations are numbered on the right and cited as `Equation~\eqref{eq:name}`; inline symbols are introduced before use.
- **Tables.** `booktabs` rules only, no vertical rules, caption **above** with a bold `Table N:` label, `\small` body, ragged paragraph columns for text.
- **Figures.** Vector PDF from the bundled matplotlib style, caption **below** with a bold `Figure N:` label, one to two panels, panel titles as `A. …` / `B. …`. Blue solid, red dashed, gold dash-dot, grey dotted series, light grid, no top or right spines.
- **Citations.** Author–year in text, `\citep{a, b}` → (Grieco, 1988; Jervis, 1978), `\citet{a}` → Jervis (1978). Reference list in APA (apacite through BibTeX), alphabetical, hanging indent, DOIs and URLs as navy links in the text face.
- **Links.** One navy for internal references, citations and URLs (`RGB 0,0,112`); nothing boxed.

The template that encodes all of this is `templates/paper.tex`. Copy it; never rebuild the preamble by hand.

## Inputs to collect before drafting

Ask for what is missing; do not invent it.

1. Title and subtitle; author, affiliation, e-mail; date.
2. Genre: formal conceptual or empirical.
3. The question in one sentence, and the two to four contributions.
4. The sources: a list of works the author already knows, or permission to search. Every reference in the paper must be real and verified (title, authors, year, venue, DOI or URL). Unverifiable works are left out, never guessed.
5. Figures and tables the author wants, with the data or the formulas that produce them. Illustrative numbers are allowed only when the caption says so.
6. Target length in pages, and any journal the paper is for (the look stays; the section plan may bend).

## Workflow

1. **Project layout.** Create `<slug>/` with `paper.tex` (copied from `templates/paper.tex`), `references.bib`, `figures/`, `figures.py` (copied), and one script per figure (`fig1.py`, …). Set `\shorttitle`, `\shortauthor`, the title block, and the abstract placeholders first.
2. **Outline.** Write the section plan from the genre blueprint below, with one line per section stating what it establishes. Get the author's agreement before drafting long sections.
3. **References first.** Fill `references.bib` from the verified sources, in standard BibTeX fields (`journal`, `year`, `@misc` with `url` and `note`); wrap book titles and proper nouns in double braces so APA's sentence case leaves them alone. Cite keys as you write; a `\citep` to a key not in the file is a build error, which is the point.
4. **Draft section by section** in the file, in the paper's order. Introduce every symbol and number every result. Cross-reference only by label.
5. **Figures and tables.** Generate each figure with `figures.py` conventions (`house_style()`, `save(fig, "figN")`), reference it in the text before it appears, and write the caption as a complete sentence that names what the numbers are and are not.
6. **Build.** `latexmk -pdf -interaction=nonstopmode paper.tex` in the project directory (BibTeX and the extra passes run automatically). Fix every error and every undefined citation or reference before reading the PDF.
7. **Check** against the list below, then deliver the PDF, the `.tex`, the `.bib` and the figure scripts.

## Section blueprints

### Formal conceptual paper

This is the shape of the reference paper this look was taken from (about 15 pages of body plus appendix and references).

1. **Introduction.** The question in plain words; the literatures it sits in, cited; the narrower question this paper answers, in italics; the contributions, numbered in prose; what the contributions are and are not.
2. **Research design and theoretical foundations.** `Research design` (the kind of paper, why these theories, the analytical steps). Then one subsection per foundation, each ending with what the paper takes from it and what it does not claim.
3. **Framework and units of analysis.** Objects, levels of analysis, the channels or mechanisms, and a table mapping each mechanism to its formal inputs, its conditional implication and the empirical question it leaves open.
4. **Formal analysis.** One subsection per mechanism: setup, a numbered proposition or corollary with a descriptive name, prose on what it does and does not establish, a figure or table where numbers help, always declared illustrative.
5. **Interpretation.** How the results connect by number; the mistaken substitutions the separation prevents; how apparent counterexamples read under the framework.
6. **Worked application.** Define the activity before assigning parameters; a numerical diagnostic labelled as not a calibration; add a duration or dynamic scenario only with separate evidence.
7. **Empirical development and falsifiable implications.** Separate the tests; identification and the observation model; discriminating implications.
8. **Discussion and conclusion.** What is distinguished, what is not claimed, the empirical priorities in order.
9. **Data, code, and AI assistance** (unnumbered). Scripts per figure and table, where they live, what AI assistance did.
10. **Appendix: Proofs and supplementary derivations.** One subsection per result.
11. **Numerical illustration and reproducibility** (unnumbered). Parameters and seeds per figure.
12. **References.**

### Empirical paper

1. Introduction (background, gap, question, hypotheses, contribution).
2. Related work.
3. Methods (design, participants or data, materials, procedure, analysis; reproducible).
4. Results (descriptives, checks, hypothesis tests with effect sizes and intervals; text first, then the table or figure).
5. Discussion (findings, comparison with prior work, implications, limitations, future work).
6. Conclusion.
7. Data, code, and AI assistance (unnumbered); Appendix for supplementary tables; References.

Abstracts are one paragraph: 200–300 words for the conceptual genre, 150–250 for the empirical one. Keywords: four to six.

## Writing rules

- Plain declarative sentences. One idea per sentence. No rhetorical questions after the introduction, no "it is worth noting", no "novel", "seminal", "paradigm".
- Every claim is scoped: "under the assumptions of Proposition 2", "for the specified punishment rule". A bound is not an estimate; say which one you have.
- Numbers that are assumed are labelled assumed, in the sentence and in the caption. Never present an illustrative number as a finding.
- Name what the paper does not do, in the introduction and in the conclusion.
- Cite when a claim rests on someone else's work; do not cite to decorate. Two to four citations per claim at most.
- Symbols: define on first use; one meaning per symbol for the whole paper; consistent subscripts.

## LaTeX rules

- Copy the template; edit only the marked regions. The preamble is the look.
- Labels: `sec:`, `subsec:`, `eq:`, `prop:`, `cor:`, `lem:`, `def:`, `tab:`, `fig:`. Refer with `\ref`, `\eqref`, never with hard-coded numbers.
- Tables: `\begin{table}[t]`, `\caption` before the `tabular`, `\toprule`/`\midrule`/`\bottomrule` only, `L{…}` (ragged) columns for text so rows wrap without stretched spacing; keep the widths summing under 14 cm.
- Figures: `\begin{figure}[t]`, `\includegraphics[width=0.9\textwidth]{figures/figN.pdf}`, caption after.
- Results: `\begin{proposition}[Descriptive name]\label{prop:x} … \end{proposition}`; proofs in the appendix with `\begin{proof}`.
- Citations: `\citep{key}` in parentheses, `\citet{key}` as a sentence subject, `\citep[p.~12]{key}` for pages. Multiple keys in one `\citep` are printed alphabetically, as APA requires.
- Bibliography engine: the template uses apacite with BibTeX because that builds on any TeX Live. biblatex-apa is the exact-APA-7 alternative when a working `biber` exists; the swap is four lines and is described at the top of the template's references block. On this machine the MacTeX 2025 `biber` wrapper fails to extract its binary and the Homebrew TeX ships none, so stay on BibTeX here.
- Do not load extra packages for one effect; ask first. `microtype`, `enumitem`, `csquotes` are already loaded.

## Figures

`templates/figures.py` fixes the style. Each figure is its own script that imports it:

```python
from figures import house_style, save, PALETTE, panel_title
house_style()
fig, (a, b) = plt.subplots(1, 2, figsize=(6.6, 3.2))
panel_title(a, "A", "Latent threshold crossing"); panel_title(b, "B", "Independent behavioral delay")
...
save(fig, "fig3")   # writes figures/fig3.pdf for LaTeX and figures/fig3.png for previews
```

Series take colours and line styles from the palette in order; do not pick colours ad hoc. Shaded regions use `SHADES`. Axis labels are full phrases with the symbol ("Positional rivalry, α"). Legends inside the axes. No titles on single-panel figures; the caption carries the description.

Run figure scripts with the project directory as the working directory so `figures/` lands next to `paper.tex`. If matplotlib is missing, `uv run --with matplotlib --with numpy python figN.py` works without installing anything.

## Checks before delivery

- [ ] `latexmk` exits 0 with no `Undefined citation`, `Undefined reference`, or `Overfull \hbox` wider than 10pt in the log.
- [ ] Every figure and table is referenced in the text before it appears; every label is used.
- [ ] Every result has a descriptive name, a scope sentence, and a proof or derivation in the appendix.
- [ ] Every reference in `references.bib` was verified against its DOI or URL; none were invented.
- [ ] Assumed numbers say so in the sentence and in the caption.
- [ ] Abstract and keywords within length; the running head fits on one line.
- [ ] Page 1 matches the look: bold title, regular subtitle, author block, centred "Abstract", keywords line, "1 Introduction" starting on the same page.

## Deliverables

- `paper.pdf`
- `paper.tex`, `references.bib`, `figures/*.pdf`, `figures.py` and the per-figure scripts
- A short note listing any reference that could not be verified and was therefore omitted

## License and caveats

MIT-0. Generated claims, statistics and references need review against authoritative sources before academic use; the skill omits any reference it cannot verify rather than guessing one. The only code it asks to run is the bundled template, the figure helper and per-figure scripts through the local LaTeX toolchain and Python; review any figure script before running it.

## Files in this skill

- `templates/paper.tex` — the house-style template with placeholder sections.
- `templates/references.bib` — three verified sample entries showing article, book and online forms.
- `templates/figures.py` — the matplotlib style, palette and `save` helper.
- `templates/fig_example.py` — a worked single-panel figure (log–log bounds, four series) to copy for new figure scripts.
