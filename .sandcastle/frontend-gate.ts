// The frontend half of the gate, for a branch that changes frontend/: the checks `./jclaw.sh test` runs before a push.
// It takes the sandbox's exec and the gate's switch to main, so the offline checks drive it with a scripted shell.

export type Diagnostic = { kind: string; file: string | null; line: number | null; message: string };
export type Exec = (command: string) => Promise<{ stdout: string; exitCode: number }>;
export type OnMain = <T>(body: () => Promise<T>) => Promise<T>;
export type FrontendGate = { fresh: Diagnostic[]; environment: string[]; flakes: string[]; summary: string };

const DEADLINE_SECONDS = 900;
const IN = "cd frontend &&";
// Prepared as `./jclaw.sh test` does: a stale .nuxt or vite cache has passed a broken page before (JCLAW-680).
const PREPARE = `${IN} pnpm install --frozen-lockfile > /tmp/fe-prepare.log 2>&1 && rm -rf .nuxt node_modules/.vite && pnpm exec nuxi prepare >> /tmp/fe-prepare.log 2>&1`;
export const STATIC_CHECKS = ["stylelint", "lint", "typecheck"];
const NOT_RUN = "frontend checks not run: the branch changes nothing under frontend/";

type Failed = { file: string; message: string };

// The test files a vitest JSON report marks failed, relative to frontend/. undefined when there is no readable report:
// vitest crashed, or was killed, before it wrote one.
export const vitestFailures = (raw: string): Failed[] | undefined => {
  let report: { testResults?: { name: string; status: string; message?: string; assertionResults?: { status: string; fullName: string; failureMessages?: string[] }[] }[] };
  try {
    report = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (!Array.isArray(report?.testResults)) return undefined;
  return report.testResults
    .filter((t) => t.status === "failed")
    .map((t) => {
      const first = t.assertionResults?.find((a) => a.status === "failed");
      // A file that fails to import has no assertions; its reason is the file's own message.
      const why = first ? `${first.fullName}: ${(first.failureMessages?.[0] ?? "").split("\n")[0]}` : (t.message ?? "").split("\n")[0];
      return { file: t.name.slice(t.name.indexOf("/frontend/") + "/frontend/".length), message: why };
    });
};

// Failures that are new relative to main, judged as the backend's are: a test file that passes alone was a flake, and
// a file or check that also fails on main is the environment. Throws when the dependencies install on neither, or the
// tests run on neither: a suite nothing could run has verified nothing.
export const frontendGate = async (exec: Exec, onMain: OnMain): Promise<FrontendGate> => {
  if (!(await exec("git diff --name-only main...HEAD -- frontend | head -1")).stdout.trim()) return { fresh: [], environment: [], flakes: [], summary: NOT_RUN };

  // Without the colour codes: the tail goes into a repair prompt and a ticket comment.
  const tail = async (log: string) => (await exec(`tail -40 ${log}`)).stdout.replace(/\x1b\[[0-9;]*m/g, "").trim();
  const quote = (file: string) => `'${file.replaceAll("'", `'\\''`)}'`;
  const prepare = async () => (await exec(PREPARE)).exitCode === 0;
  // All test files when none are named. undefined: green. Not `pnpm test`: pnpm takes a leading --reporter as its own.
  const vitest = async (files: string[], tag: string): Promise<{ failed: Failed[] } | { crashed: string } | undefined> => {
    const out = `/tmp/fe-vitest-${tag}.json`;
    const log = `/tmp/fe-vitest-${tag}.log`;
    const run = await exec(`${IN} rm -f ${out}; timeout ${DEADLINE_SECONDS} pnpm exec vitest run ${files.map(quote).join(" ")} --reporter=json --outputFile=${out} > ${log} 2>&1`);
    if (run.exitCode === 0) return undefined;
    const failed = vitestFailures((await exec(`cat ${out} 2>/dev/null`)).stdout);
    return failed?.length ? { failed } : { crashed: await tail(log) };
  };
  // The tail of its output when red, undefined when green.
  const staticCheck = async (name: string, tag: string) => {
    const log = `/tmp/fe-${name}-${tag}.log`;
    return (await exec(`${IN} timeout ${DEADLINE_SECONDS} pnpm ${name} > ${log} 2>&1`)).exitCode === 0 ? undefined : tail(log);
  };
  const of = (message: string, file: string | null = null): Diagnostic => ({ kind: "frontend", file, line: null, message });

  if (!(await prepare())) {
    const why = await tail("/tmp/fe-prepare.log");
    if (!(await onMain(prepare))) throw new Error(`frontend dependencies do not install in the sandbox, on main either:\n${why}`);
    return { fresh: [of(`pnpm install or nuxi prepare fails on this branch and works on main:\n${why}`, "frontend/package.json")], environment: [], flakes: [], summary: "frontend: the dependencies did not install" };
  }

  const flakes: string[] = [];
  const suspects: Failed[] = [];
  let crashed: string | undefined;
  const all = await vitest([], "all");
  if (all && "failed" in all) {
    const alone = await vitest(all.failed.map((f) => f.file), "alone");
    // A re-run that left no report clears nothing, as with the backend's reports.
    const again = alone === undefined ? [] : "failed" in alone ? alone.failed.map((f) => f.file) : all.failed.map((f) => f.file);
    for (const f of all.failed) {
      if (again.includes(f.file)) suspects.push(f);
      else flakes.push(`pnpm test ${f.file}: ${f.message}`);
    }
  } else if (all) {
    crashed = all.crashed;
  }
  const red: { name: string; output: string }[] = [];
  for (const name of STATIC_CHECKS) {
    const output = await staticCheck(name, "branch");
    if (output !== undefined) red.push({ name, output });
  }

  const fresh: Diagnostic[] = [];
  const environment: string[] = [];
  if (suspects.length > 0 || crashed !== undefined || red.length > 0) {
    const onMainToo = await onMain(async () => {
      if (!(await prepare())) throw new Error(`frontend dependencies do not install on main in the sandbox:\n${await tail("/tmp/fe-prepare.log")}`);
      const there = new Set<string>();
      // A test file the branch added is not on main, where vitest fails on a file it cannot find.
      const existing: string[] = [];
      for (const s of suspects) if ((await exec(`test -f ${quote(`frontend/${s.file}`)}`)).exitCode === 0) existing.push(s.file);
      const rerun = existing.length > 0 ? await vitest(existing, "main") : undefined;
      for (const file of rerun === undefined ? [] : "failed" in rerun ? rerun.failed.map((f) => f.file) : existing) there.add(file);
      // Only a crash on main means the sandbox cannot run them: main with a failing test still wrote its report.
      if (crashed !== undefined) {
        const whole = await vitest([], "main-all");
        if (whole && "crashed" in whole) throw new Error(`the frontend tests do not run in the sandbox, on main either:\n${whole.crashed}`);
      }
      for (const check of red) if ((await staticCheck(check.name, "main")) !== undefined) there.add(`pnpm ${check.name}`);
      return there;
    });
    // The phases that follow run on the branch, whose dependencies the baseline just replaced with main's.
    if (!(await prepare())) throw new Error("the branch's frontend dependencies did not install again after the baseline on main");
    for (const s of suspects) {
      if (onMainToo.has(s.file)) environment.push(`frontend/${s.file}`);
      else fresh.push(of(`pnpm test: ${s.file}: ${s.message}`, `frontend/${s.file}`));
    }
    if (crashed !== undefined) fresh.push(of(`pnpm test failed without a report on this branch and runs on main:\n${crashed}`));
    for (const check of red) {
      if (onMainToo.has(`pnpm ${check.name}`)) environment.push(`pnpm ${check.name}`);
      else fresh.push(of(`pnpm ${check.name} fails on this branch and passes on main:\n${check.output}`));
    }
  }
  const summary = fresh.length === 0 ? "frontend tests, stylelint, lint and typecheck: no new failures" : `frontend: ${fresh.length} new failure${fresh.length === 1 ? "" : "s"}`;
  return { fresh, environment, flakes, summary };
};
