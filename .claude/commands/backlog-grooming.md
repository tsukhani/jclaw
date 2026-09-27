---
name: backlog-grooming
description: Groom the JCLAW Jira backlog — consolidate duplicate and overlapping stories, link every open story to a matching epic (creating epics where none fits), estimate unpointed stories, and plan them into the active and future v0.N sprints within measured velocity, each sprint with a real goal and a feature/NFR balance. Plans first; writes to Jira only after approval.
category: Planning
tags: [jira, backlog, sprint, planning, estimation, consolidation]
argument-hint: "[empty | plan] [capacity=<points>] [nfr=<percent>]"
---

**Backlog Grooming**

Brings the JCLAW backlog into a plannable state in five passes — consolidation, epics, estimates, sprints, goals — against live Jira data. Every pass lands in one plan, and nothing is written until the operator approves it: Jira is shared state, and a wrong bulk write means hand-repairing dozens of issues.

**Arguments** — `$ARGUMENTS` may combine, in any order:

- *(empty)* → survey, build the plan, ask for approval, apply, verify.
- `plan` → survey and build the plan only; no question, no Jira writes.
- `capacity=<points>` → override the velocity-derived sprint capacity (step 4).
- `nfr=<percent>` → target share of each sprint's points for non-functional work (default `30`).

Reject anything else with a clear message; do not guess.

---

**Jira access**

Reads may use the `mcp__jira-confluence__*` tools. Every **write** goes through REST: the MCP's Markdown converter corrupts descriptions, and `jira_link_to_epic` reports success without writing. Put this helper in the session scratchpad and import it from each phase's script; never print the token.

```python
import json, os, urllib.error, urllib.parse, urllib.request
env = json.load(open(os.path.expanduser("~/.claude.json")))["mcpServers"]["jira-confluence"]["env"]
BASE, TOKEN = env["JIRA_URL"].rstrip("/"), env["JIRA_PERSONAL_TOKEN"]

def api(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:500]

def search(jql, fields):
    out, start = [], 0
    while True:
        q = urllib.parse.urlencode({"jql": jql, "fields": fields, "maxResults": 100, "startAt": start})
        code, d = api("GET", "/rest/api/2/search?" + q)
        if code != 200:
            raise RuntimeError(f"JQL rejected ({code}): {d}")
        for i in d["issues"]:
            i.setdefault("fields", {})  # Jira omits "fields" when none of the requested ones exist on the issue type
        out += d["issues"]; start += len(d["issues"])
        if start >= d["total"] or not d["issues"]:
            return out
```

Facts of this instance (verified 2026-09-26; if a call fails on one, re-read it rather than guess):

| What | Value |
| --- | --- |
| Project / board | `JCLAW` (id 12400) / board **30** |
| Sprint / Epic Link / Epic Name / Story Points | `customfield_10001` / `customfield_10002` / `customfield_10004` / `customfield_10006` |
| Epic issue type id | `10000` |
| An epic's children | `"Epic Link" = JCLAW-N` — `parent = …` matches nothing on this Server instance |
| Story Points write | only `PUT /rest/agile/1.0/issue/{key}/estimation?boardId=30`; the field is on no edit screen. Bug and Task issues do not have the field at all: the PUT answers 400 "not editable due to its issue type", and `"Story Points" is EMPTY` never matches them |
| Link types | `Duplicate` (duplicates / is duplicated by), `Relates`, `Blocks`, `Cloners`, `Problem/Incident` |
| Closing | transitions are To Do 11, In Progress 21, Done 31, Test 41, Review 51; there is no Won't Do or Duplicate resolution that can be applied — 31 stamps Done |

---

**Phase 1 — Survey (read-only)**

1. **Sprints** on board 30, all states (`/rest/agile/1.0/board/30/sprint`, paginate until `isLast`). Record the active sprint, any future sprints, and the highest `v<major>.<minor>` sprint name in any state — the naming rubric continues from it.
2. **Open issues**: `project = JCLAW AND statusCategory != Done ORDER BY Rank ASC` with `summary, description, issuetype, status, priority, labels, fixVersions, issuelinks, comment, customfield_10001, customfield_10002, customfield_10006`. Backlog rank is the operator's own ordering and is the final tiebreak everywhere below.
3. **Epics**: open epics with summary, description, labels and open-child count; closed epics' summaries too. A story that matches only a closed epic gets a new follow-up epic — never a link to the closed one.
4. **Capacity**: completed points of the last three closed sprints from `GET /rest/greenhopper/1.0/rapid/charts/sprintreport?rapidViewId=30&sprintId=<id>` (`contents.completedIssuesEstimateSum.value`). Capacity is their median unless `capacity=` overrides it. Print all three and the median: they have ranged from 74 to 332 points, so the operator should see what the number rests on.
5. **Pins**: a label or `fixVersion` that names an **active or future** sprint pins the issue to that sprint. One that names a closed sprint is stale — it pins nothing and goes on the plan's stale list. One that names a version beyond every sprint this plan creates (`v1.0`) keeps the issue in the backlog on purpose; list those as well.

