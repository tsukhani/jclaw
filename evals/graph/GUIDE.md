# Graph-extraction labelling guide (JCLAW-1356, JCLAW-1358)

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
  exactly one operator entity.

## 4a. Guests

A memory about someone talking to the agent who is not the owner is a guest case, tagged `guest`.

- A guest named in their own words ("Jonah Pell") or by a group display name ("Marisol") is an
  ordinary Person with their own id (`jonah-pell`, `marisol`), never `operator`.
- "a guest" is a negative, never a Person: exact matching would otherwise merge every anonymous
  guest across memories into one node. Nothing relates from it; the other spans in the memory are
  labelled normally.
- A guest case has no operator entity and no relation to `operator`: the memory never mentions
  the owner, so there is nothing the owner could rightly receive.

## 5. Preferences are views on a Topic

A stated preference, like or dislike makes its object a Topic, linked
`<person> holds_view_on <topic>` (`operator` for the owner). "Avery Lin prefers oat milk over
dairy" labels "oat milk" as a Topic. The thing it is preferred over ("dairy") is not labelled: list it as a negative. A
dislike is labelled the same way as a like; the view's polarity is not recorded.

## 6. One relation per ordered pair

At most one relation from A to B. When two fit, pick the most specific the text states
(`works_at` over `located_in` for an employer). `same_as` and `family_of` are symmetric: label
them once, in either direction. Label only relations the text states; never infer one from world
knowledge.

## 7. Hard negatives

Each case carries one or more tags. Six tags name a trap the case sets; `plain` means none;
`guest` marks a guest case (rule 4a) and sits beside the trap tags.
List the trap's span in `negatives` when it is a span a model might type as an entity.

| Tag | The trap | Example |
| --- | --- | --- |
| `weekday-time` | A weekday, month or recurring time is never an Event | "every Tuesday" -> negative "Tuesday" |
| `role` | A job title or role is no entity | "a data engineer at Harborlight Analytics" -> negative "data engineer" |
| `everyday-object` | A generic object the operator has or uses is no entity | "keeps a spare charger in the car" -> negatives "spare charger", "car" |
| `descriptive-phrase` | A description of a named thing is its alias | "Kestrel CI, the team's deployment platform" -> alias, rule 2 |
| `reversed-direction` | The text states a relation in the opposite surface order | "Harborlight Analytics employs Avery Lin" -> `operator works_at harborlight`, never the reverse |
| `employer-tool` | A tool the employer runs is not the operator's own | "Avery Lin's employer, Harborlight Analytics, runs Kestrel CI" -> `harborlight uses kestrel-ci`, no `operator uses` |

A negative never equals a mention or an alias in the same case.

## 8. Ids are stable across cases

An entity keeps one id, and one type, in every case it appears in, and its mention reads the same
in every case (case, a leading "the" and a possessive "'s" aside), so exact-match resolution has
real merges to score. The operator is the one exception: it reads as the owner's name, "The user"
or nothing, and resolution joins all three by rule and by the declared name. Two different entities never share a surface and type.

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
