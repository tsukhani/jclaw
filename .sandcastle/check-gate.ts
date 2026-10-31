// The first step of every gate: `./jclaw.sh check --backend-only`. A compile error already skips the test run inside
// diagnostics, so what this adds is the wildcard-import and import-order checks, which would otherwise fail only at the
// pre-push of a release. The frontend half stays with the frontend gate.
// It takes the sandbox's exec and the gate's switch to main, so the offline checks drive it with a scripted shell.

import type { Diagnostic, Exec, OnMain } from "./frontend-gate.ts";

export type CheckGate = { fresh: Diagnostic[]; environment: string[]; summary: string };

const DEADLINE_SECONDS = 600;
const RUN = `timeout ${DEADLINE_SECONDS} ./jclaw.sh check --backend-only > /tmp/check.log 2>&1`;

export const checkGate = async (exec: Exec, onMain: OnMain): Promise<CheckGate> => {
  // A branch cut before the command existed would print the usage and exit 1, which says nothing about the branch.
  if ((await exec("grep -q '^do_check()' jclaw.sh")).exitCode !== 0) {
    return { fresh: [], environment: [], summary: "check not run: this branch predates it" };
  }
  if ((await exec(RUN)).exitCode === 0) return { fresh: [], environment: [], summary: "check passed" };
  const output = (await exec("tail -40 /tmp/check.log")).stdout;
  if (await onMain(async () => (await exec(RUN)).exitCode !== 0)) {
    return { fresh: [], environment: ["jclaw.sh check"], summary: "check also fails on main, so it is not counted" };
  }
  return {
    fresh: [{ kind: "check", file: null, line: null, message: `./jclaw.sh check --backend-only failed:\n${output}` }],
    environment: [],
    summary: "check failed, so the tests did not run",
  };
};

// The tests run only once the check has nothing new to report: the repair round fixes the check first.
export const checkFirst = async (
  check: () => Promise<CheckGate>,
  record: (result: CheckGate) => void,
  tests: () => Promise<Diagnostic[]>,
): Promise<Diagnostic[]> => {
  const result = await check();
  record(result);
  return result.fresh.length > 0 ? result.fresh : tests();
};