**Phase 2 — Consolidation**

6. Find stories that duplicate each other, sit inside another's scope, build the same mechanism twice, ask for something that already ships, or were already decided against. This runs before epics and estimates so nothing is linked or sized twice. Use all three candidate sources below — each misses what the others find. Measured on 231 stories on 2026-09-26: word similarity ranked five known overlaps between 12th and 3,097th of 26,565 pairs; a model reading one-line summaries found all five, but missed a cross-epic pair word similarity ranked 10th; and neither saw the eight stories whose own comments recorded a decision not to build them.

   **Candidates from the stories' own comments.** Scan every open story's comments for a recorded disposition — `CLOSED —`, `not worth building`, `not implementing`, `superseded`, `absorbed into`, `obsolete`, `withdrawn`, `won't do` — and read each hit in full: a match can be a negation ("deliberately NOT superseded") or the word in another sense. Before 2026-08-07 this project left decided-against stories at To Do, since there was no Won't Do transition; the operator's rule since then is to close them to Done with a comment saying no code was delivered, so every such story is a closing candidate.
7. **Candidates from a catalog read.** Write every open non-epic story as `KEY: summary`, grouped by epic, then the Done stories resolved in the last 180 days. Give it to one `Agent` with no hints about expected pairs: find groups that duplicate, contain or overlap one another — across epics especially, since siblings in one epic are usually a deliberate breakdown — or that ask for what a Done story delivered. It returns `{keys, kind, why}` per group.
8. **Candidates from word similarity.** Score every pair of open non-epic stories and keep the **cross-epic** pairs in the top 1% (cosine ≈ 0.16 on the first run). Same-epic pairs fill the top of the ranking and are sibling breakdowns, not duplicates:

   ```python
   import collections, itertools, math, re
   STOP = set("the and for with that this from are was will should into than then them they their what when which "
              "also only more most can may must has have implement add new story epic acceptance criteria jclaw agent agents".split())
   def toks(t):
       t = re.sub(r"\{\{|\}\}|\{code[^}]*\}|\{noformat\}|h[1-6]\.", " ", (t or "").lower())
       return [w for w in re.findall(r"[a-z][a-z0-9_]{2,}", t) if w not in STOP]
   def similar_pairs(issues):  # open non-epic issues from step 2; best first
       docs = {i["key"]: toks(i["fields"]["summary"]) * 3 + toks(i["fields"].get("description")) for i in issues}
       df, n, vec = collections.Counter(w for d in docs.values() for w in set(d)), len(docs), {}
       for k, d in docs.items():
           v = {w: (1 + math.log(c)) * math.log(n / df[w]) for w, c in collections.Counter(d).items() if df[w] > 1}
           norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
           vec[k] = {w: x / norm for w, x in v.items()}
       cos = lambda a, b: sum(x * b.get(w, 0.0) for w, x in a.items())
       return sorted(((cos(vec[a], vec[b]), a, b) for a, b in itertools.combinations(vec, 2)), reverse=True)
   ```
