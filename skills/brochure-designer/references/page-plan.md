# Page plan discipline

Reference for stage 3 of SKILL.md. Read this before writing the page plan, and
before drafting any page copy.

The page plan is the cheapest place to catch a bad brochure. A structural fault
fixed here costs one line of text. The same fault found at stage 7 costs a
re-render, a re-typeset, and sometimes a re-cut of the argument.

## Contents

1. Spread roles and rhythm
2. Word budgets per archetype
3. `must-run` and `agent-drafted` content inventory
4. The plan format
5. Worked plan: 8-page transformation brochure
6. Failure modes

## 1. Spread roles and rhythm

Tag every page with a role. The role is not the archetype; it is the job the
page does for the reader.

| Role | Job | Typical archetypes |
| --- | --- | --- |
| `opening` | Stop the reader and earn the next page | `cover`, `landscape` |
| `feature` | Make one hard claim and support it | `landscape`, `pillar-detail` |
| `text-heavy` | Carry an argument that needs sentences | `pillar-detail`, `steps` |
| `gallery` | Show parallel items the reader scans, not reads | `pillars`, `stat-band`, `ladder`, `steps` |
| `closing` | Ask for the next action | `engagement` |

**Rhythm rule: no role appears three times in a row.** Three consecutive
`gallery` pages read as a catalogue. Three consecutive `text-heavy` pages read
as a report. The reader's attention is the scarce resource, and alternating
scannable pages with readable pages is what renews it.

A plan of 8 pages might run:

```
cover            opening
landscape        feature
pillars          gallery
pillar-detail    text-heavy
stat-band        gallery
pillar-detail    text-heavy
architecture     gallery
engagement       closing
```

Note the alternation after the opening pair. Every dense page is followed by a
page the reader can absorb in ten seconds. That is the whole trick.

### Density variation

Rhythm is also visual. Two adjacent pages should not have the same density. If
`pillars` and `stat-band` both fill their page with equal-weight columns, the
document goes flat even though the roles alternate.

Track a coarse density per page: `light` (under half the page filled), `medium`,
`heavy`. No three consecutive pages at the same density. The cover is `light`;
that is deliberate, and the cover's empty middle is the design.

## 2. Word budgets per archetype

A budget is a ceiling on that page's body copy, excluding the eyebrow, the folio,
the footer and the citation line. Set it before writing, and hold it. The budget
is what stops a page from silently becoming a different page.

These figures are calibrated against two delivered 8-page A4 brochures
(`abundent-agentic-ai.pdf`, `abundent-agentic-transformation.pdf`). The method
was to measure the body band (9 to 12.5 pt text, folios excluded) page by page
and set each budget just above the observed maximum, so correct work passes.

| Archetype | Measured body words | Body words | Why |
| --- | --- | --- | --- |
| `cover` | 17-23 | 40 | Display statement plus a standfirst and three one-line pillar labels |
| `landscape` | 167-180 | 230 | A thesis, a lede, three stat readings, a comparison table |
| `pillars` | 136-155 | 190 | Three strands, each a heading and two short panels |
| `pillar-detail` | 175-198 | 260 | One pillar given the page: claim, scoring grid, method |
| `stat-band` | 170-235 | 250 | Numbers dominate; each cell gets one reading and a source |
| `steps` | 115-167 | 190 | Three to five steps, each a heading and one sentence |
| `ladder` | 115-170 | 190 | Five rungs, each a name and a one-line definition |
| `architecture` | 129-180 | 200 | Thin outer layers, six platform modules with one line each |
| `engagement` | 161-171 | 210 | Path, three tiers, contact block |

Stat bands run heavier than their name suggests, because each cell carries a
reading plus its own source line. Budgeting by impression would set it too low
and flag correct pages.

### How to apply a budget

1. Write the copy for the page.
2. Count the words of body text only.
3. If over budget, do not shrink the type. Do one of these instead:
   - cut the weakest sentence, which is usually the second one
   - move a clause to a neighbouring page that is under budget
   - demote a paragraph to a one-line stat reading
   - split the page, if the content genuinely needs two pages, and re-run the
     rhythm rule on the new sequence

Shrinking the type to fit is the failure this budget exists to prevent. It is
also what stage 7's lint will catch, at a point where the fix costs far more.

**A page under budget is not a problem.** Space is a design element. A page that
uses 60 percent of its budget with a generous middle looks deliberate. A page at
100 percent looks crowded even when the type is correct.

