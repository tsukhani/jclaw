// The AFK factory: intake from Jira and, when configured, GitHub issues, then per story → implement → full-suite gate
// (baselined on main, with repair) → review → gate → brief → write-back to the story's tracker, up to
// FACTORY_MAX_PARALLEL stories at once, each in its own sandbox, polling until signalled.
// Local only: branches are never pushed, and the agent never touches a tracker.
import * as fs from "node:fs";
import { execFileSync } from "node:child_process";
import { format } from "node:util";
import * as sandcastle from "@ai-hero/sandcastle";
import { z } from "zod";
import { CPUS, assertReady, ensureBmadSeed, ensureGradleSeed, ensureImage, factoryHooks, factorySandbox, gatewayUp, installBmad, planHooks } from "./factory.ts";
import { githubTracker } from "./github.ts";
import { jira } from "./jira.ts";
import { overruled, promptContext, rejectionFeedback, type Snapshot, type Tracker } from "./tracker.ts";
import { buildMode, parsePlan, pickNonOverlapping, sensitivePaths, type BuildMode, type StoryPlan } from "./plan.ts";
import { CLONE, ENV_FILE, FACTORY_HOME, HERE, LOGS, REPO_ROOT, SETTINGS_FILE, STATE } from "./paths.ts";

// Sandcastle's lines too: one log spans every launchd restart. Local time, to read beside `pmset -g log`.
for (const level of ["log", "error", "warn"] as const) {
  const write = console[level].bind(console);
  console[level] = (...args: unknown[]) => write(`${new Date().toLocaleString("sv-SE")} ${format(...args)}`);
}

const REPO = CLONE;
const MODEL = process.env.FACTORY_MODEL ?? "claude-opus-5-5";
const MAX_REPAIRS = 2;
// Each sandbox peaks near 5 GB during the full suite.
const LIMIT = Number(process.env.FACTORY_MAX_PARALLEL || 2);
if (!Number.isInteger(LIMIT) || LIMIT < 1) throw new Error(`FACTORY_MAX_PARALLEL must be a positive integer, got "${process.env.FACTORY_MAX_PARALLEL}"`);
const gitIn = (repo: string, ...args: string[]) => execFileSync("/usr/bin/git", ["-C", repo, ...args], { encoding: "utf8" }).trim();
// The checkout this harness lives in is the clone's origin: stories start from its main, and finished branches go back.
const CHECKOUT = REPO_ROOT;
const LESSONS = `${FACTORY_HOME}/lessons.md`;
const GATE_DEADLINE_SECONDS = 1800;
const Brief = z.object({
  summary: z.string(),
  acceptanceCriteria: z.array(z.object({ criterion: z.string(), met: z.boolean(), evidence: z.string() })),
  decisions: z.array(z.string()),
  risks: z.array(z.string()),
  testsRun: z.array(z.string()),
});
type Brief = z.infer<typeof Brief>;

// The agent authenticates through the gateway, which reads the credential from .env itself.
const agent = sandcastle.claudeCode(MODEL);

const github = githubTracker();
const TRACKERS: Tracker[] = github ? [jira, github] : [jira];
const trackerFor = (key: string): Tracker => {
  const tracker = TRACKERS.find((t) => t.owns(key));
  if (!tracker) throw new Error(`no story source owns ${key}`);
  return tracker;
};
// A source that cannot be read this poll contributes nothing, so a GitHub outage or a bad token never stops Jira work;
// while it fails, its stories in review hold no files.
const fromAll = async <T>(what: string, read: (t: Tracker) => Promise<T[]>): Promise<T[]> =>
  (
    await Promise.all(
      TRACKERS.map(async (t) => {
        const topic = `source ${t.name} ${what}`;
        try {
          const found = await read(t);
          lastSaid.delete(topic);
          return found;
        } catch (error) {
          note(topic, `[factory] could not read ${what} from ${t.name}, so it is skipped this poll: ${errorText(error)}`);
          return [];
        }
      }),
    )
  ).flat();

