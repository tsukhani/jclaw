// Offline checks on synthetic tickets: review feedback, planning, build mode and the GitHub trust rule. `npm run check`.
import { vetIssue, type Issue } from "./github.ts";
import { buildMode, parsePlan, pickNonOverlapping, sensitivePaths } from "./plan.ts";
import { rejectionFeedback, type Snapshot } from "./tracker.ts";

const ticket = (...bodies: string[]): Snapshot => ({
  key: "T-1", summary: "", description: "", labels: [], blockedBy: [], updated: "", fetchedAt: "",
  comments: bodies.map((body) => ({ author: body.startsWith("h3.") ? "factory" : "Reviewer", body })),
});
const OFFER = "h3. AFK factory: ready for review\nbrief", GAVE_UP = "h3. AFK factory gave up\nboom";
const feedbackOf = (t: Snapshot) => rejectionFeedback(t, "h3. AFK factory");
const check = (name: string, got: unknown, want: unknown) =>
  console.log(`${JSON.stringify(got) === JSON.stringify(want) ? "ok  " : "FAIL"} ${name}: ${JSON.stringify(got)}`);

check("never offered", feedbackOf(ticket("a human note")), undefined);
check("failed before any offer", feedbackOf(ticket(GAVE_UP)), undefined);
check("sent back without a comment", feedbackOf(ticket(OFFER)), "");
check("sent back with feedback", feedbackOf(ticket("old note", OFFER, "rename the helper")), "Reviewer: rename the helper");
check("feedback survives a later gave-up", feedbackOf(ticket(OFFER, GAVE_UP, "rename the helper")), "Reviewer: rename the helper");
check("second rejection reads only the newest round", feedbackOf(ticket(OFFER, "first round", OFFER, "second round")), "Reviewer: second round");

const s = (key: string) => ({ key });
const keys = (r: ReturnType<typeof pickNonOverlapping>) => ({ picked: r.picked.map((x) => x.key), deferred: r.deferred.map((d) => d.key) });
const files = (o: Record<string, string[]>) => new Map(Object.entries(o).map(([k, v]) => [k, new Set(v)]));
const inFlight = new Map([["test/SsrfGuardTest.java", "JCLAW-1324 (in review)"]]);
check("disjoint stories both run", keys(pickNonOverlapping([s("A"), s("B")], files({ A: ["a"], B: ["b"] }), new Map())), { picked: ["A", "B"], deferred: [] });
check("second of two sharing a file waits", keys(pickNonOverlapping([s("A"), s("B")], files({ A: ["x", "a"], B: ["x"] }), new Map())), { picked: ["A"], deferred: ["B"] });
check("a file awaiting review blocks", keys(pickNonOverlapping([s("A"), s("B")], files({ A: ["test/SsrfGuardTest.java"], B: ["b"] }), inFlight)), { picked: ["B"], deferred: ["A"] });
check("unknown files wait while anything is in flight", keys(pickNonOverlapping([s("A")], new Map(), inFlight)), { picked: [], deferred: ["A"] });
check("unknown files run alone when nothing is", keys(pickNonOverlapping([s("A"), s("B")], files({ B: ["b"] }), new Map())), { picked: ["A"], deferred: ["B"] });
console.log(pickNonOverlapping([s("A")], files({ A: ["test/SsrfGuardTest.java"] }), inFlight).deferred[0].reason);

check("sensitive paths are flagged, ordinary ones are not",
  sensitivePaths([".githooks/pre-push", "app/utils/Filenames.java", "frontend/package.json", "gradle/wrapper/gradle-wrapper.properties", "AGENTS.md", "test/FooTest.java", ".sandcastle/main.ts", "docs/AGENTS.md"]),
  [".githooks/pre-push", "frontend/package.json", "gradle/wrapper/gradle-wrapper.properties", "AGENTS.md", ".sandcastle/main.ts"]);

const plan = parsePlan('{"A": {"files": ["./app/X.java", "test/XTest.java"], "bmad": true, "why": " open choice "}, "B": {"files": [], "bmad": false, "why": "precise"}}');
check("a plan parses, with ./ stripped and the reason trimmed",
  plan && [...plan].map(([k, p]) => [k, [...p.files], p.bmad, p.why]),
  [["A", ["app/X.java", "test/XTest.java"], true, "open choice"], ["B", [], false, "precise"]]);