## 3. `must-run` and `agent-drafted` content inventory

Before writing, and before designing, list every claim, number, name and
quotation the document will carry, and mark each one.

| Marker | Meaning | Rule |
| --- | --- | --- |
| `must-run` | Supplied by the operator, a source document, or a verified citation | Reproduce verbatim. Never paraphrase, never round, never "improve" |
| `agent-drafted` | Written by you to fill a structural slot | Must be defensible from supplied material, and visible to the operator as drafted |
| `placeholder` | Needs a real value the operator has not supplied | Mark it in the copy and list it in the delivery note |

### Why this exists

The most damaging brochure failure is not a bad layout. It is a confident
sentence stating a number nobody can source. It survives review because it reads
well, and it costs the operator credibility the moment a reader checks.

Marking content `must-run` removes the temptation to smooth an awkward supplied
phrase, and marking `agent-drafted` removes the temptation to present your own
prose as the operator's.

### The inventory format

```
ID    | Marker       | Text / value                          | Source
C1    | must-run     | ">40% of agentic AI projects will be   | Gartner, 25 Jun 2025
                         cancelled by the end of 2027"
C2    | must-run     | "[EMAIL]"                   | brief
C3    | agent-drafted| "Few are selling transformation."     | derived from C1, C4
C4    | must-run     | "~130 of thousands of vendors..."     | brief, verified
P1    | placeholder  | [CLIENT LOGO]                         | awaiting operator
```

### Verification duty

Every `must-run` statistic must be traceable to a source you can name. If the
brief supplies a number with no source, do not invent one and do not silently
drop it: put it in the copy, and flag it in the delivery note as
`unsourced, supplied by operator`.

Every `agent-drafted` line must survive this question: *could the operator
defend this sentence to a client who asked where it came from?* If not, cut it.

## 4. The plan format

Write the plan as a list, one line per page, with five fields:

```
<pages of document>
NN | role | archetype | density | body-word budget
   heading: ...
   folio: NN / TOTAL
```

Keep it in the workspace next to the HTML, because the operator will ask why
page 5 exists.

## 5. Worked plan: 8-page transformation brochure

```
01 | opening     | cover         | light  |  40 words
   heading: Human-led. Agent-run. Data-proven.
   folio: none (cover carries no folio)

02 | feature     | landscape     | heavy  | 230 words
   heading: Everyone is selling agents. Few are selling transformation.
   folio: 02 / 08

03 | gallery     | pillars       | medium | 190 words
   heading: Three strands, one transformation.
   folio: 03 / 08

04 | text-heavy  | pillar-detail | heavy  | 260 words
   heading: KPIs are for machines, not humans.
   folio: 04 / 08

05 | gallery     | stat-band     | light  | 250 words
   heading: The numbers behind the claim.
   folio: 05 / 08

06 | text-heavy  | pillar-detail | heavy  | 260 words
   heading: Process is where the value leaks.
   folio: 06 / 08

07 | gallery     | architecture  | medium | 200 words
   heading: What runs underneath.
   folio: 07 / 08

08 | closing     | engagement    | medium | 210 words
   heading: Start with a readiness diagnostic.
   folio: 08 / 08
```

Check it against the rules:

- roles alternate after page 2, with no run of three
- densities vary; no three consecutive pages share one
- the cover has no folio and the closing page carries the contact block
- every page's budget matches the table in section 2
- total budget is 1,640 words, against the 1,355 and 1,431 words the two
  delivered brochures actually carry: a ceiling, not a target

## 6. Failure modes

| Symptom | Cause | Fix at the plan |
| --- | --- | --- |
| Document feels like a catalogue | Three or more `gallery` pages in a row | Interleave a `feature` or `text-heavy` page |
| Document feels like a report | Consecutive `text-heavy` pages | Add a `stat-band` or `ladder` between them |
| A page was split into two mid-build | It was over budget from the start | Re-budget at stage 3, not stage 7 |
| The argument is unclear | No `feature` page makes the central claim | Name the claim, give it its own page |
| Reader does not know what to do | No `closing` page, or contact buried | Final page is `engagement`, with its own tinted panel |
| Type keeps getting smaller | Copy written before a budget existed | Set the budget first; cut words, never points |
| A number nobody can source | No `must-run` inventory | Build the inventory before drafting |