const processStory = async (picked: Snapshot, mode: BuildMode): Promise<void> => {
  const key = picked.key;
  const branch = `agent/${key}`;
  const tracker = trackerFor(key);
  const m = tracker.markup;
  const feedback = rejectionFeedback(picked, tracker.header);
  const lessons = fs.existsSync(LESSONS) ? fs.readFileSync(LESSONS, "utf8").trim() : "";
  const promptArgs = { KEY: key, SUMMARY: picked.summary, DESCRIPTION: promptContext(picked), LESSONS: lessons || "None yet." };
  const withFeedback = { ...promptArgs, FEEDBACK: feedback || "None: this is the first submission." };
  let bmadVersion: string | undefined;
  let freshBuild = false;
  // A resumed or reworked story keeps the way its first build went, which the `bmad` label records.
  const builtWithBmad = () => bmadVersion !== undefined || picked.labels.includes("bmad");
  let lesson: string | undefined;
  const logTo = (phase: string) => ({ type: "file" as const, path: `${LOGS}/${key}-${phase}.log` });
  const timings: Record<string, number> = {};
  const gates: string[] = [];
  const environmentFailures = new Set<string>();
  const flakes: { gate: string; failure: string }[] = [];
  const timed =async <T>(phase: string, body: () => Promise<T>): Promise<T> => {
    const started = Date.now();
    try {
      return await body();
    } finally {
      timings[phase] = Math.round((Date.now() - started) / 1000);
      console.log(`[${key} ${phase}] ${timings[phase]}s`);
    }
  };

  const build = async (): Promise<Brief | undefined> => {
    const git = (...args: string[]) => gitIn(REPO, ...args);
    try {
      git("rev-parse", "--verify", "--quiet", `refs/heads/${branch}`);
    } catch {
      if (feedback !== undefined) throw new Error(`${branch} is not on this machine, so another developer's harness built it: assign the story to them`);
      git("branch", branch, "main");
    }
    const alreadyAhead = Number(git("rev-list", "--count", `main..${branch}`));

    await using sandbox = await sandcastle.createSandbox({ cwd: REPO, branch, sandbox: factorySandbox(), hooks: factoryHooks });
    await assertReady(sandbox);

    type Diagnostic = { kind: string; file: string | null; line: number | null; message: string };
    const suiteOf = (r: Diagnostic) => r.message.split(".")[0];
    // Only a passed report clears a suite: a re-run that wrote no reports would otherwise forgive every failure.
    const failingSuites = async (suites: string[]): Promise<Set<string>> => {
      const r = await sandbox.exec(
        `rm -rf test-result; ./gradlew playAutotest -Ptests=${suites.join(",")} > /tmp/rerun.log 2>&1; ls test-result | grep passed.html || true`,
      );
      const passed = new Set(r.stdout.split("\n").filter(Boolean).map((f) => f.replace(".class.passed.html", "")));
      return new Set(suites.filter((s) => !passed.has(s)));
    };
    // Baseline on main in the same worktree, so a suite this image cannot run is never handed to the agent.
    const onMain = async <T>(body: () => Promise<T>): Promise<T> => {
      if ((await sandbox.exec("git status --porcelain --untracked-files=no")).stdout.trim()) {
        throw new Error(`worktree has uncommitted changes; cannot switch to main for the baseline`);
      }
      if ((await sandbox.exec("git switch --detach main")).exitCode !== 0) throw new Error("git switch to main failed");
      try {
        return await body();
      } finally {
        await sandbox.exec(`git switch ${branch}`);
      }
    };
    // The full suite runs here, not in the agent: a blocking 10-minute Bash call would trip the idle timeout.
    const gate = async (label: string): Promise<Diagnostic[]> =>
      timed(label, async () => {
        const run = await sandbox.exec(`timeout ${GATE_DEADLINE_SECONDS} ./jclaw.sh diagnostics --tests --out /tmp/diag.json > /tmp/gate.log 2>&1`);
        // A hung suite would otherwise hold the sandbox, and the story, forever.
        if (run.exitCode === 124) throw new Error(`the full-suite gate did not finish within ${GATE_DEADLINE_SECONDS / 60} minutes`);
        if (run.exitCode === 2) {
          throw new Error(`diagnostics harness failure (exit 2):\n${(await sandbox.exec("tail -40 /tmp/gate.log")).stdout}`);
        }
        const raw = (await sandbox.exec("cat /tmp/diag.json")).stdout;
        const passed = (await sandbox.exec("ls test-result | grep -c passed.html")).stdout.trim();
        fs.writeFileSync(`${LOGS}/${key}-${label}-diag.json`, raw);
        const records = JSON.parse(raw) as Diagnostic[];
        let fresh = records;
        if (records.length > 0 && records.every((r) => r.kind === "test")) {
          // A suite that passes alone was the concurrency flake; one that also fails on main is the environment.
          const suites = [...new Set(records.map(suiteOf))];
          const failingAlone = await failingSuites(suites);
          const failingOnMain = failingAlone.size > 0 ? await onMain(() => failingSuites([...failingAlone])) : new Set<string>();
          for (const s of failingOnMain) environmentFailures.add(s);
          for (const r of records) if (!failingAlone.has(suiteOf(r))) flakes.push({ gate: label, failure: r.message.split("\n")[0] });
          fresh = records.filter((r) => failingAlone.has(suiteOf(r)) && !failingOnMain.has(suiteOf(r)));
        }
        gates.push(`${label}: ${passed} classes passed, ${fresh.length === 0 ? "no new failures" : `${fresh.length} new failures`}`);
        return fresh;
      });

    let current: sandcastle.SandboxRunResult | undefined;
    if (feedback !== undefined) {
      current = await timed("rework", () =>
        sandbox.run({ name: `rework ${key}`, agent, promptFile: `${HERE}/prompts/rework.md`, promptArgs: withFeedback, idleTimeoutSeconds: 1200, logging: logTo("rework") }),
      );
      if (current.commits.length === 0) throw new Error("the rework made no commits");
      const said = [...current.stdout.matchAll(/<lesson>([\s\S]*?)<\/lesson>/g)].at(-1)?.[1].trim();
      if (said && said.toLowerCase() !== "none") {
        lesson = said;
        fs.appendFileSync(LESSONS, `- ${said} (${key} review, ${new Date().toISOString().slice(0, 10)})\n`);
      }
    } else if (alreadyAhead > 0) {
      console.log(`[${key} implement] skipped: ${branch} is already ${alreadyAhead} commit(s) ahead of main`);
    } else if (mode.bmad) {
      // BMAD's bmad-build-auto writes the spec, then builds from it. Halting in between means a ticket it can read more
      // than one way goes back with its questions before any code is written.
      freshBuild = true;
      bmadVersion = await installBmad(sandbox);
      if (!picked.labels.includes("bmad")) await tracker.addLabel(key, "bmad");
      // Each halt records its status in the spec's frontmatter, or in a result file when it stopped before writing one.
      const outcome = async (file: string) => {
        const text = (await sandbox.exec(`cat '${file}'`)).stdout;
        fs.writeFileSync(`${LOGS}/${key}-spec.md`, text);
        return { status: text.match(/^status:\s*['"]?([\w-]+)/m)?.[1] ?? "missing", result: text.split("## Auto Run Result")[1]?.trim() ?? "" };
      };
      await timed("spec", () =>
        sandbox.run({ name: `spec ${key}`, agent, promptFile: `${HERE}/prompts/bmad-spec.md`, promptArgs, idleTimeoutSeconds: 1200, logging: logTo("spec") }),
      );
      const spec = (await sandbox.exec("ls -t _bmad-output/implementation-artifacts/*.md 2>/dev/null | head -1")).stdout.trim();
      if (!spec) throw new Error("BMAD wrote no spec");
      const planned = await outcome(spec);
      if (planned.status !== "ready-for-dev") throw new Error(`BMAD stopped at the spec (${planned.status}):\n${planned.result}`);
      current = await timed("build", () =>
        sandbox.run({ name: `build ${key}`, agent, promptFile: `${HERE}/prompts/bmad-build.md`, promptArgs: { KEY: key, SPEC: spec }, idleTimeoutSeconds: 1200, logging: logTo("build") }),
      );
      const built = await outcome(spec);
      if (built.status !== "done") throw new Error(`BMAD stopped building (${built.status}):\n${built.result}`);
      if (current.commits.length === 0) throw new Error("the BMAD build made no commits");
    } else {
      freshBuild = true;
      current = await timed("implement", () =>
        sandbox.run({ name: `implement ${key}`, agent, promptFile: `${HERE}/prompts/implement.md`, promptArgs, idleTimeoutSeconds: 1200, logging: logTo("implement") }),
      );
      if (current.commits.length === 0) throw new Error("the implementer made no commits");
    }

    let failures = await gate("gate-1");
    for (let round = 1; failures.length > 0 && round <= MAX_REPAIRS; round++) {
      const message =
        `The harness ran the full suite on branch ${branch} (ticket ${key}) and these failures are new relative to main. ` +
        `Fix them, re-run the affected classes, commit, then output <promise>COMPLETE</promise>.\n\n${JSON.stringify(failures, null, 2)}`;
      const options = { idleTimeoutSeconds: 1200, logging: logTo(`repair-${round}`) };
      const previous = current;
      // resume() re-spreads the original run's promptArgs onto an inline prompt, which Sandcastle rejects unless emptied.
      current = await timed(`repair-${round}`, () =>
        previous?.resume ? previous.resume(message, { ...options, promptArgs: {} }) : sandbox.run({ name: `repair ${key}`, agent, prompt: message, ...options }),
      );
      failures = await gate(`gate-${round + 1}`);
    }
    if (failures.length > 0) throw new Error(`the suite is still red after ${MAX_REPAIRS} repair rounds: ${failures.map((f) => f.message.split("\n")[0]).join("; ")}`);

    const review = await timed("review", () =>
      sandbox.run({ name: `review ${key}`, agent, promptFile: `${HERE}/prompts/review.md`, promptArgs: withFeedback, idleTimeoutSeconds: 1200, logging: logTo("review") }),
    );
    if (review.commits.length > 0) {
      const after = await gate("gate-review");
      if (after.length > 0) throw new Error(`the review left the suite red: ${after.map((f) => f.message.split("\n")[0]).join("; ")}`);
    }

    const parse = (stdout: string) => {
      const match = [...stdout.matchAll(/<brief>([\s\S]*?)<\/brief>/g)].at(-1);
      return match ? Brief.safeParse(JSON.parse(match[1])) : undefined;
    };
    if (!review.resume) throw new Error("no captured review session to extract the brief from");
    let briefRun = await timed("brief", () =>
      review.resume!(fs.readFileSync(`${HERE}/prompts/brief.md`, "utf8"), { promptArgs: {}, logging: logTo("brief") }),
    );
    let brief = parse(briefRun.stdout);
    if (!brief?.success) {
      briefRun = await briefRun.resume!(
        `Your <brief> did not validate: ${brief ? brief.error.message : "no <brief> tag found"}. Re-emit only the corrected <brief> block.`,
        { promptArgs: {} },
      );
      brief = parse(briefRun.stdout);
    }
    return brief?.success ? brief.data : undefined;
  };

  // The comment carries the harness's own facts beside the agent's brief: the agent cannot see the gates.
  const reviewComment = (brief: Brief | undefined, sensitive: string[]) => {
    const closes = tracker.mergeMessage(key);
    const merge = closes ? `git merge --no-ff -m "Merge branch '${branch}'" -m "${closes}" ${branch}` : `git merge --no-ff ${branch}`;
    const lines = [`${tracker.header}: ready for review`, `Local branch ${m.code(branch)} in the checkout of this comment's author, not pushed. Its commits are unsigned, which GitHub's main refuses, so re-sign them as you merge: ${m.code(`git rebase --force-rebase --gpg-sign main ${branch}`)}, then ${m.code(merge)}; /deploy ships it. Model: ${MODEL}.`];
    if (builtWithBmad()) {
      lines.push(`Specced and built by BMAD${bmadVersion ? ` ${bmadVersion}` : ""} (bmad-build-auto). Its spec, with its own review's triage and deferred findings: ${m.code(`~/.jclaw-factory/logs/${key}-spec.md`)}.`);
    }
    if (freshBuild) lines.push(`Built ${mode.bmad ? "with BMAD" : "plain"} because: ${mode.why}`);
    if (feedback !== undefined) lines.push("Reworked after your review.");
    if (sensitive.length > 0) {
      lines.push("", m.heading("(!) Runs on your Mac once merged"), ...sensitive.map((f) => m.bullet(m.code(f))),
        "These files run during build, test, commit or push, or instruct later agents: read them line by line before merging.");
    }
    lines.push("");
    if (brief) {
      lines.push(brief.summary, "", m.heading("Acceptance criteria"), ...brief.acceptanceCriteria.map((c) => m.bullet(`${c.met ? m.met : m.unmet} ${c.criterion}: ${c.evidence}`)));
      if (brief.decisions.length) lines.push("", m.heading("Decisions"), ...brief.decisions.map(m.bullet));
      if (brief.risks.length) lines.push("", m.heading("For the reviewer"), ...brief.risks.map(m.bullet));
    } else {
      lines.push("The agent's brief did not validate; see the run log.");
    }
    if (lesson) {
      lines.push("", m.heading("Lesson recorded"), lesson, `Every future story's prompts now include it (${m.code("~/.jclaw-factory/lessons.md")}); promote it into AGENTS.md or delete it there.`);
    }
    lines.push("", m.heading("Harness gates"), ...gates.map(m.bullet));
    if (environmentFailures.size) lines.push(m.bullet(`Also failing on main in the sandbox, so not counted: ${[...environmentFailures].join(", ")}`));
    lines.push(...flakes.map((f) => m.bullet(`${f.gate}: failed in the full suite but passed alone, so not counted: ${m.code(f.failure)}`)));
    lines.push(m.bullet(`Timings: ${Object.entries(timings).map(([k, v]) => `${k} ${v}s`).join(", ")}`));
    return lines.join("\n");
  };

  if (!(await tracker.claim(key))) {
    console.log(`[${key}] claimed by another harness; leaving it`);
    return;
  }
  await tracker.started(key);
  try {
    if (feedback === "") throw new Error("it came back from review without a comment: say in a comment what to change, then send it back");
    const brief = await build();
    // A local branch in the operator's checkout, where merging it into main puts it in the next /deploy.
    gitIn(CHECKOUT, "fetch", "--quiet", REPO, `${branch}:${branch}`);
    const sensitive = sensitivePaths(gitIn(REPO, "diff", "--name-only", `main...${branch}`).split("\n").filter(Boolean));
    if (sensitive.length > 0) console.log(`[${key}] changes files that run on the Mac once merged: ${sensitive.join(", ")}`);
    await tracker.reviewing(key);
    await tracker.comment(key, reviewComment(brief, sensitive));
    fs.writeFileSync(`${LOGS}/${key}-report.json`, JSON.stringify({ key, branch, model: MODEL, buildMode: mode, timings, gates, brief, environmentFailures: [...environmentFailures], flakes }, null, 2));
    console.log(`[${key} done] → Review\n` + execFileSync("/usr/bin/git", ["-C", REPO, "log", "--stat", "--format=%h %an %s", `main..${branch}`], { encoding: "utf8" }));
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    console.log(`[${key} blocked] ${reason}`);
    await tracker.blocked(key);
    await tracker.comment(key, `${tracker.header} gave up\nBranch ${m.code(branch)} (local). ${reason}\n\nRemove the ${m.code("afk-blocked")} label to let the factory retry.`);
    throw error;
  }
};

const POLL_SECONDS = Number(process.env.FACTORY_POLL_SECONDS || 120);
if (!(POLL_SECONDS > 0)) throw new Error(`FACTORY_POLL_SECONDS must be a positive number, got "${process.env.FACTORY_POLL_SECONDS}"`);
// FACTORY_TICKET pins stories by key (comma-separated) for one round, as does FACTORY_PLAN_ONLY; otherwise the factory
// watches the active sprint's `afk` stories until it is signalled.
const PINNED = process.env.FACTORY_TICKET?.split(",").map((k) => k.trim()).filter(Boolean);
const ONE_ROUND = PINNED !== undefined || Boolean(process.env.FACTORY_PLAN_ONLY);

// A repeated state is logged once, not on every poll.
const lastSaid = new Map<string, string>();
const note = (topic: string, message: string) => {
  if (lastSaid.get(topic) !== message) console.log(message);
  lastSaid.set(topic, message);
};
// fetch rejects with a bare "fetch failed"; the reason (ENOTFOUND, ECONNRESET, a connect timeout) is on its cause.
const errorText = (error: unknown): string => {
  if (!(error instanceof Error)) return String(error);
  const text = error.message || error.name;
  const code = (error as { code?: unknown }).code;
  const own = typeof code === "string" && !text.includes(code) ? `${text} (${code})` : text;
  return error.cause === undefined ? own : `${own}: ${errorText(error.cause)}`;
};

const changedOn = (key: string): string[] => {
  try {
    const out = execFileSync("/usr/bin/git", ["-C", REPO, "diff", "--name-only", `main...agent/${key}`], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
    return out.split("\n").filter(Boolean);
  } catch {
    // No branch yet: the story has changed nothing.
    return [];
  }
};

// An agent reads each ticket and the code it points at, predicts the files the story will change, and decides whether
// it needs BMAD.
const predictPlans = async (stories: Snapshot[]): Promise<Map<string, StoryPlan>> => {
  const planBranch = "factory/plan";
  gitIn(REPO, "branch", "-f", planBranch, "main");
  try {
    await using sandbox = await sandcastle.createSandbox({ cwd: REPO, branch: planBranch, sandbox: factorySandbox(), hooks: planHooks });
    const STORIES = stories
      .map((s) => {
        const own = changedOn(s.key);
        return [`## ${s.key}: ${s.summary}`, promptContext(s), own.length ? `Its branch already changes: ${own.join(", ")}` : ""].join("\n\n");
      })
      .join("\n\n");
    const run = await sandbox.run({ name: "plan", agent, promptFile: `${HERE}/prompts/plan.md`, promptArgs: { STORIES }, logging: { type: "file", path: `${LOGS}/plan.log` } });
    const plans = parsePlan([...run.stdout.matchAll(/<plan>([\s\S]*?)<\/plan>/g)].at(-1)?.[1]);
    if (!plans) {
      console.log("[plan] the planner's answer did not parse, so every story's files are unknown and it runs plain");
      return new Map();
    }
    for (const [k, plan] of plans) for (const f of changedOn(k)) plan.files.add(f);
    return plans;
  } finally {
    try {
      gitIn(REPO, "branch", "-D", planBranch);
    } catch {
      // Sandcastle still holds its worktree; the next run's `branch -f` resets it.
    }
  }
};

// Stories this process is running, with the files each was predicted to change (undefined: unknown).
const running = new Map<string, Set<string> | undefined>();
const predicted = new Map<string, { updated: string; plan: StoryPlan }>();

// A source refused the story as it stands (GitHub: its text changed after the owner's label). Blocking it takes it out of
// the next intake, so the comment is posted once.
const refuse = async (story: Snapshot) => {
  note(story.key, `[plan] ${story.key} refused: ${story.refused}`);
  if (process.env.FACTORY_PLAN_ONLY) return;
  const tracker = trackerFor(story.key);
  const m = tracker.markup;
  await tracker.blocked(story.key);
  await tracker.comment(story.key, `${tracker.header} will not start this\n${story.refused}, so the agent would read text you have not approved. To approve the issue as it now reads, remove the ${m.code("afk")} label and add it again, then remove ${m.code("afk-blocked")}.`);
};

// The planner judged the story not worth building. It is labelled and explained, never closed: the reviewer confirms by
// closing it, or overrules by removing `wont-do`.
const decline = async (story: Snapshot, reason: string) => {
  console.log(`[plan] ${story.key} declined: ${reason}`);
  if (process.env.FACTORY_PLAN_ONLY) return;
  const tracker = trackerFor(story.key);
  const m = tracker.markup;
  await tracker.addLabel(story.key, "wont-do");
  await tracker.comment(story.key, `${tracker.header}: won't do\n${reason}\n\nNothing was built. Close it if you agree. If not, remove the ${m.code("wont-do")} label, and the factory will build it as written without judging it again.`);
};

// Starts as many stories as there are free slots, returning their runs.
const round = async (candidates: Snapshot[]): Promise<Promise<void>[]> => {
  const free = LIMIT - running.size;
  // A story runs only once every blocker is Done, which must mean merged: its branch then comes off a main that already
  // holds the blocker's code in whatever form it landed, so no branch reaches the reviewer carrying another story's commits.
  const unblocked: Snapshot[] = [];
  for (const story of candidates.filter((c) => !running.has(c.key))) {
    if (story.refused) {
      await refuse(story);
      continue;
    }
    const open = story.blockedBy.filter((b) => !b.done);
    if (open.length === 0) unblocked.push(story);
    else note(story.key, `[plan] ${story.key} waits: blocked by ${open.map((b) => `${b.key} (${b.status})`).join(", ")}`);
  }
  if (free <= 0 || unblocked.length === 0) return [];

  // ff-only: nothing in the factory commits to main, so a divergence is for a human to look at, not to merge.
  gitIn(REPO, "fetch", "--quiet", "origin", "main");
  gitIn(REPO, "merge", "--ff-only", "--quiet", "origin/main");
  ensureBmadSeed(running.size === 0);
  if (!ensureImage(REPO, gitIn(REPO, "rev-parse", "main"), `${LOGS}/image-build.log`)) return [];

  // A story pinned by FACTORY_TICKET may itself be in review; its own branch is not someone else's work.
  const inFlight = new Map<string, string>();
  for (const k of await fromAll("stories in review", (t) => t.inReview())) {
    if (!unblocked.some((s) => s.key === k)) for (const f of changedOn(k)) inFlight.set(f, `${k} (in review)`);
  }
  for (const [k, files] of running) for (const f of files ?? []) inFlight.set(f, `${k} (in progress)`);
  if ([...running.values()].includes(undefined)) {
    note("alone", "[plan] a running story's files are unknown, so nothing else starts until it finishes");
    return [];
  }

  const stale = unblocked.filter((s) => predicted.get(s.key)?.updated !== s.updated);
  if (stale.length > 0) {
    const fresh = await predictPlans(stale);
    for (const s of stale) {
      const plan = fresh.get(s.key);
      if (plan) predicted.set(s.key, { updated: s.updated, plan });
    }
  }
  const files = new Map(unblocked.flatMap((s) => (predicted.has(s.key) ? [[s.key, predicted.get(s.key)!.plan.files] as const] : [])));
  // Only a fresh story is judged: one the reviewer overruled, already built or offered, or pinned by the operator is built.
  const declined = unblocked.filter((s) => {
    const tracker = trackerFor(s.key);
    return Boolean(predicted.get(s.key)?.plan.wontDo) && !PINNED && !overruled(s, tracker.header)
      && rejectionFeedback(s, tracker.header) === undefined && changedOn(s.key).length === 0;
  });
  for (const s of declined) await decline(s, predicted.get(s.key)!.plan.wontDo!);
  const { picked, deferred } = pickNonOverlapping(unblocked.filter((s) => !declined.includes(s)), files, inFlight);
  for (const d of deferred) note(d.key, `[plan] ${d.key} waits: ${d.reason}`);
  const starting = picked.slice(0, free).map((s) => ({ story: s, mode: buildMode(s.labels, predicted.get(s.key)?.plan) }));
  if (starting.length === 0) return [];
  const described = starting.map(({ story, mode }) => `${story.key} (${mode.bmad ? "BMAD" : "plain"}: ${mode.why})`);
  console.log(`[plan] starting ${described.join(", ")}; main is at ${gitIn(REPO, "rev-parse", "--short", "main")}`);
  if (process.env.FACTORY_PLAN_ONLY) return [];
  return starting.map(({ story: s, mode }) => {
    running.set(s.key, files.get(s.key));
    lastSaid.delete(s.key);
    return processStory(s, mode)
      .catch(() => {
        // processStory has already labelled and commented on the ticket.
      })
      .finally(() => running.delete(s.key));
  });
};

let wake = () => {};
const nap = (seconds: number) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, seconds * 1000);
    wake = () => {
      clearTimeout(timer);
      resolve();
    };
  });

for (const dir of [LOGS, STATE]) fs.mkdirSync(dir, { recursive: true });
if (!fs.existsSync(ENV_FILE)) {
  console.log(`[factory] ${ENV_FILE} is missing: it holds the model credential the gateway injects (see .sandcastle/README.md)`);
  process.exit(1);
}
if (!fs.existsSync(REPO)) gitIn(REPO_ROOT, "clone", "--quiet", REPO_ROOT, REPO);

// One factory process at a time: two would each take the other's running stories for orphans.
const PIDFILE = `${STATE}/factory.pid`;
if (fs.existsSync(PIDFILE)) {
  const other = Number(fs.readFileSync(PIDFILE, "utf8"));
  let alive = false;
  try {
    process.kill(other, 0);
    alive = true;
  } catch {
    // Stale: that process is gone.
  }
  if (alive) {
    console.log(`[factory] another factory process (pid ${other}) is running; stop it first`);
    process.exit(1);
  }
}
fs.writeFileSync(PIDFILE, String(process.pid));
process.on("exit", () => fs.rmSync(PIDFILE, { force: true }));

// Stopping the harness (Ctrl-C, kill, a crash, a reboot) interrupts its stories: Sandcastle removes their containers,
// the branches keep their commits, and they are queued again here so the next round resumes them.
for (const key of process.env.FACTORY_PLAN_ONLY ? [] : await fromAll("interrupted stories", (t) => t.orphaned())) {
  const tracker = trackerFor(key);
  await tracker.requeued(key);
  await tracker.comment(key, `${tracker.header} was interrupted\nThe factory stopped while working on this story. Its branch keeps what was committed, and the next round resumes it.`);
  console.log(`[factory] ${key}: interrupted by the last stop, queued again`);
}

// The factory's own code, as main has it: an idle harness under launchd restarts to load a change.
const factoryCode = () => {
  try {
    return gitIn(CHECKOUT, "rev-parse", "main:.sandcastle");
  } catch {
    return "";
  }
};
const CODE_AT_START = factoryCode();
// Settings load once, at startup, so a change to the file is loaded the same way.
const settingsText = () => (fs.existsSync(SETTINGS_FILE) ? fs.readFileSync(SETTINGS_FILE, "utf8") : "");
const SETTINGS_AT_START = settingsText();
const pendingChange = (): string | undefined => {
  if (factoryCode() !== CODE_AT_START) return "the factory's code changed on main";
  if (settingsText() !== SETTINGS_AT_START) return `${SETTINGS_FILE} changed`;
  return undefined;
};

ensureGradleSeed();
const runs = new Set<Promise<void>>();
let firstRound = true;
console.log(`[factory] ${ONE_ROUND ? "one round" : `watching the active sprint${github ? " and GitHub issues" : ""} every ${POLL_SECONDS}s`}; ${LIMIT} at a time, ${CPUS} CPUs each, ${MODEL}`);
while (true) {
  const change = !ONE_ROUND && running.size === 0 ? pendingChange() : undefined;
  if (change) {
    if (process.env.FACTORY_SUPERVISED) {
      console.log(`[factory] ${change}; exiting so launchd restarts it with the change`);
      process.exit(0);
    }
    note("code", `[factory] ${change}; restart it to load the change`);
  }
  if (!ONE_ROUND || firstRound) {
    firstRound = false;
    let gateway: boolean | string;
    try {
      gateway = gatewayUp(running.size === 0);
    } catch (error) {
      // Docker Desktop can come up after the LaunchAgent does.
      gateway = `Docker is not answering: ${error instanceof Error ? error.message.split("\n")[0] : String(error)}`;
    }
    if (gateway !== true) {
      note("gateway", gateway === false ? "[factory] paused: the gateway is stopped. `docker start jclaw-factory-gateway` resumes." : `[factory] waiting: ${gateway}`);
    } else {
      note("gateway", "[factory] gateway up");
      try {
        const candidates = PINNED
          ? await Promise.all(PINNED.map((k) => trackerFor(k).snapshotToState(k)))
          : await fromAll("afk stories", (t) => t.intake());
        for (const run of await round(candidates)) {
          runs.add(run);
          void run.finally(() => {
            runs.delete(run);
            wake();
          });
        }
        lastSaid.delete("round");
      } catch (error) {
        note("round", `[factory] round failed, retrying next poll: ${errorText(error)}`);
      }
    }
  }
  if (ONE_ROUND && runs.size === 0) break;
  // A finishing story wakes the loop early: its slot is free.
  await nap(POLL_SECONDS);
}
console.log("[factory] stopped");
