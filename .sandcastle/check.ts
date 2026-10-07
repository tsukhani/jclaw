// Offline checks on synthetic tickets: review feedback, planning, build mode, the GitHub trust rule, Jira's intake and
// merge queries, the board, model-API overloads, and auto-merge, which lands branches between throwaway repos with real signing. `pnpm run check`.
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { execFileSync, spawn } from "node:child_process";
import { Board, RETENTION_MS, autoMerges, expired, transition, writeAtomically, type Entry, type State, type Story } from "./board.ts";
import { ownerApplied, vetIssue, type Issue } from "./github.ts";
import { intakeJql, mergeJql } from "./jira-intake.ts";
import { MergeRefused, followMain, landBranch, landedAs, mergeVerdict, restartEmptyBranch } from "./merge.ts";
import { BACKOFF_MS, Overloads, afterFailure, overloadReason, resetsOverloads, transientApiFailure } from "./overload.ts";
import { bmadOutcome, buildMode, buildsTheDiff, heldFiles, parsePlan, pickNonOverlapping, sensitivePaths } from "./plan.ts";
import { overruled, rejectionFeedback, type Snapshot } from "./tracker.ts";

const ticket = (...bodies: string[]): Snapshot => ({
  key: "T-1", summary: "", description: "", labels: [], blockedBy: [], updated: "", fetchedAt: "",
  comments: bodies.map((body) => ({ author: body.startsWith("h3.") ? "factory" : "Reviewer", body })),
});
const OFFER = "h3. AFK factory: ready for review\nbrief", GAVE_UP = "h3. AFK factory gave up\nboom";
const feedbackOf = (t: Snapshot) => rejectionFeedback(t, "h3. AFK factory");
const check = (name: string, got: unknown, want: unknown) => {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) process.exitCode = 1;
  console.log(`${ok ? "ok  " : "FAIL"} ${name}: ${JSON.stringify(got)}`);
};

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

// JCLAW-1402 predicted AGENTS.md, but its implement phase changed only ConfigService and a test.
const diffs: Record<string, string[]> = { R: ["app/services/ConfigService.java", "test/ConfigServiceTest.java"], E: [] };
const diffOf = (k: string) => diffs[k] ?? [];
const predictedR = new Set(["AGENTS.md", "app/services/ConfigService.java"]);
const heldBy = (k: string, run: { predicted: Set<string> | undefined; built: boolean }) => {
  const held = heldFiles(k, run, diffOf);
  return held && [...held];
};
const runningAs = (held: string[] | undefined) => new Map((held ?? []).map((f) => [f, "R (in progress)"]));
check("a running story holds its prediction during the build phase", heldBy("R", { predicted: predictedR, built: false }), ["AGENTS.md", "app/services/ConfigService.java"]);
check("its real diff replaces the prediction once built", heldBy("R", { predicted: predictedR, built: true }), ["app/services/ConfigService.java", "test/ConfigServiceTest.java"]);
check("a predicted file blocks another story during the build",
  keys(pickNonOverlapping([s("B")], files({ B: ["AGENTS.md"] }), runningAs(heldBy("R", { predicted: predictedR, built: false })))), { picked: [], deferred: ["B"] });
check("a file only predicted no longer blocks once built",
  keys(pickNonOverlapping([s("B")], files({ B: ["AGENTS.md"] }), runningAs(heldBy("R", { predicted: predictedR, built: true })))), { picked: ["B"], deferred: [] });
check("a file in the real diff still blocks once built",
  keys(pickNonOverlapping([s("B")], files({ B: ["test/ConfigServiceTest.java"] }), runningAs(heldBy("R", { predicted: predictedR, built: true })))), { picked: [], deferred: ["B"] });
check("an unknown prediction holds everything until built", heldBy("R", { predicted: undefined, built: false }), undefined);
check("an unknown prediction holds the real diff once built", heldBy("R", { predicted: undefined, built: true }), ["app/services/ConfigService.java", "test/ConfigServiceTest.java"]);
check("a build that changed nothing holds no files", heldBy("E", { predicted: new Set(["a"]), built: true }), []);
check("implement, build and rework complete the build phase; spec, gates, repairs, review and brief do not",
  ["implement", "build", "rework", "spec", "gate-1", "repair-1", "review", "gate-review", "brief", "merge"].filter(buildsTheDiff), ["implement", "build", "rework"]);

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