9. Drop any pair already joined by an issue link — someone has judged it — and merge the remaining candidates into groups.
10. **Verify every group** against the full descriptions and acceptance criteria, never the summaries: the catalog read over-reports (36 groups on the first run, many sharing only a fix pattern across different code). Read each story's comments too — a prior thread may have kept two stories apart on purpose. Where an outcome may already exist, check the code (`graphify query` first, then `git grep -P`) or the Done story, as JCLAW-1119 was confirmed shipped. Verdicts:
    - **duplicate** — the same outcome in other words: keep one, close the other.
    - **subsumed** — one story's scope sits inside the other's: close the smaller; its criteria the survivor lacks move to the survivor.
    - **merge** — the same mechanism built twice with different edges: combine into the survivor, moving the closed story's criteria over.
    - **shipped** — the outcome exists in code or a Done story: close it, citing the evidence. Prove it in the code: on the first run only 3 of the catalog read's 18 shipped claims held, and one cited Done story (JCLAW-1215) had itself been closed with no code delivered — read a cited story's comments before trusting its status.
    - **withdrawn** — the story's own thread records a decision not to build it (a measured premise that failed, an obsolete need, a scope deferred to a trigger that has not fired) and nothing later reverses it: close it, citing that decision.
    - **distinct** — related but separate work: keep both, linked `Relates` so the next run skips the pair; a real ordering between them becomes a `Blocks` link instead. A pair that shares only vocabulary gets no link.

    The survivor is the story in progress or in the active sprint, else the one with more links and clearer criteria, else the lower key. When unsure, the verdict is **distinct**: closing a live story loses work, while a missed duplicate is caught by the next run.
11. With more than ~15 groups, fan verification out: one `Agent` per batch, all in one message, each returning `{keys, verdict, survivor, closes, reason, absorbed_criteria}`. Closed stories leave every later phase; a survivor is estimated on its merged scope in Phase 4, with its old estimate shown beside the new one, and moves to the earliest sprint holding it or any story it absorbs, so absorbed work never slips. A survivor over 13 points stays unplanned, and the plan names what now waits on its split.

**Phase 3 — Epics**

12. For every open non-epic issue with no Epic Link, find its epic, strongest signal first, and record which signal decided it:
    - a label `epic-JCLAW-N` naming an open epic;
    - a summary prefix shared with an open epic (`Context Shards: …` → `Context Shards: Shared Memory Pipeline for AI Agents`);
    - an issue link to an epic, or to a story already under one;
    - subject matter, judged from the story's description against the epic's — not from titles alone. A match on this signal alone gets a one-line reason in the plan.
13. Cluster the stories no open epic fits by theme, and propose one new epic per cluster — a cluster of one is allowed, since every story ends with an epic. Each proposal carries a summary, a short Epic Name, a two-to-four sentence description of the problem its stories share, and the story keys. A proposed epic whose theme an open epic already covers is a missed match from step 12, not a new epic.
14. **Key collision check.** Find the highest key (`project = JCLAW ORDER BY key DESC`); for each key the new epics will take (highest+1 … highest+N) run `git grep -nP 'JCLAW-<n>\b'`. A hit means work was committed under a ticket that does not exist yet, and filing an epic onto that key would repoint every reference to it: stop and report it.

**Phase 4 — Estimates**

15. Estimate every open Story and Bug that has no Story Points, and every merge survivor from step 10. Keep all other existing estimates — re-estimating is the operator's call, not a grooming side effect. Epics get no points: their stories carry them, and epic points would be counted twice in a sprint's sum.
16. Use the Fibonacci scale the project's existing estimates use, `1, 2, 3, 5, 8, 13`, and anchor by comparison: pull Done stories from the same epic or theme with their points (`"Epic Link" = JCLAW-N AND statusCategory = Done AND "Story Points" is not EMPTY`) and size relative to them. Size against the code, not the title — `graphify query "<subject>"` first, then `git grep -P` for the classes and files the description names — counting subsystems touched, test surface (a backend change pays `play autotest`, a frontend one `pnpm test` and typecheck) and open unknowns.
    - **1** — one file, an obvious change, an existing test pattern.
    - **2** — two or three files, or one change that needs a new test.
    - **3** — one subsystem, clear design, moderate test work.
    - **5** — two subsystems, or one with a design decision to make.
    - **8** — several subsystems or a new component (a sidecar, a settings panel with its endpoints).
    - **13** — the ceiling: estimate it, and list it as a split candidate.
    - Larger than 13 is not estimated: flag it for splitting and leave it out of the sprint plan.
    - A spike is time-boxed at 2 or 3.
17. With more than ~30 stories to estimate, fan out: one `Agent` call per epic batch, all in one message so they run concurrently, each given its stories, the anchors and the guide above, returning `key → points + one-line reason`. Before accepting them, compare across batches — the same kind of change gets the same number whichever batch saw it.

**Phase 5 — Sprint plan**

