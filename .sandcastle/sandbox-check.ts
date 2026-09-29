// Live check of the sandbox lockdown: resource limits, which parts of the clone's .git a sandbox can write, that a
// commit and the gate's switch to main still work, and that git on the Mac runs no planted hook. No model call.
// `npx tsx sandbox-check.ts`
import { execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import * as sandcastle from "@ai-hero/sandcastle";
import { ensureGateway, ensureGradleSeed, factorySandbox, planHooks } from "./factory.ts";
import { CLONE } from "./paths.ts";

const BRANCH = "agent/sandbox-check";
const git = (cwd: string, args: string[], env: NodeJS.ProcessEnv = process.env) =>
  execFileSync("/usr/bin/git", ["-C", cwd, ...args], { encoding: "utf8", env, stdio: ["ignore", "pipe", "pipe"] }).trim();

ensureGateway();
ensureGradleSeed();
const before = git(CLONE, ["rev-parse", "main"]);
{
  await using sandbox = await sandcastle.createSandbox({ cwd: CLONE, branch: BRANCH, sandbox: factorySandbox(), hooks: planHooks });
  const r = await sandbox.exec([
    "echo \"memory.max=$(cat /sys/fs/cgroup/memory.max) pids.max=$(cat /sys/fs/cgroup/pids.max)\"",
    "gc=$(git rev-parse --path-format=absolute --git-common-dir)",
    "for t in hooks/post-checkout config info/attributes refs/heads/main packed-refs commondir worktrees/other/commondir; do " +
      "(mkdir -p \"$(dirname \"$gc/$t\")\" && echo x >> \"$gc/$t\") 2>/dev/null && echo \"WRITABLE $t\" || echo \"refused  $t\"; done",
    "git branch -f main HEAD 2>/dev/null && echo 'WRITABLE moving main' || echo 'refused  moving main'",
    "echo probe > sandbox-check.txt && git add sandbox-check.txt && git -c user.name=check -c user.email=check@example.invalid commit -qm 'sandbox check' 2>/tmp/commit.err && echo 'commit   ok' || echo 'commit   FAILED'",
    "sed 's/^/         commit stderr: /' /tmp/commit.err",
    "git switch -q --detach main 2>/dev/null && git switch -q " + BRANCH + " 2>/dev/null && echo 'switch   main and back ok' || echo 'switch   FAILED'",
  ].join("; "));
  console.log(r.stdout.trim());
}
console.log(`host: ${BRANCH} has the sandbox commit: ${git(CLONE, ["log", "-1", "--format=%s", BRANCH]) === "sandbox check"}`);
console.log(`host: main unchanged: ${git(CLONE, ["rev-parse", "main"]) === before}`);
console.log(`host: files planted in .git/hooks: ${fs.readdirSync(path.join(CLONE, ".git/hooks")).filter((f) => !f.endsWith(".sample")).length}`);
git(CLONE, ["branch", "-D", BRANCH]);

// A post-checkout hook that leaves a marker: it must fire without the harness's git settings and not with them.
const lab = fs.mkdtempSync(path.join(os.tmpdir(), "hook-check-"));
const marker = path.join(lab, "hook-ran");
const bare = { ...process.env };
for (const k of Object.keys(bare)) if (k.startsWith("GIT_CONFIG_")) delete bare[k];
git(lab, ["init", "-q", "-b", "main"], bare);
git(lab, ["-c", "user.name=c", "-c", "user.email=c@example.invalid", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "base"], bare);
fs.writeFileSync(path.join(lab, ".git/hooks/post-checkout"), `#!/bin/sh\ntouch "${marker}"\n`, { mode: 0o755 });
git(lab, ["checkout", "-q", "-b", "control"], bare);
console.log(`host git without the harness settings runs the hook: ${fs.existsSync(marker)}`);
fs.rmSync(marker, { force: true });
git(lab, ["checkout", "-q", "-b", "hardened"]);
console.log(`host git with the harness settings runs the hook: ${fs.existsSync(marker)}`);
fs.rmSync(lab, { recursive: true, force: true });
