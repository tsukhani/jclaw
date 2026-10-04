# Graph-extraction labelling guide (JCLAW-1356, JCLAW-1358)

Guide version: 3

The rules every label in `cases.json`, `second-labels.json` and the held-out file follows. A
label that breaks one of them is a label error, not a model error. The format itself is in
`README.md`; this file says what to label and how.

## 1. A record is a span plus a type

- An entity is labelled once per case, by its exact span (`mention`) and one term type from
  `conf/ontology/seed-schema.yaml`. Scoring is strict: a written span must equal the mention or a
  listed alias character for character. "Meridian" written for a gold "Meridian program" is a
  wrong match unless "Meridian" is listed as an alias.
- Pick the longest span that names the thing and nothing more: "Kestrel CI", not "Kestrel CI
  pipeline" unless "pipeline" is part of the name. Leave a sentence-initial determiner out ("The
  user", kept only in legacy cases, is the one exception, below); keep a determiner that is part of
  a name ("The Larkspur Inn").
- Every alias is verbatim in the text. List every other span in the same case that names the
  same entity: a second spelling, an abbreviation, a pronoun-free description.

## 2. A descriptive phrase is an alias, not a new entity

"Kestrel CI, the team's deployment platform" names one System. "the team's deployment platform"
goes in Kestrel CI's `aliases`; it is never an entity of its own. A model that writes both the
name and the description has written one entity twice, and the second is a `duplicate`.

## 3. A true but trivial fact is noise

A record that is true but adds nothing a graph needs carries `"noise": true`: a generic place
that is only a backdrop ("at home"), an obviously implied relation, a topic so broad it says
nothing. Noise is counted on its own and sits in neither the wrong count nor the denominator, nor
in recall's gold. That is not free: it shrinks the denominator, which widens the certification
bound, so every noise label makes certifying harder. Use it sparingly: when in doubt, a thing is
either worth a node (label it) or not (leave it out, or list it as a negative).

Noise means only true but trivial; it never covers an ended, denied or unasserted fact. A
relation to a former name of the same thing, when the case states the `same_as`, is noise: it is
true and implied, not ended (c047).

## 4. The operator

- The set declares its owner in a USER.md header, the root `userMd` (`"Name: Avery Lin"`). The
  owner's name in a memory names the operator: entity id `operator`, type Person, mention
  exactly the declared name ("Avery Lin"). It is an ordinary term: the model types it, and that
  decision is scored like any other.
- "The user" stands for a memory captured before the owner had a name, and is kept only in those
  legacy cases: the same `operator` entity, with the span as written, determiner included. The
  harness writes it without asking.
- A memory with no stated subject ("Prefers oat milk over dairy.") is also about the operator.
  Label it `{"id": "operator", "type": "Person", "implicit": true}` with no mention: there is no
  span to write, and the harness writes the operator without asking.
- A memory never says both the owner's name and "the user". Every case except a guest case has
  exactly one operator entity, and a guest case has one only when it is tagged
  `guest-about-owner` (rule 4a).

## 4a. Guests

A memory about someone talking to the agent who is not the owner is a guest case, tagged `guest`.

- A guest named in their own words ("Jonah Pell") or by a group display name ("Marisol") is an
  ordinary Person with their own id (`jonah-pell`, `marisol`), never `operator`.
- "a guest" is a negative, never a Person: exact matching would otherwise merge every anonymous
  guest across memories into one node. Nothing relates from it; the other spans in the memory are
  labelled normally.
- A guest memory that names the owner is tagged `guest-about-owner` beside `guest`. It carries
  the owner as the `operator` entity, and every relation it states about the owner is
  `unasserted`: a guest's word about the owner is never the owner's fact.
- A guest case that does not name the owner keeps the v2 rule: it has no operator entity and no
  relation to `operator`, since there is nothing the owner could rightly receive.

## 5. Preferences are views on a Topic

A stated preference, like or dislike makes its object a Topic, linked `<person> holds_view_on
<topic>` (`operator` for the owner), with `valence` for that holder. "Avery Lin prefers oat milk
over dairy" labels "oat milk" as a Topic. The thing it is preferred over ("dairy") is not
labelled: list it as a negative.

- Favorable: prefers, likes, loves, enjoys, is interested in.
- Unfavorable: dislikes, hates, can't stand, doesn't like, never liked.
- "Thinks that" and "believes that" views take no valence.
- "Doesn't like X" is unfavorable, never denied. "No longer likes X" and "doesn't like X
  anymore" are favorable with status ended.
- In "A hates X, but B loves it", each holder gets their own valence.

## 6. One relation per ordered pair

At most one positive (holds, ended or unasserted) and one denied relation per ordered pair. When
two positive relations fit, pick the most specific the text states
(`works_at` over `located_in` for an employer). `same_as` and `family_of` are symmetric: label
them once, in either direction. Label only relations the text states; never infer one from world
knowledge.

