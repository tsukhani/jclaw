// node --test bin/policy-guard.test.mjs
// The command layer is driven with a stubbed git state; the ref layer runs the real .githooks/pre-push against local
// bare remotes, so /deploy's and /renovate's pushes are shown passing through git itself.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawnSync } from "node:child_process";
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { REASONS, commandVerdict, hookVerdict, pathVerdict, refsVerdict } from "./policy-guard.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const GUARD = join(ROOT, "bin", "policy-guard.mjs");

const RELEASE = { branch: "main", subject: "Release v0.19.79", mainCheckout: true, onMain: true };
const SHA = "c".repeat(40);
const ctx = (state = RELEASE, pid = "4242") => ({
  cwd: "/repo",
  state: () => state,
  serverPid: () => pid,
  checkoutOf: (abs) => (abs.startsWith("/repo/") ? "/repo" : null),
});
const refused = (rule) => ({ rule, reason: REASONS[rule] });

// The renovate cleanup exactly as .claude/commands/deploy.md step 17 and renovate.md step 12 run it.
const RENOVATE_CLEANUP = `/usr/bin/git fetch -q origin --prune
/usr/bin/git branch -r --list 'origin/renovate/*' | sed 's|^ *origin/||' |
  while read -r b; do /usr/bin/git merge-base --is-ancestor "origin/$b" HEAD && echo "$b"; done |
  xargs -r /usr/bin/git push origin --delete`;

const CASES = [
  // /deploy's own pushes
  ["/usr/bin/git push origin HEAD", null],
  ["/usr/bin/git push github HEAD", null],
  [`/usr/bin/git push origin ${SHA}:refs/heads/main`, null],
  [`/usr/bin/git push github ${SHA}:refs/heads/main`, null],
  ['/usr/bin/git push origin "refs/tags/v0.19.79"', null],
  ['/usr/bin/git push github "refs/tags/v0.19.79"', null],
  [RENOVATE_CLEANUP, null],
  ["git push origin --delete renovate/pnpm-11.x", null],
  // other pushes
  ["git push", "push"],
  ["git push origin main", "push"],
  ["git push upstream HEAD", "push"],
  ["git push --force origin HEAD", "push"],
  ["git push origin HEAD --dry-run", "push"],
  ["git push origin refs/tags/v0.19.79-rc1", "push"],
  ["git push origin --tags", "push"],
  ["git push origin --delete main", "push"],
  ["git push github --delete renovate/pnpm-11.x", "push"],
  ["rtk git push origin main", "push"],
  ["env FOO=1 git push origin main", "push"],
  ['bash -c "git push origin main"', "push"],
  ["sh -lc 'cd /repo && git push'", "push"],
  ['echo "$(git push origin main)"', "push"],
  ["echo `git push`", "push"],
  ["eval git push origin main", "push"],
  ["git status && git push", "push"],
  ["for r in origin github; do git push $r main; done", "push"],
  // text that only mentions a push
  ["echo git push", null],
  ["git log --grep push", null],
  ["grep -n 'git push' .claude/commands/deploy.md", null],
  ["cat <<'EOF'\ngit push --force\nEOF", null],
  // hook skipping
  ["git commit --no-verify -m x", "noVerify"],
  ["git commit -nm x", "noVerify"],
  ["git commit -an -m x", "noVerify"],
  ["git commit -m -n", null],
  ["git commit -mn", null],
  ['git commit -m "never use --no-verify"', null],
  ["git commit -F - <<'EOF'\nfix: never --no-verify\nEOF", null],
  ["git commit -F - <<'EOF'\nmsg\nEOF\ngit push", "push"],
  ["git push origin HEAD --no-verify", "noVerify"],
  ["git merge --no-verify feature", "noVerify"],
  ["git merge -n feature", null],
  ["git rebase --no-verify main", "noVerify"],
  ["git -c core.hooksPath=/dev/null commit -m x", "hooksPath"],
  ["GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.hooksPath GIT_CONFIG_VALUE_0=/dev/null git commit -m x", "hooksPath"],
  ["git config core.hooksPath .githooks", null],
  ["git config core.hooksPath", null],
  ["git config --get core.hooksPath", null],
  ["git config core.hooksPath /dev/null", "hooksPath"],
  ["git config --unset core.hooksPath", "hooksPath"],
  ["JCLAW_SKIP_TESTS=1 git push origin HEAD", "skipTests"],
  ["export JCLAW_SKIP_TESTS=1", "skipTests"],
  ["grep JCLAW_SKIP_TESTS .githooks/pre-push", null],
  // stash
  ["git stash", null],
  ["git stash pop", null],
  ["git stash show -u", null],
  ['git stash push -m "-u"', null],
  ["git stash -u", "stash"],
  ["git stash -ku", "stash"],
  ["git stash -a", "stash"],
  ["git stash push --include-untracked -m wip", "stash"],
  ["git stash save -u wip", "stash"],
  // stopping the instance
  ["./jclaw.sh status", null],
  ["./jclaw.sh test", null],
  ["./jclaw.sh backup", null],
  ["cat jclaw.sh | grep restart", null],
  ["./jclaw.sh restart", "stop"],
  ["./jclaw.sh --dev restart", "stop"],
  ["bash jclaw.sh stop", "stop"],
  ["/Users/someone/jclaw/jclaw.sh compact", "stop"],
  ["JCLAW_OPERATOR_APPROVED=1 ./jclaw.sh restart", null],
  ["env JCLAW_OPERATOR_APPROVED=1 ./jclaw.sh restart", null],
  ["JCLAW_OPERATOR_APPROVED=0 ./jclaw.sh restart", "stop"],
  ["kill 4242", "stop"],
  ["kill -9 4242", "stop"],
  ["kill $(cat server.pid)", "stop"],
  ["lsof -ti :9000 | xargs kill", "stop"],
  ["kill 5555", null],
  ["kill -0 4242", null],
  ["JCLAW_OPERATOR_APPROVED=1 kill 4242", null],
];

