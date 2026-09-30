// Live check of a bmad story's setup: the seed installs into a sandbox, BMAD renders bmad-build-auto there with the
// factory's overrides, and the tree stays clean for bmad-build-auto's own check. No model call. `npx tsx bmad-check.ts`
import { execFileSync } from "node:child_process";
import * as sandcastle from "@ai-hero/sandcastle";
import { ensureBmadSeed, ensureGateway, factorySandbox, installBmad, planHooks } from "./factory.ts";
import { CLONE } from "./paths.ts";

const BRANCH = "agent/bmad-check";
ensureGateway();
ensureBmadSeed(true);
{
  await using sandbox = await sandcastle.createSandbox({ cwd: CLONE, branch: BRANCH, sandbox: factorySandbox(), hooks: planHooks });
  console.log(`BMAD ${await installBmad(sandbox)} installed`);
  const r = await sandbox.exec([
    'out=$(uv run --no-cache _bmad/scripts/render_skill.py --project-root "$PWD" --skill "$PWD/.claude/skills/bmad-build-auto" 2>&1) && echo "render    ok" || echo "render    FAILED: $out"',
    'w=$(echo "$out" | sed -n "s/^read and follow //p"); grep -q "AFK factory" "$w" && grep -q "promise>COMPLETE" "$w" && echo "overrides applied" || echo "overrides MISSING"',
    'git add --refresh -- . && test -z "$(git status --porcelain)" && echo "tree      clean" || { echo "tree      DIRTY"; git status --porcelain; }',
  ].join("; "));
  console.log(r.stdout.trim());
}
execFileSync("/usr/bin/git", ["-C", CLONE, "branch", "-D", BRANCH]);