18. **Classify** every plannable issue as **feature** or **NFR**. NFR is: every Bug; labels `security`, `sev-*`, `vulnhunt-*`, `performance`, `reliability`, `observability`, `accessibility`, `testing`, `audit`, `regression`, `infrastructure`, `governance`, `safety`, `documentation`; stories under audit, remediation, refactor or security epics; and any story whose substance is a defect fix, refactor, hardening, performance or test work, whatever its labels. Everything else is feature. A judgment call gets a reason in the plan.
19. **Placement rules**, each subordinate to the ones above it:
    1. An issue in the active sprint stays there, and an In Progress issue never moves. An issue in a future sprint stays unless that sprint is over capacity; then the lowest-ranked of them are the first to move out.
    2. A pinned issue (step 5) goes to its sprint.
    3. An issue that `is blocked by` an open issue lands in its blocker's sprint or a later one. So does one whose description or estimator note says it is blocked by, or depends on, another open story.
    4. Highest/High priority and `sev-high` issues go before the rest.
    5. An epic's stories stay together where capacity allows, so a sprint finishes epics rather than touching many.
    6. Backlog rank.
20. **Fill** sprints in order — the active sprint, then existing future sprints, then new ones — up to capacity, counting estimated points only. A sprint's room is capacity minus every point already in it, done or open. When the last sprint is full, propose the next by the rubric: `v<major>.<minor+1>` after the highest name from step 1, never reusing a name, even a closed sprint's. It starts the day after the previous sprint ends, with the same length, time of day and UTC offset.
21. **Balance**: each sprint's NFR share of points should sit within ±10 percentage points of the `nfr=` target. Out of band, swap across adjacent sprints — the lowest-ranked item of the over-represented class moves later, the highest-ranked of the under-represented class moves earlier — without breaking rules 19.1–19.3. First measure the NFR share of everything being placed: when that pool itself sits outside the band, no arrangement keeps every sprint inside it, and holding early sprints to the target only piles the surplus into the last ones (the first run: a 48% NFR pool against a 30% target left v0.22 at 63% and v0.23 at 97%). Spread at the pool's own share instead, and report the gap between it and the target.
22. **Epics in sprints**: an open epic goes in the earliest sprint holding one of its planned stories (Jira keeps an issue in one open sprint at a time), as JCLAW-1255 sits in v0.19 with its stories.
23. **Sprint goals**: every active and future sprint gets a goal naming what it delivers, in the shape the board uses — `<Theme>: <deliverables>. <Theme>: <deliverables>.` — one clause per epic or theme carrying most of the points, with NFR work named as its own theme (`Hardening: …`). One to three sentences, no ticket keys. Rewrite an existing goal only when it no longer describes the sprint; a bare version name (`v0.18`) never does.

**Phase 6 — Plan and approval**

24. Write the full plan to the session scratchpad as `backlog-plan-<YYYY-MM-DD>.md`:
    - **Capacity** — the three velocities, the median, any override.
    - **Consolidation** — `group | verdict | survivor | closes | reason`, each closing verdict with the criteria that move to its survivor; then the pairs checked and kept distinct, with the link each gets.
    - **Epic links** — `key | summary | → epic | signal`.
    - **New epics** — summary, Epic Name, description, stories.
    - **Estimates** — `key | summary | points | reason`, split candidates listed separately; merge survivors show `old → new`.
    - **Sprints** — per sprint: name, dates, goal, points against capacity, NFR share, then `key | points | class | epic` per issue.
    - **Left in the backlog**, each with its reason.
    - **Stale labels and fixVersions** — reported, not changed.
    - **Anomalies** — key collisions, overloaded active sprint, anything else that needs a human.
25. Summarize in chat: the count per section — stories to close, by verdict, first — one line per sprint (`v0.20 · 2026-10-28 → 2026-11-27 · 281/287 pts · NFR 31% · <goal>`), and the plan's path.
26. With `plan`, stop here. Otherwise ask with `AskUserQuestion`: apply everything / apply everything except the consolidation closures / apply epics and estimates only / cancel. An answer with edits ("put JCLAW-902 in v0.21", "keep JCLAW-924 open") is folded into the plan; show the lines that changed and ask again.

**Phase 7 — Apply**

In this order, since each step uses the one before. Stop at the first unexpected failure and report exactly what was and was not written — a half-applied plan is recoverable only from a precise stopping point.

