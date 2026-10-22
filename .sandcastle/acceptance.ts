// Acceptance tests first (JF-17): for a story with acceptance criteria the factory commits one failing test per
// criterion and stops for the operator to approve them before any code is written, because the agent that reads a
// criterion would otherwise also write the test that judges its reading.
import { autoMerges } from "./board.ts";
import type { Snapshot } from "./tracker.ts";

export const APPROVED = "afk-tests-approved";

// The factory's comment offering a story's tests. It never starts with the implementation offer, `<header>: ready for review`.
export const testsOffer = (header: string) => `${header}: acceptance tests ready for review`;

export type TestsStage =
  | { stage: "skip" }
  | { stage: "write" }
  | { stage: "rewrite"; feedback: string }
  | { stage: "approved" };

// An afk-merge story builds and merges unattended, so it never stops for review.
export const testsStage = (s: Snapshot, header: string): TestsStage => {
  if (!/acceptance criteria/i.test(s.description) || autoMerges(s)) return { stage: "skip" };
  if (s.labels.includes(APPROVED)) return { stage: "approved" };
  let offer = -1;
  s.comments.forEach((c, i) => {
    if (c.body.startsWith(testsOffer(header))) offer = i;
  });
  if (offer < 0) return { stage: "write" };
  const feedback = s.comments
    .slice(offer + 1)
    .filter((c) => !c.body.startsWith(header))
    .map((c) => `${c.author}: ${c.body}`)
    .join("\n\n");
  return { stage: "rewrite", feedback };
};

export const testsOffered = (s: Snapshot, header: string): boolean => s.comments.some((c) => c.body.startsWith(testsOffer(header)));

export const awaitingTestsApproval = (s: Snapshot, header: string): boolean =>
  s.comments
    .filter((c) => c.body.startsWith(testsOffer(header)) || c.body.startsWith(`${header}: ready for review`))
    .at(-1)?.body.startsWith(testsOffer(header)) ?? false;

export const isTestPath = (path: string) =>
  /^(test|frontend\/test|frontend\/tests)\//.test(path) || /\.(test|spec)\.ts$/.test(path);

// Judged by path, not by commit: the operator may have edited the tests before approving.
export const onlyTests = (changed: string[]) => changed.length > 0 && changed.every(isTestPath);

export const approvedTestsNote = (stage: TestsStage): string =>
  stage.stage === "approved"
    ? "\n### Approved acceptance tests\nThe operator approved the acceptance tests committed first on this branch as the ticket's meaning. Make them pass; never weaken, delete or rewrite them. If one cannot pass as written, leave it failing and say why in the commit body."
    : "";