// BMAD's two halt shapes, as bmad-build-auto's workflow.md writes them.
check("a halt in a spec reads its Auto Run Result",
  bmadOutcome("---\nstatus: blocked\n---\n\n# Story 7\n\n## Auto Run Result\n\nStatus: blocked\nBlocking condition: no epic spec found\n"),
  { status: "blocked", result: "Status: blocked\nBlocking condition: no epic spec found" });
check("a halt before any spec reads its result file, so the ticket gets the reason",
  bmadOutcome("---\nstatus: blocked\n---\n\n# BMad Build Auto Result\n\nStatus: blocked\nBlocking condition: unresolved review decisions\n"),
  { status: "blocked", result: "Status: blocked\nBlocking condition: unresolved review decisions" });

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

const declined = parsePlan('{"A": {"files": [], "bmad": false, "why": "x", "wontDo": " not this repository "}, "B": {"files": ["b"], "bmad": false, "why": "y", "wontDo": null}, "C": {"files": ["c"], "bmad": true, "why": "z"}}');
check("a declined story carries its reason, trimmed; null or absent means build",
  declined && [...declined].map(([k, p]) => [k, p.wontDo ?? "build"]), [["A", "not this repository"], ["B", "build"], ["C", "build"]]);
const JIRA = "h3. AFK factory";
check("a story the factory never declined is not overruled", overruled(ticket("a human note", OFFER), JIRA), false);
check("a story back in intake after the factory's won't-do was overruled",
  overruled(ticket(`${JIRA}: won't do\nIt is about another product.`, "it is not, build it"), JIRA), true);
check("GitHub's header counts the same way",
  overruled({ ...ticket(), comments: [{ author: OWNER, body: "### AFK factory: won't do\nGibberish." }] }, "### AFK factory"), true);

const SPRINT = "project = JCLAW AND sprint in openSprints() AND issuetype not in (Epic, Sub-task)";
const HOLD = '(labels is EMPTY OR labels not in (afk-blocked, wont-do, no-afk)) AND status = "To Do" AND (assignee is EMPTY OR assignee = currentUser()) ORDER BY rank';
check("with no afk epic, a story is taken by its own label alone", intakeJql([]), `${SPRINT} AND labels = afk AND ${HOLD}`);
check("an afk epic's stories are taken with the labelled ones", intakeJql(["JCLAW-538", "JCLAW-902"]),
  `${SPRINT} AND (labels = afk OR "Epic Link" in (JCLAW-538, JCLAW-902)) AND ${HOLD}`);

const MERGE = "project = JCLAW AND labels = afk AND status = Review";
check("with no afk-merge epic, a story merges by its own label", mergeJql([]), `${MERGE} AND labels = afk-merge AND labels not in (no-afk-merge) ORDER BY rank`);
check("an afk-merge epic's stories merge with the labelled ones", mergeJql(["JCLAW-538"]),
  `${MERGE} AND (labels = afk-merge OR "Epic Link" in (JCLAW-538)) AND labels not in (no-afk-merge) ORDER BY rank`);
const merged = (who: string, at: string) => ({ __typename: "LabeledEvent", createdAt: at, actor: { login: who }, label: { name: "afk-merge" } });
check("afk-merge counts when the owner applied it last", ownerApplied(issue({ timelineItems: { nodes: [merged("stranger", BEFORE), merged(OWNER, LATER)] } }), OWNER, "afk-merge"), true);
check("afk-merge from anyone else does not", ownerApplied(issue({ timelineItems: { nodes: [merged(OWNER, BEFORE), merged("stranger", LATER)] } }), OWNER, "afk-merge"), false);