for (const [command, rule] of CASES) {
  test(`${rule ?? "allowed"}: ${command.replace(/\n/g, "⏎")}`, () => {
    deepStrictEqual(commandVerdict(command, ctx()), rule ? refused(rule) : null);
  });
}

test("a push of HEAD needs the exact release subject", () => {
  for (const subject of ["Release v0.19.79 hotfix", "release v0.19.79", "Release 0.19.79", "fix(deploy): x"]) {
    deepStrictEqual(commandVerdict("git push origin HEAD", ctx({ ...RELEASE, subject })), refused("push"), subject);
  }
});

test("a release commit pushed by name must be a release, on main, named in full", () => {
  const command = `git push github ${SHA}:refs/heads/main`;
  deepStrictEqual(commandVerdict(command, ctx({ ...RELEASE, subject: "feat: a peer's work" })), refused("push"));
  deepStrictEqual(commandVerdict(command, ctx({ ...RELEASE, onMain: false })), refused("push"));
  deepStrictEqual(commandVerdict(command, ctx({ ...RELEASE, branch: "release" })), refused("push"));
  deepStrictEqual(commandVerdict(command, ctx({ ...RELEASE, mainCheckout: false })), refused("push"));
  deepStrictEqual(commandVerdict("git push github cccccccc:refs/heads/main", ctx()), refused("push"));
  deepStrictEqual(commandVerdict(`git push github ${SHA}:refs/heads/other`, ctx()), refused("push"));
  deepStrictEqual(commandVerdict(`git push --force github ${SHA}:refs/heads/main`, ctx()), refused("push"));
});

test("the named commit is the one judged, not HEAD", () => {
  const seen = [];
  const c = { ...ctx(), state: (dir, rev) => { seen.push(rev); return RELEASE; } };
  commandVerdict(`git push origin ${SHA}:refs/heads/main`, c);
  commandVerdict("git push origin HEAD", c);
  deepStrictEqual(seen, [SHA, "HEAD"]);
});