## 6a. A verb states the relation

A memory rarely uses the relation's own word. These readings are settled:

- **A home or base is `located_in`; a venue is noise.** Where a person lives, works from or is
  based ("has lived there since 2019", "their home", "works from an armchair at Larchmere
  House") is `located_in`. A place where they only do something ("goes bouldering at Hollis
  Park", "plays football at Hollis Park") is `located_in` with `"noise": true`.
- **A former home is `located_in` with status ended.** "Moved to A from B" gives A holds and B
  ended. A venue is still noise.
- **Working for a project is `works_on`:** drafting a document for it, reviewing its burndown,
  tracking tickets against it.
- **Keeping or paying for a thing is `owns`:** keeping a file or a URL ("keeps the deploy steps
  at https://wiki.example.com/runbooks/deploy"), paying for a system, a vendor's own product
  ("the on-call alerting app from Harborlight Analytics").
- **Operating a system is `uses`,** whatever the verb: backing up to it, checking it, running
  something on it, a project shipping through it.
- **A classification is not a view.** "considers X a kind of Y" labels `X kind_of Y`; the
  holder's `holds_view_on X` is noise.

## 6b. Reported knowledge

The memory's own speaker reporting counts as stated. In an owner memory that is the owner's
"says", "notes", "mentioned that", "recalls", "learned that" (c011, c020, c063, c087, c095); in a
guest memory, the guest's own report (c137, c139). Anything only a third party says, thinks or
told them ("Jonah Pell says…", "heard from Felix that…") is unasserted.

## 7. Hard negatives

Each case carries one or more tags. Nine tags name a trap the case sets: the six v2 tags plus
`ended`, `negated` and `unasserted`; `plain` means none. `dated` marks a date stratum, and
`guest` and `guest-about-owner` mark guest cases (rule 4a) beside the trap tags.
List the trap's span in `negatives` when it is a span a model might type as an entity.

| Tag | The trap | Example |
| --- | --- | --- |
| `weekday-time` | A weekday, month or recurring time is never an Event | "every Tuesday" -> negative "Tuesday" |
| `role` | A job title or role is no entity | "a data engineer at Harborlight Analytics" -> negative "data engineer" |
| `everyday-object` | A generic object the operator has or uses is no entity | "keeps a spare charger in the car" -> negatives "spare charger", "car" |
| `descriptive-phrase` | A description of a named thing is its alias | "Kestrel CI, the team's deployment platform" -> alias, rule 2 |
| `reversed-direction` | The text states a relation in the opposite surface order | "Harborlight Analytics employs Avery Lin" -> `operator works_at harborlight`, never the reverse |
| `employer-tool` | A tool the employer runs is not the operator's own | "Avery Lin's employer, Harborlight Analytics, runs Kestrel CI" -> `harborlight uses kestrel-ci`, no `operator uses` |
| `ended` | Writing it as current | "used to work at Ostrander Freight" -> `operator works_at ostrander` with status ended |
| `negated` | Writing a denied relation as positive, or a negation about a non-entity as a statement | "paid for by Harborlight Analytics, not by the user" -> `operator owns kestrel` denied |
| `unasserted` | Writing it at all | "Plans to attend the Quillon Summit" -> `quillon involves operator` unasserted |
| `guest-about-owner` | Writing any owner relation from a guest memory | every relation touching `operator` is unasserted (rule 4a) |
| `dated` | A stratum, not a hard negative: a date attached to the wrong statement or reading | "starts in March" dates the renovation, not a bound on `operator works_on attic` (rule 13) |

A negative never equals a mention or an alias in the same case.

## 8. Ids are stable across cases

An entity keeps one id, and one type, in every case it appears in, and its mention reads the
same in every case (case, a leading "the" and a possessive "'s" aside), so exact-match
resolution has real merges to score. The operator is the one exception: it reads as the owner's
name, "The user" or nothing, and resolution joins all three by rule and by the declared name.
Two different entities never share a surface and type.

## 9. Adjudication

When a run reports a wrong record, a person reads it against the case and records a verdict in
`adjudications.json`:

- `wrong` -- the model is wrong and the label stands. It counts against the model.
- `label-error` -- the label is wrong (a missed alias, a missing entity, a disallowed type). The
  certification is refused until the label is fixed and the run repeated; a fixed label is never
  applied to an old run.

Adjudicate every wrong record at the certified threshold. Never adjudicate from the model's
confidence or from what a later run did.

## 10. Synthetic only

Every name, organization, place and file is invented, and URLs use `example.com`. Never copy a
real memory into `evals/graph/`; real memories are labelled only in the held-out file under
`data/graph-eval/`, which is never committed.

## 12. Status

Every relation carries `status`, set by this rule.

| Status | When | Examples |
| --- | --- | --- |
| holds | Stated true when written, including the present perfect and a scheduled future stated as fact | "has worked at … for three years"; c011 "will host … in October"; c026 "is registered for" |
| ended | Held and has stopped | used to, former, an "ex" prefix, no longer, left, after leaving, migrated away from (unless a rename, rule 3), was at … for N years (c014, c046, c077, c114); a transfer verb ends the giver's tie ("bought X from Y", "was given X by Y", "recruited Z from Y": c059, c124, c108); an ex-partner's `family_of` |
| denied | The memory denies this very relation between two labelled entities | c063 "not by the user" (`operator owns kestrel`). Never inferred: c128's "never logs into" the hardware labels nothing |
| unasserted | Planned, wanted, might or may, hoped, needs to confirm, asked whether; a belief about a relation other than a view; a third party's report; a guest's report about the owner | c100 "Plans to attend"; c056 and c098 "believes"; c127 "Needs to confirm that"; c138 a guest's question |

Timeless relations (`kind_of`, `same_as`, `derived_from`) and relations from an Event take only
holds or denied (or unasserted), and no valid time.

## 13. Dates

- List every date-like span in `dates`, resolved against capturedAt by the date table below,
  using the reading the tense means; an excluded span gets null.
- An Event's own date goes on the Event as `occurs`, never on its relations, and only when the
  text asserts it. A date that is planned, asked about or still to be confirmed is listed in
  dates but not as occurs (c127, c138).
- A recurring series ("yearly", "every autumn") gets no occurs. A memory that dates one edition
  gives that edition's occurs, and its relations belong to that edition (c011 against c040).
- A relation takes a bound only from a date the memory ties to that relation's own start or end:
  c066's "starts in March" and c090's "assigned in May" date something else.
- A bound never transfers along `part_of`: c003's "since 2019" dates Larchmere House, not
  Ashgrove.
- Never use capturedAt as a start.
- "For N units" on a holds relation gives a start of capturedAt minus N, approximate; on an ended
  relation it gives nothing.
- A perfect "has/had never" on a denial gives valid `../<capturedAt>` where the relation admits
  valid time. A habitual "never" gives none.
- A date inside a file name, URL or ticket is not a date.

Reading dates. Values at the root anchor 2026-02-15:

```
Kind                          Example                              Value
Absolute day                  12 December 2026, 2026-12-12         2026-12-12
Absolute month                December 2026                        2026-12
Year                          2019 (four digits, not inside a      2019
                              URL, path, file name or ticket)
Calendar quarter              Q3 2027                              2027-35
Range                         12 to 14 June 2027                   2027-06-12/2027-06-14
                              from 2019 to 2022                    2019/2022
Deictic day                   yesterday, today, tomorrow           2026-02-14, 2026-02-15, 2026-02-16
Deictic year                  last, this, next year                2025, 2026, 2027
Deictic month                 last, this, next month               2026-01, 2026-02, 2026-03
Deictic named month           last June, this June, next June      2025-06, 2026-06, 2026-06
Deictic quarter (not fiscal)  last, this, next quarter             2025-36, 2026-33, 2026-34
Deictic season                this winter, this spring             2025-24, 2026-21
                              last summer, next winter             2025-22, 2026-24
Ago                           three years ago, two months ago      2023~, 2025-12~
Year-less month or season     "was in December" (behind)           2025-12
                              "in April" (ahead)                   2026-04
                              the anchor's own month               2026-02
Day without a year            12 December                          as above, at day precision
Duration, holds relation      has worked there for three years     2023~ (the start it gives)
Duration, ended relation      was there for six years              null
Excluded                      weekdays, clock times, vague words   null
                              (recently, soon), recurring spans
                              (every, each, per; plural weekdays,
                              months or seasons), d/m numerics,
                              fiscal quarters, event-relative
                              bounds, digits in a file name, URL,
                              path or ticket

Seasons: spring 21 (March to May), summer 22, autumn 23, winter 24 (December to February,
dated by its December). Quarters: 33 to 36 for Q1 to Q4. A trailing ~ means approximate.
"this <season>" is the season holding the anchor, else the one starting in the anchor's year;
"last" is the latest that ended on or before the anchor; "next" the earliest starting after it.
"last <Month>" is the latest such month strictly before the anchor's month; "next" the earliest
strictly after; "this" is that month in the anchor's year.

valid by status and slot
  holds, start F             F/..     (F may lie after capturedAt: a scheduled start)
  holds, end T               /T       (T must end after capturedAt)
  holds, start and end       F/T
  ended, start F             F/
  ended, end T               /T
  ended, start and end       F/T
  holds, "for N years"       2023~/..   (capturedAt minus N, approximate)
  denied, "has/had never"    ../2026-02-15   (../<capturedAt>)
```

Rule 11 is deliberately unused: other tickets cite the status rule as 12 and the dates rule as 13.