const brief = (met: boolean[]) => ({ brief: { acceptanceCriteria: met.map((m, i) => ({ criterion: `AC ${i + 1}`, met: m })) } });
check("a clean report with ordinary files may merge", mergeVerdict(brief([true, true]), []), undefined);
check("no report: another harness built it", mergeVerdict(undefined, []), "this harness has no report for it, so another developer's factory built it");
check("an unvalidated brief is refused", mergeVerdict({}, []), "the agent's brief did not validate, so its acceptance criteria are unconfirmed");
check("an unmet criterion is refused", mergeVerdict(brief([true, false]), []), "acceptance criteria not met: AC 2");
check("files that run on the Mac need a human", mergeVerdict(brief([true]), [".githooks/pre-push"]),
  "it changes files that run on your Mac once merged, which a human must read first: .githooks/pre-push");

// The board: one transition per state, what moves `since`, retention at its boundary, and the document's shape.
const T0 = new Date("2026-10-01T10:00:00.000Z"), T1 = new Date("2026-10-01T11:00:00.000Z"), T2 = new Date("2026-10-01T12:00:00.000Z");
const entering = (next: State) => transition(undefined, "JCLAW-7", next, T0, { summary: "Do it", autoMerge: true });
check("waiting carries its reason", entering({ state: "waiting", reason: "blocked by JCLAW-6 (To Do)" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "waiting", reason: "blocked by JCLAW-6 (To Do)", since: T0.toISOString() });
check("running carries its phase and when it started", entering({ state: "running", phase: "gate-1" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "running", phase: "gate-1", phaseStartedAt: T0.toISOString(), since: T0.toISOString() });
check("review carries nothing more", entering({ state: "review" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "review", since: T0.toISOString() });
check("blocked carries why the factory gave up", entering({ state: "blocked", reason: "the implementer made no commits" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "blocked", reason: "the implementer made no commits", since: T0.toISOString() });
check("refused carries the auto-merge refusal", entering({ state: "refused", reason: "acceptance criteria not met: AC 2" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "refused", reason: "acceptance criteria not met: AC 2", since: T0.toISOString() });
check("merged by the factory carries its merge commit", entering({ state: "merged", sha: "abc123", by: "factory" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "merged", sha: "abc123", by: "factory", since: T0.toISOString() });
check("merged by hand names the operator", entering({ state: "merged", sha: null, by: "operator" }),
  { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "merged", sha: null, by: "operator", since: T0.toISOString() });
check("a GitHub issue's source is github", transition(undefined, "GH-12", { state: "review" }, T0)?.source, "github");

const gate1 = entering({ state: "running", phase: "gate-1" })!;
const repair = transition(gate1, "JCLAW-7", { state: "running", phase: "repair-1" }, T1)!;
check("a new phase moves phaseStartedAt, not since", [repair.since, repair.state === "running" && repair.phaseStartedAt], [T0.toISOString(), T1.toISOString()]);
check("the same phase again changes nothing", transition(repair, "JCLAW-7", { state: "running", phase: "repair-1" }, T2), undefined);
const reviewed = transition(repair, "JCLAW-7", { state: "review" }, T2)!;
check("leaving running moves since and drops the phase", reviewed, { key: "JCLAW-7", summary: "Do it", source: "jira", autoMerge: true, state: "review", since: T2.toISOString() });
const waited = entering({ state: "waiting", reason: "all 2 slots are busy" })!;
const rewaited = transition(waited, "JCLAW-7", { state: "waiting", reason: "app/X.java is changed by JCLAW-5 (in review)" }, T1)!;
check("a new reason for the same state keeps since", [rewaited.since, rewaited.state === "waiting" && rewaited.reason], [T0.toISOString(), "app/X.java is changed by JCLAW-5 (in review)"]);
check("a state without its description keeps the old one", [transition(waited, "JCLAW-7", { state: "review" }, T1)?.summary], ["Do it"]);

const at = (ms: number) => new Date(T0.getTime() + ms);
const blockedAt = entering({ state: "blocked", reason: "x" })!, mergedAt = entering({ state: "merged", sha: "abc", by: "factory" })!;
check("merged and blocked stay until 14 days, and leave at 14 days",
  [expired(blockedAt, at(RETENTION_MS - 1)), expired(blockedAt, at(RETENTION_MS)), expired(mergedAt, at(RETENTION_MS - 1)), expired(mergedAt, at(RETENTION_MS))],
  [false, true, false, true]);
check("review, refused and waiting never age out",
  (["review", "refused", "waiting"] as const).map((state) => expired({ ...blockedAt, state, reason: "x" } as Entry, at(10 * RETENTION_MS))), [false, false, false]);

check("auto-merge from the story's label, its epic's, and not when exempted",
  [autoMerges({ labels: ["afk", "afk-merge"] }), autoMerges({ labels: ["afk"], parent: { labels: ["afk", "afk-merge"] } }),
    autoMerges({ labels: ["afk", "no-afk-merge"], parent: { labels: ["afk-merge"] } }), autoMerges({ labels: ["afk-merge", "no-afk-merge"] }),
    autoMerges({ labels: ["afk"], parent: { labels: ["afk"] } })],
  [true, true, false, false, false]);

const home = fs.mkdtempSync(path.join(os.tmpdir(), "jclaw-board-check-"));
const boardLogs = path.join(home, "logs"), boardFile = path.join(home, "board.json");
fs.mkdirSync(boardLogs);
for (const f of ["JCLAW-1-build.log", "JCLAW-1-report.json", "JCLAW-10-build.log", "factory.log", "plan.log"]) fs.writeFileSync(path.join(boardLogs, f), "");
const settings = { FACTORY_MAX_PARALLEL: 2, FACTORY_CPUS: 6, FACTORY_POLL_SECONDS: 120, FACTORY_MODEL: "claude-opus-5-5" };
const newBoard = () => new Board({ file: boardFile, logs: boardLogs, pid: 4242, startedAt: T0, settings, enabled: true });
const board = newBoard();
board.main = "f".repeat(40);
board.set("JCLAW-1", { state: "running", phase: "picked-up" }, { summary: "One", autoMerge: false }, T0);
board.set("JCLAW-10", { state: "waiting", reason: "blocked by JCLAW-1 (In Progress)" }, { summary: "Ten", autoMerge: true }, T1);
board.set("JCLAW-1", { state: "running", phase: "build" }, undefined, T2);
const written = JSON.parse(fs.readFileSync(boardFile, "utf8"));
check("the document carries exactly its fields", Object.keys(written), ["schema", "updatedAt", "harness", "settings", "stories"]);
check("…with the harness and its effective settings", [written.schema, written.updatedAt, written.harness, written.settings],
  [1, T2.toISOString(), { pid: 4242, startedAt: T0.toISOString(), main: "f".repeat(40) }, settings]);
check("stories by most recent change, each with only its own logs",
  written.stories.map((s: Story) => [s.key, s.state, s.logs]), [["JCLAW-1", "running", ["JCLAW-1-build.log", "JCLAW-1-report.json"]], ["JCLAW-10", "waiting", ["JCLAW-10-build.log"]]]);
check("a story entry carries exactly its fields", Object.keys(written.stories[0]).sort(),
  ["autoMerge", "key", "logs", "phase", "phaseStartedAt", "since", "source", "state", "summary"]);
board.set("JCLAW-2", { state: "blocked", reason: "x" }, { summary: "Two" }, T0);
check("a blocked story leaves the next write after 14 days", board.document(at(RETENTION_MS)).stories.map((s) => s.key), ["JCLAW-1", "JCLAW-10"]);
board.set("JCLAW-3", { state: "running", phase: "gate-merge" }, { summary: "Three" }, T2);
board.write(T2);
const restarted = newBoard();
restarted.load();
check("a restart keeps the order; a story left running waits, and one left landing is back in review",
  restarted.document(T2).stories.map((s) => [s.key, s.state]), [["JCLAW-3", "review"], ["JCLAW-1", "waiting"], ["JCLAW-10", "waiting"]]);
restarted.set("JCLAW-4", { state: "running", phase: "implement" }, { summary: "Four" }, T2);
restarted.abandoned("JCLAW-4", "its last run stopped before it finished", T2);
restarted.abandoned("JCLAW-3", "not running, so not touched", T2);
restarted.set("JCLAW-5", { state: "running", phase: "merge" }, { summary: "Five" }, T2);
restarted.abandoned("JCLAW-5", "a landing goes back to review", T2);
check("a run that ended without reporting waits, or is back in review from a landing; anything else is left alone",
  restarted.document(T2).stories.filter((s) => ["JCLAW-3", "JCLAW-4", "JCLAW-5"].includes(s.key)).map((s) => [s.key, s.state, "reason" in s ? s.reason : undefined]),
  [["JCLAW-5", "review", undefined], ["JCLAW-4", "waiting", "its last run stopped before it finished"], ["JCLAW-3", "review", undefined]]);
check("a disabled board writes nothing", (() => {
  const other = path.join(home, "plan-only.json");
  new Board({ file: other, logs: boardLogs, pid: 1, startedAt: T0, settings, enabled: false }).set("JCLAW-1", { state: "review" });
  return fs.existsSync(other);
})(), false);

// A reader in another process, racing a writer that replaces a large board as fast as it can, must never see a partial one.
const race = path.join(home, "race.json");
const big = (n: number) => JSON.stringify({ schema: 1, n, stories: Array.from({ length: 4000 }, (_, i) => ({ key: `JCLAW-${i}`, summary: "x".repeat(40) })) });
writeAtomically(race, big(0));
const reader = spawn(process.execPath, ["-e", `
  const fs = require("node:fs");
  let reads = 0, torn = 0;
  process.stdout.write("ready\\n");
  const until = Date.now() + 1500;
  while (Date.now() < until) {
    try { JSON.parse(fs.readFileSync(${JSON.stringify(race)}, "utf8")); } catch { torn++; }
    reads++;
  }
  process.stdout.write(JSON.stringify({ reads, torn }));
`]);
let out = "";
reader.stdout.on("data", (d) => (out += d));
await new Promise<void>((resolve) => reader.stdout.once("data", () => resolve()));
let writes = 0;
for (const until = Date.now() + 1500; Date.now() < until; ) writeAtomically(race, big(++writes));
await new Promise((resolve) => reader.on("exit", resolve));
const raced = JSON.parse(out.slice(out.indexOf("{")));
check("a reader racing the writer always parses a complete board", [writes > 50, raced.reads > 50, raced.torn], [true, true, 0]);
check("the temporary file does not outlive the write", fs.readdirSync(home).filter((f) => f.endsWith(".tmp")), []);
fs.rmSync(home, { recursive: true, force: true });

// Model-API overloads: which phase logs count, the backoff, the third strike, the reset, and a restart in between.
const run = (...lines: string[]) => `\n--- Run started: 2026-10-05T20:30:00.000Z ---\n${lines.join("\n")}\n`;
const OVERLOADED = "API Error: Repeated 529 Overloaded errors. The API is at capacity — this is usually temporary.";
check("Claude Code's 529 line is an overload", transientApiFailure(run("Bash(ls)", "working on it", OVERLOADED)), OVERLOADED);
check("a 500, 502, 503 or 504, an overloaded_error and a rate limit count, cut before their JSON body",
  ["API Error: 500 {\"type\":\"error\"}", "API Error: 502 Bad Gateway", "API Error: 503", "API Error: 504 Gateway Timeout",
    "API Error: 529 {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}", "API Error: Request rejected (429) · rate_limit_error"]
    .map((l) => transientApiFailure(run(l))),
  ["API Error: 500", "API Error: 502 Bad Gateway", "API Error: 503", "API Error: 504 Gateway Timeout", "API Error: 529", "API Error: Request rejected (429) · rate_limit_error"]);
check("a 400, a 401 and an ordinary failure are not",
  ["API Error: 400 {\"type\":\"invalid_request_error\"}", "API Error: 401 authentication_error", "Error: the implementer made no commits", "the agent wrote about a 503"]
    .map((l) => transientApiFailure(run(l))), [undefined, undefined, undefined, undefined]);
check("only the last run counts: an earlier run's overload does not", transientApiFailure(run(OVERLOADED) + run("Edit(app/X.java)", "Error: boom")), undefined);
check("an overload Claude Code recovered from early in the run does not",
  transientApiFailure(run(OVERLOADED, ...Array.from({ length: 12 }, (_, i) => `step ${i}`))), undefined);

const overloadHome = fs.mkdtempSync(path.join(os.tmpdir(), "jclaw-overload-check-"));
const overloadFile = path.join(overloadHome, "overloads.json");
const O0 = new Date("2026-10-05T20:40:00.000Z"), MIN = 60 * 1000;
const later = (ms: number) => new Date(O0.getTime() + ms);
const overloads = new Overloads(overloadFile);
const first = afterFailure(overloads, "JCLAW-1", run("working", OVERLOADED), O0);
check("an overload failure requeues the story for 10 minutes", first && [first.verdict, first.line],
  [{ requeue: true, count: 1, until: later(BACKOFF_MS) }, OVERLOADED]);
check("a non-overload failure blocks, and is not counted", [afterFailure(overloads, "JCLAW-2", run("Error: boom"), O0), afterFailure(overloads, "JCLAW-2", undefined, O0)],
  [undefined, undefined]);
check("…so the story it blocked is not held", overloads.holding("JCLAW-2", O0), undefined);
const story1 = { key: "JCLAW-1" }, other = { key: "JCLAW-3" };
const roundAt = (ms: number) => { const r = overloads.split([story1, other], later(ms)); return [r.ready.map((x) => x.key), r.held.map((h) => [h.story.key, h.until.toISOString()])]; };
check("the backoff keeps the requeued story out of a round 1 ms too soon; others still start",
  roundAt(BACKOFF_MS - 1), [["JCLAW-3"], [["JCLAW-1", later(BACKOFF_MS).toISOString()]]]);
check("…and lets it in at 10 minutes, and after", [roundAt(BACKOFF_MS)[0], roundAt(BACKOFF_MS + MIN)[0]], [["JCLAW-1", "JCLAW-3"], ["JCLAW-1", "JCLAW-3"]]);
check("the board's reason names the overload and when it is eligible again", overloadReason(later(BACKOFF_MS)),
  "the model API was overloaded; eligible again at 2026-10-05T20:50:00.000Z");
const restartedOverloads = new Overloads(overloadFile);
check("a restart keeps the count: the second overload backs off 20 minutes",
  afterFailure(restartedOverloads, "JCLAW-1", run(OVERLOADED), later(BACKOFF_MS))?.verdict, { requeue: true, count: 2, until: later(3 * BACKOFF_MS) });
check("the third consecutive overload blocks", afterFailure(restartedOverloads, "JCLAW-1", run(OVERLOADED), later(3 * BACKOFF_MS))?.verdict, { requeue: false, count: 3 });
check("…and forgets the count, so an operator's retry starts afresh",
  [overloads.holding("JCLAW-1", later(3 * BACKOFF_MS)), overloads.failed("JCLAW-1", O0)], [undefined, { requeue: true, count: 1, until: later(BACKOFF_MS) }]);
overloads.failed("JCLAW-1", O0);
overloads.completed("JCLAW-1");
check("a completed phase resets the count: two overloads, a completion, then one more requeues as the first",
  overloads.failed("JCLAW-1", O0), { requeue: true, count: 1, until: later(BACKOFF_MS) });
check("…and clears the backoff along with the count", (overloads.completed("JCLAW-1"), overloads.holding("JCLAW-1", O0)), undefined);
check("every agent phase resets the count; no gate does, or a passing gate-1 would reset a review's overloads forever",
  ["spec", "build", "implement", "rework", "repair-1", "review", "brief", "gate-1", "gate-2", "gate-review", "gate-merge"].map(resetsOverloads),
  [true, true, true, true, true, true, true, false, false, false, false]);
check("completing a story that never overloaded writes nothing", (overloads.completed("JCLAW-9"), JSON.parse(fs.readFileSync(overloadFile, "utf8"))), {});
check("a missing state file means no overloads", new Overloads(path.join(overloadHome, "absent.json")).holding("JCLAW-1", O0), undefined);
fs.rmSync(overloadHome, { recursive: true, force: true });

// landBranch between a throwaway checkout and its clone, signing with the operator's own git configuration.
const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), "jclaw-land-check-"));
const g = (repo: string, ...args: string[]) => execFileSync("/usr/bin/git", ["-C", repo, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
const write = (repo: string, file: string, text: string) => fs.writeFileSync(path.join(repo, file), text);
const commit = (repo: string, file: string, text: string) => {
  write(repo, file, text);
  g(repo, "add", file);
  g(repo, "commit", "--quiet", "-m", `change ${file}`);
};
const checkout = path.join(sandbox, "checkout"), clone = path.join(sandbox, "clone");
fs.mkdirSync(checkout);
g(checkout, "init", "--quiet", "--initial-branch=main");
commit(checkout, "base.txt", "base\n");
g(sandbox, "clone", "--quiet", checkout, clone);
// The factory's clone commits as its own identity, as setup gives it.
g(clone, "config", "user.name", "jclaw-factory-agent");
g(clone, "config", "user.email", "jclaw-factory-agent@localhost");
const operatorEmail = g(checkout, "config", "user.email");
// A finished story, as processStory leaves it: built in the clone, copied into the checkout, its tip recorded.
const story = (key: string, file: string, text: string) => {
  g(clone, "fetch", "--quiet", "origin", "main");
  g(clone, "branch", "--quiet", "-f", `agent/${key}`, "origin/main");
  g(clone, "checkout", "--quiet", `agent/${key}`);
  commit(clone, file, text);
  g(clone, "checkout", "--quiet", "--detach");
  g(checkout, "fetch", "--quiet", clone, `agent/${key}:agent/${key}`);
  return g(clone, "rev-parse", `agent/${key}`);
};
const regates: string[] = [];
type Regate = (branch: string) => Promise<string | undefined>;
const green: Regate = async (branch) => {
  regates.push(branch);
  return undefined;
};
const attempt = async (key: string, head: string, regate: Regate = green) => {
  try {
    return await landBranch({ checkout, clone, key, head, messages: [], regate });
  } catch (e) {
    return e instanceof MergeRefused ? `${e.permanent ? "refused" : "waiting"}: ${e.message}` : `error: ${e}`;
  }
};
const has = (repo: string, ref: string) => g(repo, "for-each-ref", "--format=%(refname)", ref) !== "";

const a = await attempt("T-1", story("T-1", "one.txt", "one\n"));
const main1 = g(checkout, "rev-parse", "main");
check("an unmoved main lands without a re-gate", typeof a === "object" && !a.regated && regates.length === 0 && main1 === a.merge, true);
check("the merge commit and the rebased commit are signed", g(checkout, "log", "--format=%G?", "-2", "main").split("\n"), ["G", "G"]);
check("the operator commits the merge and the rebased commit, so GitHub can verify them; the agent stays the author",
  g(checkout, "log", "--format=%ae %ce", "-2", "main").split("\n"), [`${operatorEmail} ${operatorEmail}`, `jclaw-factory-agent@localhost ${operatorEmail}`]);
check("the merge has two parents and names the branch", [g(checkout, "rev-list", "--parents", "-n1", "main").split(" ").length, g(checkout, "log", "-1", "--format=%s", "main")], [3, "Merge branch 'agent/T-1'"]);
check("the checkout fast-forwarded its working tree", fs.readFileSync(path.join(checkout, "one.txt"), "utf8"), "one\n");
check("a landed story is recognised by its merge commit; an unlanded one is not", [typeof a === "object" && landedAs(checkout, "T-1") === a.merge, landedAs(checkout, "T-9")], [true, undefined]);
check("both copies of the branch and the landing refs are gone",
  [has(checkout, "refs/heads/agent/T-1"), has(clone, "refs/heads/agent/T-1"), has(clone, "refs/heads/factory"), has(clone, "refs/factory")], [false, false, false, false]);

const head2 = story("T-2", "two.txt", "two\n");
commit(checkout, "elsewhere.txt", "moved\n");
const b = await attempt("T-2", head2);
check("a moved main re-gates the rebased branch, then lands", typeof b === "object" && b.regated && regates.at(-1) === "factory/land-T-2", true);
check("…and the result holds both the branch and the newer main", ["two.txt", "elsewhere.txt"].every((f) => fs.existsSync(path.join(checkout, f))), true);

const head3 = story("T-3", "base.txt", "theirs\n");
commit(checkout, "base.txt", "ours\n");
const before3 = g(checkout, "rev-parse", "main");
check("a conflict is refused and leaves main alone", [await attempt("T-3", head3), g(checkout, "rev-parse", "main") === before3, has(clone, "refs/heads/factory")],
  ["refused: agent/T-3 conflicts with main in base.txt", true, false]);

const head4 = story("T-4", "four.txt", "four\n");
write(checkout, "four.txt", "local work\n");
const c = await attempt("T-4", head4, async () => undefined);
check("an untracked local file the merge would overwrite waits, untouched", [typeof c === "string" && c.startsWith("waiting:"), fs.readFileSync(path.join(checkout, "four.txt"), "utf8")], [true, "local work\n"]);
fs.rmSync(path.join(checkout, "four.txt"));

g(checkout, "checkout", "--quiet", "-b", "elsewhere");
const d = await attempt("T-4", head4, async () => undefined);
check("with main not checked out, the ref moves and HEAD stays put",
  [typeof d === "object" && g(checkout, "rev-parse", "main") === d.merge, g(checkout, "symbolic-ref", "--short", "HEAD")], [true, "elsewhere"]);
g(checkout, "checkout", "--quiet", "main");

const head5 = story("T-5", "five.txt", "five\n");
commit(checkout, "other.txt", "moved again\n");
check("a red re-gate is refused", await attempt("T-5", head5, async () => "FooTest failed"),
  "refused: main had moved since the branch was built, and the suite is red on the rebased branch: FooTest failed");
check("a branch that moved since the offer is refused", await attempt("T-5", "0".repeat(40)),
  "refused: agent/T-5 has moved since the factory offered it for review; merge it by hand");

const head6 = story("T-6", "six.txt", "six\n");
commit(checkout, "seven.txt", "moved once more\n");
const resetTo = g(checkout, "rev-parse", "main~1");
const resetDuringGate: Regate = async () => {
  g(checkout, "reset", "--quiet", "--hard", resetTo);
  return undefined;
};
check("main reset back during the re-gate waits, and keeps the reset", [await attempt("T-6", head6, resetDuringGate), g(checkout, "rev-parse", "main") === resetTo],
  ["waiting: your checkout's main moved while the branch was landing", true]);

// The clone took a commit the checkout then amended, so the two mains have diverged.
const mirror = path.join(sandbox, "mirror");
g(sandbox, "clone", "--quiet", checkout, mirror);
commit(checkout, "amended.txt", "draft\n");
followMain(mirror);
write(checkout, "amended.txt", "final\n");
g(checkout, "commit", "--quiet", "--amend", "--all", "--no-edit");
followMain(mirror);
check("the clone's main follows an amend in the checkout",
  [g(mirror, "rev-parse", "main") === g(checkout, "rev-parse", "main"), fs.readFileSync(path.join(mirror, "amended.txt"), "utf8")], [true, "final\n"]);

// Branches an earlier run left as main then moved on: one empty, one a worktree holds, one with a commit of its own.
const stale = g(mirror, "rev-parse", "main");
g(mirror, "branch", "agent/T-7", stale);
g(mirror, "branch", "agent/T-8", stale);
g(mirror, "worktree", "add", "--quiet", path.join(sandbox, "held"), "agent/T-8");
g(mirror, "branch", "agent/T-9", g(mirror, "commit-tree", "-p", stale, "-m", "work", `${stale}^{tree}`));
const worked = g(mirror, "rev-parse", "agent/T-9");
commit(checkout, "later.txt", "later\n");
followMain(mirror);
check("a retried story's empty branch restarts from main; a held one and one with commits stay",
  [restartEmptyBranch(mirror, "agent/T-7"), g(mirror, "rev-parse", "agent/T-7") === g(mirror, "rev-parse", "main"),
    restartEmptyBranch(mirror, "agent/T-8"), g(mirror, "rev-parse", "agent/T-8") === stale,
    restartEmptyBranch(mirror, "agent/T-9"), g(mirror, "rev-parse", "agent/T-9") === worked],
  [0, true, 0, true, 1, true]);
fs.rmSync(sandbox, { recursive: true, force: true });