test("a release commit is pushed only from main in the main checkout", () => {
  deepStrictEqual(commandVerdict("git push origin HEAD", ctx({ ...RELEASE, branch: "release" })), refused("push"));
  deepStrictEqual(commandVerdict("git push origin HEAD", ctx({ ...RELEASE, mainCheckout: false })), refused("push"));
});

test("the push is judged in the directory git -C or an earlier cd names", () => {
  const seen = [];
  const c = { ...ctx(), state: (dir) => { seen.push(dir); return RELEASE; } };
  commandVerdict("git -C /elsewhere push origin HEAD", c);
  commandVerdict("cd /other && git push origin HEAD", c);
  commandVerdict("cd sub; git -C deeper push origin HEAD", c);
  deepStrictEqual(seen, ["/elsewhere", "/other", "/repo/sub/deeper"]);
});

test("with no server.pid, only the pid file or the port names the server", () => {
  deepStrictEqual(commandVerdict("kill 4242", ctx(RELEASE, "")), null);
  deepStrictEqual(commandVerdict("kill $(lsof -ti :9443)", ctx(RELEASE, "")), refused("stop"));
});

test("editing an upstream-imported skill is refused, a local skill is not", () => {
  deepStrictEqual(pathVerdict("/repo/skills/humanizer/SKILL.md", ctx()),
    { rule: "skills", reason: REASONS.skills("humanizer") });
  deepStrictEqual(pathVerdict("skills/git/references/x.md", ctx()), { rule: "skills", reason: REASONS.skills("git") });
  deepStrictEqual(pathVerdict("/repo/skills/web-research/SKILL.md", ctx()), null);
  deepStrictEqual(pathVerdict("/repo/skillsx/git/SKILL.md", ctx()), null);
  deepStrictEqual(pathVerdict("/elsewhere/skills/humanizer/SKILL.md", ctx()), null);
});

test("hookVerdict reads Bash commands and the edit tools' paths, and nothing else", () => {
  const c = ctx();
  deepStrictEqual(hookVerdict({ tool_name: "Bash", tool_input: { command: "git push" } }, c), refused("push"));
  deepStrictEqual(hookVerdict({ tool_name: "Write", tool_input: { file_path: "/repo/skills/git/a.md" } }, c),
    { rule: "skills", reason: REASONS.skills("git") });
  deepStrictEqual(hookVerdict({ tool_name: "NotebookEdit", tool_input: { notebook_path: "/repo/skills/git/n.ipynb" } }, c),
    { rule: "skills", reason: REASONS.skills("git") });
  deepStrictEqual(hookVerdict({ tool_name: "Read", tool_input: { file_path: "/repo/skills/git/a.md" } }, c), null);
});

test("the hook prints Claude Code's deny decision with the rule's reason, and nothing for an allowed call", () => {
  const run = (event) => spawnSync("node", [GUARD, "hook"], { input: JSON.stringify(event), encoding: "utf8" });
  const denied = run({ tool_name: "Bash", tool_input: { command: "git push --force origin main" }, cwd: ROOT });
  strictEqual(denied.status, 0);
  strictEqual(denied.stdout, `${JSON.stringify({ hookSpecificOutput: { hookEventName: "PreToolUse",
    permissionDecision: "deny", permissionDecisionReason: "Pushing is /deploy's job (AGENTS.md Policy): stop at the "
    + "local commit and report its hash. The guard passes only a \"Release vX.Y.Z\" commit on main pushed to origin or "
    + "github as HEAD or by its full sha, a version tag pushed alone, and /renovate's deletion of renovate/* branches."
    } })}\n`);
  const allowed = run({ tool_name: "Bash", tool_input: { command: "git status" }, cwd: ROOT });
  strictEqual(allowed.status, 0);
  strictEqual(allowed.stdout, "");
});