27. **Consolidation**, per closing verdict:
    1. **Survivor** (duplicate, subsumed, merge): append the moved criteria to its description under `h3. Absorbed from JCLAW-N` — `GET /rest/api/2/issue/{key}?fields=description` returns the raw wiki markup; `PUT` it back with the section added at the end, leaving the existing text byte-for-byte unchanged.
    2. **Comment** on the story being closed, in wiki markup: `Closed as a duplicate of JCLAW-S` (or `merged into JCLAW-S`); *no code was delivered for this story*; its acceptance criteria now live in JCLAW-S. A shipped verdict says no code was delivered under this ticket and names the code or Done story that already delivers it. Done is the only closing status, so the comment is what tells a closed-unbuilt ticket from a shipped one.
    3. **Link**: `POST /rest/api/2/issueLink` with `{"type":{"name":"Duplicate"},"inwardIssue":{"key":<closed>},"outwardIssue":{"key":<survivor>}}` → 201, reading "<closed> duplicates <survivor>". On this instance the inwardIssue takes the outward verb — a `Blocks` link built the other way round came out inverted in 2026-07. Shipped and withdrawn verdicts have no survivor and get no link.
    4. **Out of its sprint**: `POST /rest/agile/1.0/backlog/issue` with `{"issues":[<closed>]}` → 204. A story closed inside a sprint is counted as completed points in that sprint's report, and those reports are where step 4 measures capacity.
    5. **Close**: `POST /rest/api/2/issue/{key}/transitions` with `{"transition":{"id":"31"}}` → 204.

    Then the distinct pairs: `Relates` links (or `Blocks`, blocker as inwardIssue).
28. **Epics**: `POST /rest/api/2/issue` with `{"fields":{"project":{"key":"JCLAW"},"issuetype":{"id":"10000"},"summary":…,"customfield_10004":<Epic Name>,"description":…}}` → 201; record each new key. REST stores the description verbatim, so write **Jira wiki markup** (`*bold*`, `{{code}}`, `*` bullets) — Markdown would show literal `**` — and keep every `{{…}}` span on one line.
29. **Epic links**: `PUT /rest/api/2/issue/{key}` with `{"fields":{"customfield_10002":"JCLAW-N"}}` → 204.
30. **Estimates**: `PUT /rest/agile/1.0/issue/{key}/estimation?boardId=30` with `{"value":N}` → 200. A Bug's estimate answers 400 "not editable due to its issue type": record it and continue. The plan still counts it toward capacity, because the work is real, so that sprint's sum in Jira reads lower by that much.
31. **Sprints**: new ones via `POST /rest/agile/1.0/sprint` with `{"name":"v0.N","originBoardId":30,"goal":…,"startDate":…,"endDate":…}` → 201. Goals of existing ones via `POST /rest/agile/1.0/sprint/{id}` with `{"goal":…}` — POST is the partial update; `PUT` replaces the sprint and needs every field.
32. **Membership**: `POST /rest/agile/1.0/sprint/{id}/issue` with `{"issues":[…]}`, at most 50 per call → 204; the same call moves an issue from another open sprint. Back to the backlog: `POST /rest/agile/1.0/backlog/issue` — the MCP has no tool for it.

**Phase 8 — Verify and report**

33. Re-read from Jira, never from write responses — a 2xx is not proof the value landed:
    - Each closed story is Done, in no sprint, and — for a duplicate, subsumed or merge verdict — shows `duplicates <survivor>` under `outwardIssue` when read from its own side; each survivor's description ends with its `Absorbed from` section, and `?expand=renderedFields` shows no leftover `{{` or `h3.`.
    - `project = JCLAW AND statusCategory != Done AND issuetype != Epic AND "Epic Link" is EMPTY` → none.
    - `project = JCLAW AND statusCategory != Done AND issuetype = Story AND "Story Points" is EMPTY` → only the split candidates.
    - Per sprint: its issues match the plan, and its point sum and goal match.
34. Report what changed — stories closed by verdict with their survivors, links added, new epic keys, epic links, estimates, sprints created, goals set, issues placed — then each sprint's line from step 25, then what is left for the operator: split candidates, stale labels, collisions, and any sprint whose balance could not reach the band.

---

**Hard rules**

- No Jira write before Phase 7, and none without approval.
- Never modify a closed sprint, remove an issue from the active sprint, or move an In Progress issue. The one exception: a story the approved plan closes leaves its sprint, the active one included, before it is closed.
- Never start or complete a sprint. Transition an issue only to close a story the approved plan consolidates, and only after its comment says no code was delivered and where its work went.
- Never rewrite an existing issue's summary, description, labels or fixVersions. The one edit allowed is the append-only `Absorbed from` section on a merge survivor.
- Never overwrite an existing estimate, except a merge survivor's, shown `old → new` in the plan.
- Never delete an issue, epic or sprint.