check("a plan that is not JSON is refused", parsePlan("{not json"), undefined);
check("the old files-only answer is refused", parsePlan('{"A": ["app/X.java"]}'), undefined);
check("a plan missing its verdict is refused", parsePlan('{"A": {"files": [], "why": "x"}}'), undefined);
const verdict = { files: new Set<string>(), bmad: true, why: "a spike decides the scope" };
check("the planner decides an unlabelled story", buildMode(["afk"], verdict), { bmad: true, why: "a spike decides the scope" });
check("a bmad label forces BMAD", buildMode(["afk", "bmad"], { ...verdict, bmad: false }), { bmad: true, why: "labelled bmad" });
check("a no-bmad label forbids it", buildMode(["afk", "no-bmad"], verdict), { bmad: false, why: "labelled no-bmad" });
check("no-bmad wins over a bmad the harness wrote", buildMode(["afk", "bmad", "no-bmad"], verdict), { bmad: false, why: "labelled no-bmad" });
check("no verdict runs plain", buildMode(["afk"], undefined), { bmad: false, why: "the planner gave no verdict" });

const OWNER = "tsukhani";
const BEFORE = "2026-10-01T09:00:00Z", LABELLED = "2026-10-01T10:00:00Z", LATER = "2026-10-01T11:00:00Z";
const labelled = (createdAt: string, login = OWNER) => ({ __typename: "LabeledEvent", createdAt, actor: { login }, label: { name: "afk" } });
const remark = (login: string, createdAt: string, edited?: { at: string; by: string }) => ({
  author: { login }, body: `${login} at ${createdAt}`, createdAt, lastEditedAt: edited?.at ?? null, editor: edited ? { login: edited.by } : null,
});
const issue = (over: Partial<Issue> = {}): Issue => ({
  number: 12, title: "Add a thing", body: "Do the thing.", updatedAt: LATER, author: { login: "stranger" },
  lastEditedAt: null, editor: null, labels: { nodes: [{ name: "afk" }] }, assignees: { nodes: [] },
  comments: { nodes: [] }, timelineItems: { nodes: [labelled(LABELLED)] }, ...over,
});
const vet = (i: Issue) => vetIssue(i, OWNER, "now");
check("a stranger's issue the owner labelled is story GH-12", vet(issue())?.key, "GH-12");
check("an issue someone else labelled afk is no story", vet(issue({ timelineItems: { nodes: [labelled(LABELLED, "collaborator")] } })), undefined);
check("comments from before the label, and the owner's from after it",
  vet(issue({ comments: { nodes: [remark("stranger", BEFORE), remark(OWNER, LATER), remark("stranger", LATER)] } }))?.comments.map((c) => c.body),
  [`stranger at ${BEFORE}`, `${OWNER} at ${LATER}`]);
check("a comment a stranger edited after the label is left out",
  vet(issue({ comments: { nodes: [remark("stranger", BEFORE, { at: LATER, by: "stranger" })] } }))?.comments.length, 0);
check("a body a stranger edited after the label is refused",
  vet(issue({ lastEditedAt: LATER, editor: { login: "stranger" } }))?.refused, "stranger edited the issue after you labelled it afk");
check("the owner's own later edit stands", vet(issue({ lastEditedAt: LATER, editor: { login: OWNER } }))?.refused, undefined);
check("an edit from before the label stands", vet(issue({ lastEditedAt: BEFORE, editor: { login: "stranger" } }))?.refused, undefined);
check("a title a stranger renamed after the label is refused",
  vet(issue({ timelineItems: { nodes: [labelled(LABELLED), { __typename: "RenamedTitleEvent", createdAt: LATER, actor: { login: "stranger" } }] } }))?.refused,
  "stranger renamed the issue after you labelled it afk");
check("labelling it again approves the issue as it now reads",
  vet(issue({ lastEditedAt: LATER, editor: { login: "stranger" }, timelineItems: { nodes: [labelled(LABELLED), labelled("2026-10-01T12:00:00Z")] } }))?.refused,
  undefined);
