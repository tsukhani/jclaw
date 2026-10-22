import { z } from "zod";

// What the planner predicts for one story: the files it will change, whether to build it with BMAD and why, and, when
// building it would be wrong, why it should not be built at all.
export type StoryPlan = { files: Set<string>; bmad: boolean; why: string; wontDo?: string };
const PlanAnswer = z.record(
  z.string(),
  z.object({ files: z.array(z.string()), bmad: z.boolean(), why: z.string(), wontDo: z.string().nullish() }),
);

// The planner's <plan> JSON by story key, or undefined when it does not parse.
export const parsePlan = (raw: string | undefined): Map<string, StoryPlan> | undefined => {
  if (raw === undefined) return undefined;
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    return undefined;
  }
  const parsed = PlanAnswer.safeParse(json);
  if (!parsed.success) return undefined;
  return new Map(
    Object.entries(parsed.data).map(([key, p]) => [
      key,
      { files: new Set(p.files.map((f) => f.replace(/^\.\//, ""))), bmad: p.bmad, why: p.why.trim(), wontDo: p.wontDo?.trim() || undefined },
    ]),
  );
};

export type BuildMode = { bmad: boolean; why: string };

// A label settles it, and `no-bmad` wins because the harness writes `bmad` itself. Otherwise the planner decides, and a
// story it gave no verdict runs plain.
export const buildMode = (labels: string[], plan: StoryPlan | undefined): BuildMode => {
  if (labels.includes("no-bmad")) return { bmad: false, why: "labelled no-bmad" };
  if (labels.includes("bmad")) return { bmad: true, why: "labelled bmad" };
  if (plan) return { bmad: plan.bmad, why: plan.why };
  return { bmad: false, why: "the planner gave no verdict" };
};

// A BMAD halt's status and reason, read from a spec's `## Auto Run Result`, or from the `# BMad Build Auto Result` file
// bmad-build-auto writes instead when it stopped before writing a spec.
export const bmadOutcome = (text: string): { status: string; result: string } => ({
  status: text.match(/^status:\s*['"]?([\w-]+)/m)?.[1] ?? "missing",
  result: text.split(/^(?:## Auto Run Result|# BMad Build Auto Result)$/m)[1]?.trim() ?? "",
});

// Why a BMAD build stopped short of done. A run cut off before its completion signal never rewrote the result the spec
// phase left in the file, so that text is not the reason.
export const buildHaltReason = (result: string, completed: boolean): string =>
  completed ? result : "The build agent's run ended before BMAD finished, so it recorded no reason; its commits stay on the branch.";

// Two stories that change the same file conflict at merge time even when each passes its own gate. In board order, a
// story is picked unless its files meet those of a branch awaiting review (`owners`) or of a story picked before it.
// A story whose files are unknown (absent from `files`) runs only when nothing else is in flight, and then alone.
export const pickNonOverlapping = <T extends { key: string }>(
  stories: T[],
  files: Map<string, Set<string>>,
  inFlight: Map<string, string>,
): { picked: T[]; deferred: { key: string; reason: string }[] } => {
  const owners = new Map(inFlight);
  const picked: T[] = [];
  const deferred: { key: string; reason: string }[] = [];
  let alone = false;
  for (const story of stories) {
    const mine = files.get(story.key);
    if (alone || (mine === undefined && (owners.size > 0 || picked.length > 0))) {
      deferred.push({ key: story.key, reason: "its files are unknown, so it runs only when nothing else is in flight" });
      continue;
    }
    if (mine === undefined) {
      picked.push(story);
      alone = true;
      continue;
    }
    const clash = [...mine].filter((f) => owners.has(f));
    if (clash.length > 0) {
      deferred.push({ key: story.key, reason: clash.map((f) => `${f} is changed by ${owners.get(f)}`).join("; ") });
      continue;
    }
    picked.push(story);
    for (const f of mine) owners.set(f, `${story.key} (this run)`);
  }
  return { picked, deferred };
};

// Files that run on the operator's Mac once a branch is merged (git hooks, build, install and CI scripts, the factory
// itself) or that instruct every later agent: a review must read these line by line.
const SENSITIVE = [
  /^\.githooks\//, /^\.sandcastle\//, /^\.devcontainer\//, /^\.claude\//, /^gradle\/wrapper\//, /^gradlew(\.bat)?$/,
  /^jclaw\.sh$/, /^(build|settings)\.gradle\.kts$/, /^\.play-version$/, /(^|\/)package\.json$/,
  /(^|\/)(pnpm-lock\.yaml|package-lock\.json)$/, /(^|\/)Dockerfile$/, /^docker-entrypoint\.sh$/, /^Jenkinsfile/,
  /^(AGENTS|CLAUDE)\.md$/,
];
export const sensitivePaths = (files: string[]): string[] => files.filter((f) => SENSITIVE.some((r) => r.test(f)));

// A running story holds its predicted files (undefined: unknown, so everything) until its build phase completes, and its
// branch's real diff against main from then on, recomputed each round.
export type RunningStory = { predicted: Set<string> | undefined; built: boolean };
export const heldFiles = (key: string, run: RunningStory, changedOn: (key: string) => string[]): Set<string> | undefined =>
  run.built ? new Set(changedOn(key)) : run.predicted;

// The phases whose completion means the branch holds the story's real diff.
export const buildsTheDiff = (phase: string) => phase === "implement" || phase === "build" || phase === "rework";