test("a hook input the guard cannot read is refused only when it mentions what the guard protects", () => {
  const run = (input) => spawnSync("node", [GUARD, "hook"], { input, encoding: "utf8" });
  strictEqual(JSON.parse(run("{not json git push").stdout).hookSpecificOutput.permissionDecision, "deny");
  strictEqual(run("{not json ls").stdout, "");
});

test("refsVerdict passes /deploy's and /renovate's refs and refuses every other", () => {
  const zero = "0".repeat(40);
  const sha = "a".repeat(40);
  const release = () => ({ branch: "main", subject: "Release v0.19.79", mainCheckout: true, onMain: true });
  const line = (l, r, s = sha) => `${l} ${s} ${r} ${zero}\n`;
  deepStrictEqual(refsVerdict(line("HEAD", "refs/heads/main"), "origin", release), null);
  deepStrictEqual(refsVerdict(line(sha, "refs/heads/main"), "github", release), null);
  deepStrictEqual(refsVerdict(line("refs/heads/main", "refs/heads/main"), "origin", release), null);
  deepStrictEqual(refsVerdict(line("refs/tags/v0.19.79", "refs/tags/v0.19.79"), "github", release), null);
  deepStrictEqual(refsVerdict(line("(delete)", "refs/heads/renovate/x", zero), "origin", release), null);
  deepStrictEqual(refsVerdict("", "origin", release), null);
  const no = (ref) => ({ ...refused("push"), ref });
  deepStrictEqual(refsVerdict(line("HEAD", "refs/heads/main"), "origin",
    () => ({ branch: "main", subject: "feat: x", mainCheckout: true })), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line("HEAD", "refs/heads/main"), "origin",
    () => ({ branch: "side", subject: "Release v0.19.79", mainCheckout: true })), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line("refs/heads/main", "refs/heads/main"), "origin",
    () => ({ branch: "main", subject: "Release v0.19.79", mainCheckout: false })), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line("refs/heads/main", "refs/heads/main"), "fork", release), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line("refs/heads/feature", "refs/heads/main"), "origin", release), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line(sha, "refs/heads/main"), "github",
    () => ({ ...release(), onMain: false })), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line("(delete)", "refs/heads/main", zero), "origin", release), no("refs/heads/main"));
  deepStrictEqual(refsVerdict(line("(delete)", "refs/heads/renovate/x", zero), "github", release),
    no("refs/heads/renovate/x"));
  deepStrictEqual(refsVerdict(line("refs/tags/v1.0.0-rc1", "refs/tags/v1.0.0-rc1"), "origin", release),
    no("refs/tags/v1.0.0-rc1"));
});

