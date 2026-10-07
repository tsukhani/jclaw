// Auto-merge for stories labelled `afk-merge`: whether a branch in review may land unattended, and landing it.
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { execFileSync } from "node:child_process";

// Not merged. A permanent refusal waits for a human; anything else is retried on the next poll.
export class MergeRefused extends Error {
  constructor(message: string, readonly permanent: boolean) {
    super(message);
  }
}

export type Report = {
  brief?: { acceptanceCriteria: { criterion: string; met: boolean }[] };
  // The branch tip the factory offered; reports written before auto-merge existed have none.
  head?: string;
};

// The checks that need no git: the factory's own report, and the files a human must read before they run on the Mac.
export const mergeVerdict = (report: Report | undefined, sensitive: string[]): string | undefined => {
  if (!report) return "this harness has no report for it, so another developer's factory built it";
  if (!report.brief) return "the agent's brief did not validate, so its acceptance criteria are unconfirmed";
  const unmet = report.brief.acceptanceCriteria.filter((c) => !c.met);
  if (unmet.length > 0) return `acceptance criteria not met: ${unmet.map((c) => c.criterion).join("; ")}`;
  if (sensitive.length > 0) return `it changes files that run on your Mac once merged, which a human must read first: ${sensitive.join(", ")}`;
  return undefined;
};

const git = (repo: string, ...args: string[]) =>
  execFileSync("/usr/bin/git", ["-C", repo, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
const gitOrUndefined = (repo: string, ...args: string[]) => {
  try {
    return git(repo, ...args);
  } catch {
    return undefined;
  }
};
const stderrOf = (error: unknown) => {
  const e = error as { stderr?: string; message?: string };
  return (e.stderr || e.message || String(error)).trim().split("\n").slice(0, 4).join(" ");
};

// The checkout is mid-merge, mid-rebase or mid-cherry-pick, so its refs are not the operator's settled state.
const busy = (checkout: string) =>
  ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"].find((p) =>
    fs.existsSync(path.resolve(checkout, git(checkout, "rev-parse", "--git-path", p))),
  );

// Nothing commits to the clone's main, so it follows the checkout's through an amend or rebase rather than refusing.
export const followMain = (clone: string) => {
  git(clone, "fetch", "--quiet", "origin", "main");
  git(clone, "reset", "--keep", "--quiet", "origin/main");
};

export type Landed = { merge: string; regated: boolean };

// The merge commit an earlier landing of `key` left on the checkout's main, if one did.
export const landedAs = (checkout: string, key: string): string | undefined =>
  gitOrUndefined(checkout, "log", "-1", "--format=%H", `--grep=^Merge branch 'agent/${key}'$`, "refs/heads/main") || undefined;

// Rebases the story's branch onto the checkout's main with every commit re-signed, runs `regate` on the result when main
// has moved since the branch was built, and merges it with a signed merge commit, all in a throwaway worktree of the
// clone. The checkout only ever fast-forwards: its main moves to the merge commit, and git refuses rather than overwrite
// a local change. Nothing is pushed.
export const landBranch = async (opts: {
  checkout: string;
  clone: string;
  key: string;
  head: string | undefined;
  messages: string[];
  // The full suite on the named branch of the clone: undefined when green, else why it is red.
  regate: (branch: string) => Promise<string | undefined>;
}): Promise<Landed> => {
  const { checkout, clone, key, head } = opts;
  const branch = `agent/${key}`;
  const land = `factory/land-${key}`;
  const landed = `refs/factory/landed/${key}`;

  const built = gitOrUndefined(clone, "rev-parse", "--verify", "--quiet", `refs/heads/${branch}`);
  if (!built) throw new MergeRefused(`${branch} is not in the factory's clone`, true);
  if (head && built !== head) throw new MergeRefused(`${branch} has moved since the factory offered it for review; merge it by hand`, true);
  const offered = gitOrUndefined(checkout, "rev-parse", "--verify", "--quiet", `refs/heads/${branch}`);
  if (offered && offered !== built) throw new MergeRefused(`${branch} in your checkout differs from what the factory built; merge it by hand`, true);

  git(clone, "fetch", "--quiet", "origin", "main");
  const base = git(clone, "rev-parse", "origin/main");
  const regated = git(clone, "merge-base", base, built) !== base;

  // GitHub verifies a signature only when the committer is the key's own account, never the clone's factory identity.
  const operator = ["user.name", "user.email"].flatMap((k) => ["-c", `${k}=${git(checkout, "config", k)}`]);
  const worktree = fs.mkdtempSync(path.join(os.tmpdir(), `jclaw-land-${key}-`));
  try {
    git(clone, "worktree", "add", "--quiet", "-B", land, worktree, built);
    try {
      git(worktree, ...operator, "rebase", "--quiet", "--force-rebase", "--gpg-sign", base);
    } catch (error) {
      const conflicted = gitOrUndefined(worktree, "diff", "--name-only", "--diff-filter=U")?.split("\n").filter(Boolean) ?? [];
      gitOrUndefined(worktree, "rebase", "--abort");
      throw new MergeRefused(
        conflicted.length > 0 ? `${branch} conflicts with main in ${conflicted.join(", ")}` : `rebasing ${branch} onto main failed: ${stderrOf(error)}`,
        true,
      );
    }
    if (regated) {
      // The sandbox takes the branch into a worktree of its own, so this one lets go of it first.
      git(worktree, "checkout", "--quiet", "--detach");
      const red = await opts.regate(land);
      if (red) throw new MergeRefused(`main had moved since the branch was built, and the suite is red on the rebased branch: ${red}`, true);
    }
    git(worktree, "checkout", "--quiet", "--detach", base);
    git(worktree, ...operator, "merge", "--quiet", "--no-ff", "--gpg-sign", "-m", `Merge branch '${branch}'`, ...opts.messages.flatMap((m) => ["-m", m]), land);
    const merge = git(worktree, "rev-parse", "HEAD");

    const operation = busy(checkout);
    if (operation) throw new MergeRefused(`your checkout has a ${operation} in progress`, false);
    git(clone, "update-ref", landed, merge);
    git(checkout, "fetch", "--quiet", clone, landed);
    const holder = git(checkout, "worktree", "list", "--porcelain")
      .split("\n\n")
      .find((block) => block.split("\n").includes("branch refs/heads/main"));
    const holderPath = holder?.split("\n")[0].replace(/^worktree /, "");
    if (holderPath && fs.realpathSync(holderPath) !== fs.realpathSync(checkout)) {
      throw new MergeRefused(`main is checked out in another worktree (${holderPath})`, false);
    }
    if (git(checkout, "rev-parse", "refs/heads/main") !== base) throw new MergeRefused("your checkout's main moved while the branch was landing", false);
    try {
      if (holderPath) git(checkout, "merge", "--ff-only", "--quiet", merge);
      else git(checkout, "update-ref", "refs/heads/main", merge, base);
    } catch (error) {
      // main moved since the fetch, or a local change sits on a file the merge touches: try again next poll.
      throw new MergeRefused(`your checkout's main could not fast-forward: ${stderrOf(error)}`, false);
    }
    for (const repo of [checkout, clone]) gitOrUndefined(repo, "branch", "-D", branch);
    return { merge, regated };
  } finally {
    gitOrUndefined(clone, "worktree", "remove", "--force", worktree);
    fs.rmSync(worktree, { recursive: true, force: true });
    gitOrUndefined(clone, "worktree", "prune");
    gitOrUndefined(clone, "branch", "-D", land);
    gitOrUndefined(clone, "update-ref", "-d", landed);
  }
};
