#!/usr/bin/env node
// The range half of /secret-scan: the lines a pattern matches that the commits since the last release added, and those
// commits' messages. It is a script because a command run with arguments has every dollar sign followed by a digit in
// its text replaced by an argument's word, which garbled the awk this replaces. Contract and exit codes: bin/README.md.

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// The command documents the patterns, so it would match every one of them.
const SELF = ":!.claude/commands/secret-scan.md";

export class GitError extends Error {}

function git(cwd, args, { noMatchIsEmpty = false } = {}) {
  try {
    return execFileSync("git", args, { cwd, encoding: "utf8", maxBuffer: 1 << 30, stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    // git grep exits 1, saying nothing, when it matched nothing; anything else is a failed scan, never a clean one.
    if (noMatchIsEmpty && error.status === 1 && !error.stderr) return "";
    throw new GitError((error.stderr || error.message).trim());
  }
}

/** The newest v* tag reachable from HEAD, and the commits after it, newest first. */
export function rangeOf(cwd) {
  const base = git(cwd, ["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD"]).trim();
  return { base, revs: git(cwd, ["rev-list", `${base}..HEAD`]).split("\n").filter(Boolean) };
}

/** `git grep -nz` over revisions: `<rev>:<path>\0<line>\0<text>`, where a revision never holds a colon and a path may. */
export function parseGrep(out) {
  return out.split("\n").filter(Boolean).map((row) => {
    const [where, line, text] = row.split("\0");
    const colon = where.indexOf(":");
    return { rev: where.slice(0, colon), path: where.slice(colon + 1), line: Number(line), text };
  });
}

/**
 * The range's hits whose path and text the base did not hold, each once. git grep reports the trees in the order given,
 * newest first, so each keeps the newest commit holding it: HEAD's when the line is in the tree.
 */
export function newHits(baseHits, rangeHits) {
  const key = (hit) => `${hit.path}\0${hit.text}`;
  const shipped = new Set(baseHits.map(key));
  const seen = new Set();
  return rangeHits.filter((hit) => {
    const k = key(hit);
    if (shipped.has(k) || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/**
 * What `pattern` matches under `paths` in the range's commits that the last release did not hold; `fixed` matches it
 * literally.
 */
export function newLines(cwd, pattern, { fixed = false, paths = ["."] } = {}) {
  const { base, revs } = rangeOf(cwd);
  if (revs.length === 0) return [];
  const grep = (trees) => parseGrep(git(cwd,
    ["grep", "-nIz", fixed ? "-F" : "-P", "-e", pattern, ...trees, "--", ...paths, SELF], { noMatchIsEmpty: true }));
  return newHits(grep([base]), grep(revs));
}

/** Each line of the range's commit messages behind its commit's short sha, leaving out the Co-Authored-By trailers. */
export function messageLines(cwd) {
  const { base } = rangeOf(cwd);
  let sha = "";
  const lines = [];
  for (const line of git(cwd, ["log", "--format=%x00%h%n%B", `${base}..HEAD`]).split("\n")) {
    if (line.startsWith("\0")) sha = line.slice(1);
    else if (line !== "" && !line.startsWith("Co-Authored-By: ")) lines.push(`${sha}: ${line}`);
  }
  return lines;
}

const USAGE = "usage: secret-scan-range.mjs range | lines -P <pattern> [path...] | lines -F <string> [path...] | messages";

/** 0 with what it found, which may be nothing, or 2 with why the scan could not run. */
export function run(args, cwd) {
  const [command, mode, pattern, ...paths] = args;
  try {
    if (command === "range" && args.length === 1) {
      const { base, revs } = rangeOf(cwd);
      return { code: 0, out: `${base} ${revs.length}\n` };
    }
    if (command === "lines" && (mode === "-P" || mode === "-F") && pattern) {
      const hits = newLines(cwd, pattern, { fixed: mode === "-F", paths: paths.length ? paths : ["."] });
      return { code: 0, out: hits.map((h) => `${h.rev}:${h.path}:${h.line}:${h.text}\n`).join("") };
    }
    if (command === "messages" && args.length === 1) return { code: 0, out: messageLines(cwd).map((l) => `${l}\n`).join("") };
    return { code: 2, err: `${USAGE}\n` };
  } catch (error) {
    if (!(error instanceof GitError)) throw error;
    return { code: 2, err: `secret-scan-range: ${error.message}\n` };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { code, out = "", err = "" } = run(process.argv.slice(2), process.cwd());
  process.stdout.write(out);
  process.stderr.write(err);
  process.exit(code);
}