const inScratchRepo = (body) => {
  const scratch = mkdtempSync(join(tmpdir(), "policy-guard-"));
  try {
    const env = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1", JCLAW_SKIP_TESTS: "1" };
    const git = (cwd, ...args) => spawnSync("git", args, { cwd, env, encoding: "utf8" });
    const work = join(scratch, "work");
    for (const bare of ["origin.git", "github.git"]) git(scratch, "init", "-q", "--bare", "-b", "main", bare);
    git(scratch, "init", "-q", "-b", "main", work);
    for (const [k, v] of [["user.name", "t"], ["user.email", "t@example.invalid"], ["commit.gpgsign", "false"],
      ["tag.gpgsign", "false"], ["core.hooksPath", ".githooks"]]) git(work, "config", k, v);
    mkdirSync(join(work, ".githooks"));
    mkdirSync(join(work, "bin"));
    copyFileSync(join(ROOT, ".githooks", "pre-push"), join(work, ".githooks", "pre-push"));
    chmodSync(join(work, ".githooks", "pre-push"), 0o755);
    copyFileSync(GUARD, join(work, "bin", "policy-guard.mjs"));
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", "feat: work");
    git(work, "remote", "add", "origin", join(scratch, "origin.git"));
    git(work, "remote", "add", "github", join(scratch, "github.git"));
    body({ scratch, work, git, env });
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
};

test("through git itself, the pre-push hook lets /deploy's pushes out and refuses the rest", () => {
  inScratchRepo(({ scratch, work, git }) => {
    const notRelease = git(work, "push", "origin", "HEAD");
    strictEqual(notRelease.status, 1);
    strictEqual(notRelease.stderr.split("\n")[0], `[jclaw pre-push] Refused refs/heads/main: ${REASONS.push}`);

    git(work, "commit", "-q", "--allow-empty", "-m", "Release v0.0.1");
    strictEqual(git(work, "push", "origin", "HEAD").status, 0);
    strictEqual(git(work, "push", "github", "HEAD").status, 0);
    strictEqual(git(scratch, "--git-dir=origin.git", "log", "-1", "--format=%s", "main").stdout.trim(), "Release v0.0.1");

    git(work, "tag", "-a", "v0.0.1", "-m", "Release v0.0.1");
    strictEqual(git(work, "push", "origin", "refs/tags/v0.0.1").status, 0);
    strictEqual(git(work, "push", "github", "refs/tags/v0.0.1").status, 0);

    for (const b of ["renovate/x", "feature"]) git(scratch, "--git-dir=origin.git", "branch", b, "main");
    strictEqual(git(work, "push", "origin", "--delete", "renovate/x").status, 0);
    strictEqual(git(work, "push", "origin", "--delete", "feature").status, 1);
    strictEqual(git(work, "push", "origin", "HEAD:refs/heads/other").status, 1);

    git(work, "commit", "-q", "--allow-empty", "-m", "Release v0.0.2");
    git(work, "worktree", "add", "-q", "-b", "side", join(scratch, "wt"));
    strictEqual(git(join(scratch, "wt"), "push", "origin", "main").status, 1);
    strictEqual(git(work, "push", "origin", "HEAD").status, 0);
  });
});

test("a commit landing between /deploy's two pushes neither blocks the release nor goes out with it", () => {
  inScratchRepo(({ scratch, work, git, env }) => {
    const tip = (remote) => git(scratch, `--git-dir=${remote}.git`, "rev-parse", "main").stdout.trim();
    git(work, "commit", "-q", "--allow-empty", "-m", "Release v0.0.1");
    const release = git(work, "rev-parse", "HEAD").stdout.trim();
    strictEqual(git(work, "push", "origin", `${release}:refs/heads/main`).status, 0);

    git(work, "commit", "-q", "--allow-empty", "-m", "feat: a peer session's work");

    strictEqual(git(work, "push", "github", "HEAD").status, 1);
    strictEqual(git(work, "push", "github", `${release}:refs/heads/main`).status, 0);
    strictEqual(tip("origin"), release);
    strictEqual(tip("github"), release);

    // The second push pays nothing for the first's tests, though HEAD has moved past what they tested.
    const gitDir = git(work, "rev-parse", "--absolute-git-dir").stdout.trim();
    writeFileSync(join(gitDir, "jclaw-last-tested-sha"), `${release}\n`);
    const { JCLAW_SKIP_TESTS: _, ...tested } = env;
    git(scratch, "--git-dir=github.git", "update-ref", "-d", "refs/heads/main");
    const again = spawnSync("git", ["push", "github", `${release}:refs/heads/main`], { cwd: work, env: tested, encoding: "utf8" });
    strictEqual(again.status, 0, again.stderr);
    strictEqual(`${again.stdout}${again.stderr}`.includes(`${release} already tested`), true, again.stdout);

    git(work, "checkout", "-q", "-b", "side");
    git(work, "commit", "-q", "--allow-empty", "-m", "Release v0.0.9");
    const offMain = git(work, "rev-parse", "HEAD").stdout.trim();
    git(work, "checkout", "-q", "main");
    strictEqual(git(work, "push", "origin", `${offMain}:refs/heads/main`).status, 1);
  });
});
