#!/usr/bin/env node
// Advisory review of the comments a change adds, scored by Claude against AGENTS.md section 8. It reports and never
// gates. Exit codes: bin/README.md.

import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_ROOT = resolve(fileURLToPath(import.meta.url), "..", "..");
const MAX_PROMPT_CHARS = 150_000;

const USAGE = `Usage: node bin/comment-review.mjs <git-ref>

Scores every comment added since <git-ref>, committed or not, against AGENTS.md section 8
with claude -p, and prints a verdict per comment: keep, cut or shorten.

Exit: 0 reviewed (whatever the verdicts), 2 the review could not run.`;

const SLASH = /\.(java|kts?|ts|tsx|mjs|cjs|js|vue|css)$/;
const HASH = /\.(sh|bash|py|ya?ml|properties|conf|toml)$/;
const SKIPPED = /(^|\/)(archunit_store|test\/approved)\/|\.generated\.ts$|pnpm-lock\.yaml$/;

function commentOn(line, file) {
  const code = line.trim();
  if (SLASH.test(file)) {
    if (/^(\/\/|\/\*|\*|<!--)/.test(code)) return code;
    const trailing = /\s(\/\/\s.*)$/.exec(line);
    return trailing ? trailing[1] : undefined;
  }
  if (HASH.test(file)) {
    if (code.startsWith("#!")) return undefined;
    if (code.startsWith("#")) return code;
    const trailing = /\s(#\s.*)$/.exec(line);
    return trailing ? trailing[1] : undefined;
  }
  return undefined;
}

/** The comments a unified diff adds, consecutive lines joined into one, each with the hunk it sits in. */
export function addedComments(diff) {
  const comments = [];
  let file;
  let line = 0;
  let hunk = [];
  let open;
  const flush = () => {
    if (open) comments.push(open);
    open = undefined;
  };
  for (const raw of diff.split("\n")) {
    if (raw.startsWith("diff --git ")) {
      flush();
      file = undefined;
      continue;
    }
    if (raw.startsWith("+++ ")) {
      file = raw.startsWith("+++ b/") ? raw.slice(6) : undefined;
      continue;
    }
    const header = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(raw);
    if (header) {
      flush();
      line = Number(header[1]);
      hunk = [raw];
      continue;
    }
    if (!file || SKIPPED.test(file) || raw.startsWith("--- ") || raw.startsWith("\\")) continue;
    hunk.push(raw);
    if (raw.startsWith("-")) continue;
    const text = raw.startsWith("+") ? commentOn(raw.slice(1), file) : undefined;
    if (text !== undefined && open && open.file === file && open.end === line - 1) {
      open.text += `\n${text}`;
      open.end = line;
    } else {
      flush();
      if (text !== undefined) open = { file, line, end: line, text, hunk };
    }
    line++;
  }
  flush();
  return comments.map((c, i) => ({ id: i + 1, file: c.file, line: c.line, text: c.text, hunk: c.hunk }));
}

/** Section 8 of AGENTS.md, the rule the comments are scored against. */
export function rubric(agentsMd) {
  const start = agentsMd.indexOf("### 8. ");
  if (start < 0) throw new Error("AGENTS.md has no section 8");
  const end = agentsMd.indexOf("\n### ", start + 1);
  return agentsMd.slice(start, end < 0 ? undefined : end).trim();
}

export function prompt(rules, comments) {
  const hunks = [...new Set(comments.map((c) => c.hunk))].map((h) => h.join("\n"));
  const listed = comments.map((c) => `[${c.id}] ${c.file}:${c.line}\n${c.text}`);
  return `You review the comments a change adds against the project's comment rule below. Judge each numbered comment with the code around it in the diff. You report; nothing is edited.

<rule>
${rules}
</rule>

<diff>
${hunks.join("\n")}
</diff>

<comments>
${listed.join("\n\n")}
</comments>

Give every numbered comment one verdict, as a JSON array inside <verdicts></verdicts>, with nothing after it:
[{"id": 1, "verdict": "keep" | "cut" | "shorten", "rule": "the part of the rule it meets or breaks, in a few words", "why": "one sentence", "suggestion": "the shorter comment, for shorten only"}]
"cut" means the delete test removes it: the code says everything the comment says.`;
}

/** The verdicts from the model's answer; throws when there are none to read. */
export function parseVerdicts(answer) {
  const match = [...answer.matchAll(/<verdicts>([\s\S]*?)<\/verdicts>/g)].at(-1);
  if (!match) throw new Error("the answer has no <verdicts> block");
  const verdicts = JSON.parse(match[1]);
  if (!Array.isArray(verdicts)) throw new Error("<verdicts> is not a JSON array");
  return verdicts;
}

function main(argv) {
  if (argv[0] === "--help" || argv[0] === "-h") {
    process.stdout.write(`${USAGE}\n`);
    return 0;
  }
  if (argv.length !== 1 || argv[0].startsWith("-")) {
    process.stderr.write(`${USAGE}\n`);
    return 2;
  }
  const diff = spawnSync("git", ["diff", "--unified=3", "--no-color", argv[0]], { cwd: PROJECT_ROOT, encoding: "utf8", maxBuffer: 256 * 1024 * 1024 });
  if (diff.status !== 0) {
    process.stderr.write(`Error: git diff ${argv[0]} failed: ${diff.stderr.trim()}\n`);
    return 2;
  }
  const comments = addedComments(diff.stdout);
  if (comments.length === 0) {
    process.stdout.write(`No comments added since ${argv[0]}.\n`);
    return 0;
  }
  let reviewed = comments;
  let text = prompt(rubric(readFileSync(join(PROJECT_ROOT, "AGENTS.md"), "utf8")), reviewed);
  while (text.length > MAX_PROMPT_CHARS && reviewed.length > 1) {
    reviewed = reviewed.slice(0, Math.ceil(reviewed.length / 2));
    text = prompt(rubric(readFileSync(join(PROJECT_ROOT, "AGENTS.md"), "utf8")), reviewed);
  }
  if (reviewed.length < comments.length) {
    process.stderr.write(`==> Reviewing the first ${reviewed.length} of ${comments.length} comments; the rest would exceed the prompt budget.\n`);
  }
  process.stderr.write(`==> Asking claude -p about ${reviewed.length} comment(s)...\n`);
  const claude = spawnSync("claude", ["-p", "--tools", "", "--output-format", "text", "--no-session-persistence"],
    { cwd: PROJECT_ROOT, input: text, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (claude.error || claude.status !== 0) {
    process.stderr.write(`Error: claude -p failed: ${claude.error?.message ?? claude.stderr.trim()}\n`);
    return 2;
  }
  let verdicts;
  try {
    verdicts = parseVerdicts(claude.stdout);
  } catch (e) {
    process.stderr.write(`Error: ${e.message}:\n${claude.stdout.slice(-2000)}\n`);
    return 2;
  }
  const byId = new Map(reviewed.map((c) => [c.id, c]));
  for (const v of verdicts) {
    const c = byId.get(v.id);
    if (!c) continue;
    process.stdout.write(`${c.file}:${c.line}  ${String(v.verdict).toUpperCase()}  ${v.rule} — ${v.why}\n`);
    process.stdout.write(`    ${c.text.split("\n").join("\n    ")}\n`);
    if (v.suggestion) process.stdout.write(`    → ${v.suggestion}\n`);
  }
  const counts = verdicts.reduce((n, v) => ({ ...n, [v.verdict]: (n[v.verdict] ?? 0) + 1 }), {});
  process.stdout.write(`\n${reviewed.length} comment(s): ${Object.entries(counts).map(([k, n]) => `${n} ${k}`).join(", ")}. Advisory only.\n`);
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
