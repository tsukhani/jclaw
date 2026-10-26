---
name: in-depth-research
description: >-
  Conduct exhaustive multi-source investigation with methodology tracking, source evaluation, and iterative depth.
metadata:
  version: "1.0.1"
  author: clawhub.ai/ivangdavila/in-depth-research
  jclaw-icon: "🔬"
  jclaw-tools: web_search web_fetch browser filesystem checklist memory
  jclaw-commands: ""
---
## Core Role

Deep Research = investigate thoroughly until the question is answered. Not surface search — systematic exploration with documented methodology.

**Not:** quick lookups (→ just search), combining existing docs (→ Synthesize), ongoing monitoring (→ Digest)

## Protocol

```
Scope → Search → Evaluate → Deepen → Synthesize → Document → Deliver
```

### 1. Scope

Before searching, clarify:
- What exactly needs answering?
- What depth is required? (Overview / Thorough / Exhaustive)
- What's the decision this enables?
- Time/effort budget?

Reframe vague questions into specific, answerable queries.

### 2. Search

Multi-vector approach (see `methodology.md`):
- Start broad, then narrow
- Multiple search engines/sources
- Follow citation trails
- Check primary sources when secondary cite them
- Look for contradicting viewpoints

Track every source. Nothing unattributed.

### 3. Evaluate

For each source (see `sources.md`):
- Authority: Who wrote this? What credentials?
- Recency: When? Still valid?
- Evidence: Claims backed by data?
- Bias: Any agenda or conflict?
- Corroboration: Do others confirm this?

Flag low-credibility sources. Weight findings accordingly.

### 4. Deepen

Research is iterative:
- Initial findings reveal new questions
- Follow promising threads
- Fill gaps identified
- Stop when: answer is clear, returns diminish, or budget exhausted

Document decision to stop and why.

### 5. Synthesize

Merge findings (use Synthesize skill patterns):
- Reconcile contradictions explicitly
- Weight by source quality
- Note confidence levels
- Identify remaining unknowns

### 6. Document

Research trail matters:
- Sources consulted (with links)
- Search queries used
- Why certain sources were weighted higher
- What was NOT found (gaps)

### 7. Deliver

Format per user needs (see `output-formats.md`):
- Executive: BLUF + key findings + confidence
- Academic: Full methodology + citations
- Working doc: All findings for further work

## Output Format (Default)

```
🔬 DEEP RESEARCH: [Topic]

⚡ ANSWER
[Direct answer to the question — 2-3 sentences]

📊 CONFIDENCE: [High/Medium/Low] — [why]

🔍 KEY FINDINGS
• [Finding 1] — [source]
• [Finding 2] — [source]
• [Finding 3] — [source]

⚠️ CAVEATS
• [Important limitation or uncertainty]

🕳️ GAPS
• [What couldn't be determined]

📚 SOURCES ([count])
[Numbered list with credibility notes]

🔎 METHODOLOGY
[Brief: what was searched, how sources were evaluated]
```

## Depth Levels

| Level | Effort | Sources | When |
|-------|--------|---------|------|
| Quick | 5-10 min | 3-5 | Simple factual questions |
| Standard | 30-60 min | 8-15 | Most research requests |
| Thorough | 2-4 hours | 20-30 | Important decisions |
| Exhaustive | Days | 50+ | Critical, high-stakes |

Confirm depth before starting. Adjust if findings warrant.

---

*References: `methodology.md`, `sources.md`, `output-formats.md`*
