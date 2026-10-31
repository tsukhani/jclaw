#!/usr/bin/env node
// Enforces AGENTS.md's Policy block: Claude Code's PreToolUse hook hands it a tool call, and .githooks/pre-push the
// refs a push carries. Contract and exit codes: bin/README.md.

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const USAGE = `Usage: node bin/policy-guard.mjs hook        (a PreToolUse event on stdin; prints a deny decision or nothing)
       node bin/policy-guard.mjs pre-push REMOTE (git's pre-push ref lines on stdin; exit 1 refuses the push)`;

const REMOTES = new Set(["origin", "github"]);
const RELEASE = /^Release v\d+\.\d+\.\d+$/;
const VERSION_TAG = /^refs\/tags\/v\d+\.\d+\.\d+$/;
const STOPS = new Set(["restart", "stop", "restore", "repair", "compact", "reset", "upgrade", "uninstall"]);
const UPSTREAM_SKILLS = new Set(["claude-designer", "powerpoint-pptx", "humanizer", "youtube-transcript", "git",
  "frontend-design", "ffmpeg-video-processing"]);
const APPROVAL = "JCLAW_OPERATOR_APPROVED";
const EDITS = new Set(["Edit", "Write", "MultiEdit", "NotebookEdit"]);
const SHELLS = new Set(["bash", "sh", "zsh", "dash", "ksh"]);
const RESERVED = new Set(["if", "then", "else", "elif", "fi", "do", "done", "while", "until", "for", "case", "esac",
  "function", "select", "{", "}", "!", "time", "coproc"]);
// What a crash still refuses: a parser bug must not open the push path, nor block every other command.
const TRIGGER = /push|no-verify|stash|hookspath|JCLAW_SKIP_TESTS|jclaw\.sh|kill|skills\//i;

export const REASONS = {
  push: "Pushing is /deploy's job (AGENTS.md Policy): stop at the local commit and report its hash. The guard passes only "
    + "git push origin|github HEAD on main at a \"Release vX.Y.Z\" commit, a version tag pushed alone, and /renovate's "
    + "deletion of renovate/* branches.",
  noVerify: "Skipping git hooks is refused (AGENTS.md Policy): fix what the hook reports, then commit again.",
  hooksPath: "Changing core.hooksPath skips the repository's hooks (AGENTS.md Policy): leave it at .githooks.",
  skipTests: "JCLAW_SKIP_TESTS is a human's bypass, never an agent's (AGENTS.md Policy): let the pre-push tests run.",
  stash: "git stash with untracked files round-trips other sessions' work through the stash (AGENTS.md Policy): verify "
    + "in a detached worktree holding HEAD plus your diff instead.",
  stop: "This stops the JClaw instance, which may be serving live work (AGENTS.md Policy): ask the operator in chat, and "
    + `once they approve, rerun the same command prefixed with ${APPROVAL}=1.`,
  skills: (name) => `skills/${name} is imported from upstream, and a re-import overwrites hand edits (AGENTS.md Policy): `
    + "change it upstream, or ask the operator.",
};

const deny = (rule, reason = REASONS[rule]) => ({ rule, reason });

// ─── Shell parsing ───

/** Index of the `)` closing the `(` at `open`, skipping quotes; the end of `src` when unbalanced. */
function closeParen(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === "\\") i++;
    else if (c === "'") i = src.indexOf("'", i + 1) < 0 ? src.length : src.indexOf("'", i + 1);
    else if (c === '"') {
      for (i++; i < src.length && src[i] !== '"'; i++) if (src[i] === "\\") i++;
    } else if (c === "(") depth++;
    else if (c === ")" && --depth === 0) return i;
  }
  return src.length;
}

function closeBacktick(src, open) {
  for (let i = open + 1; i < src.length; i++) {
    if (src[i] === "\\") i++;
    else if (src[i] === "`") return i;
  }
  return src.length;
}

/**
 * Words and control operators of one command line. Quotes are removed from a word's value; command substitutions,
 * backticks and process substitutions are returned as nested command lines; redirection targets and heredoc bodies
 * are dropped, so text a command only reads never counts as its arguments.
 */
function lex(src) {
  const tokens = [];
  const nested = [];
  const heredocs = [];
  let word = null;
  let raw = "";
  let target = null;
  let i = 0;
  const start = () => { if (word === null) { word = ""; raw = ""; } };
  const end = () => {
    if (word === null) return;
    if (target === "drop") target = null;
    else if (target) { heredocs.push({ delim: word, strip: target === "heredoc-" }); target = null; }
    else tokens.push({ v: word, raw });
    word = null;
  };
  const substitute = (from, to, body) => {
    start();
    nested.push(body);
    word += "\u0000";
    raw += src.slice(from, to + 1);
    i = to + 1;
  };
  const skipHeredocs = () => {
    for (const h of heredocs.splice(0)) {
      while (i < src.length) {
        const nl = src.indexOf("\n", i);
        let line = src.slice(i, nl < 0 ? src.length : nl);
        i = nl < 0 ? src.length : nl + 1;
        if (h.strip) line = line.replace(/^\t+/, "");
        if (line === h.delim) break;
      }
    }
  };
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (c === "\n") { end(); tokens.push({ op: "\n" }); i++; skipHeredocs(); continue; }
    if (c === " " || c === "\t" || c === "\r") { end(); i++; continue; }
    if (c === "#" && word === null) { while (i < src.length && src[i] !== "\n") i++; continue; }
    if (c === "\\") {
      if (n !== "\n") { start(); word += n ?? ""; raw += src.slice(i, i + 2); }
      i += 2;
      continue;
    }
    if (c === "'") {
      start();
      const j = src.indexOf("'", i + 1) < 0 ? src.length : src.indexOf("'", i + 1);
      word += src.slice(i + 1, j);
      raw += src.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    if (c === "$" && n === "'") {
      start();
      let j = i + 2;
      for (; j < src.length && src[j] !== "'"; j++) word += src[j] === "\\" ? src[++j] ?? "" : src[j];
      raw += src.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    if (c === '"') {
      start();
      raw += c;
      for (i++; i < src.length && src[i] !== '"';) {
        if (src[i] === "\\" && '"\\$`\n'.includes(src[i + 1])) { word += src[i + 1]; raw += src.slice(i, i + 2); i += 2; }
        else if (src[i] === "$" && src[i + 1] === "(") { const j = closeParen(src, i + 1); nested.push(src.slice(i + 2, j)); word += "\u0000"; raw += src.slice(i, j + 1); i = j + 1; }
        else if (src[i] === "`") { const j = closeBacktick(src, i); nested.push(src.slice(i + 1, j)); word += "\u0000"; raw += src.slice(i, j + 1); i = j + 1; }
        else { word += src[i]; raw += src[i]; i++; }
      }
      raw += '"';
      i++;
      continue;
    }
    if (c === "$" && n === "(") {
      if (src[i + 2] === "(") { start(); const j = closeParen(src, i + 1); word += "\u0000"; raw += src.slice(i, j + 1); i = j + 1; continue; }
      const j = closeParen(src, i + 1);
      substitute(i, j, src.slice(i + 2, j));
      continue;
    }
    if (c === "$" && n === "{") {
      start();
      const j = src.indexOf("}", i) < 0 ? src.length : src.indexOf("}", i);
      word += src.slice(i, j + 1);
      raw += src.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    if (c === "`") { const j = closeBacktick(src, i); substitute(i, j, src.slice(i + 1, j)); continue; }
    if ((c === "<" || c === ">") && n === "(") { const j = closeParen(src, i + 1); substitute(i, j, src.slice(i + 2, j)); continue; }
    if (c === "<" || c === ">" || (c === "&" && n === ">")) {
      if (word !== null && /^\d+$/.test(word)) word = null;
      else end();
      if (src.startsWith("<<<", i)) { i += 3; target = "drop"; continue; }
      if (src.startsWith("<<-", i)) { i += 3; target = "heredoc-"; continue; }
      if (src.startsWith("<<", i)) { i += 2; target = "heredoc"; continue; }
      let j = i;
      if (src[j] === "&") j++;
      while (src[j] === "<" || src[j] === ">") j++;
      if (src[j] === "|") j++;
      else if (src[j] === "&") {
        j++;
        if (/[\d-]/.test(src[j] ?? "")) { while (/[\d-]/.test(src[j] ?? "")) j++; i = j; continue; }
      }
      i = j;
      target = "drop";
      continue;
    }
    if (c === "|" || c === "&" || c === ";" || ((c === "(" || c === ")") && word === null)) {
      end();
      const op = (c === "|" || c === "&" || c === ";") && n === c ? c + c : c === "|" && n === "&" ? "|&" : c;
      tokens.push({ op });
      i += op.length;
      continue;
    }
    start();
    word += c;
    raw += c;
    i++;
  }
  end();
  return { tokens, nested };
}

/** The simple commands a command line runs, wrappers unwrapped, including those inside substitutions and -c strings. */
export function simpleCommands(line, depth = 0) {
  if (depth > 8) return [];
  const { tokens, nested } = lex(line);
  const out = [];
  let words = [];
  const flush = () => {
    if (words.length) out.push(...unwrap(words, depth));
    words = [];
  };
  for (const t of tokens) {
    if (t.op) flush();
    else words.push(t);
  }
  flush();
  for (const n of nested) out.push(...simpleCommands(n, depth + 1));
  return out;
}

function unwrap(tokens, depth) {
  let words = tokens.map((t) => t.v);
  let raws = tokens.map((t) => t.raw);
  const assigns = new Map();
  let viaXargs = false;
  const shift = (k = 1) => { words = words.slice(k); raws = raws.slice(k); };
  const takeAssigns = () => {
    while (words.length && /^[A-Za-z_]\w*=/.test(words[0])) {
      const eq = words[0].indexOf("=");
      assigns.set(words[0].slice(0, eq), words[0].slice(eq + 1));
      shift();
    }
  };
  const skipOptions = (withValue = new Set()) => {
    while (words.length && words[0].startsWith("-") && words[0] !== "-") {
      if (words[0] === "--") { shift(); return; }
      shift(withValue.has(words[0]) ? 2 : 1);
    }
  };
  for (;;) {
    while (words.length && RESERVED.has(words[0])) shift();
    takeAssigns();
    const name = basename(words[0] ?? "");
    if (name === "env") { shift(); skipOptions(new Set(["-u", "-S", "-C"])); continue; }
    if (["command", "exec", "nohup", "builtin", "noglob", "caffeinate"].includes(name)) { shift(); skipOptions(new Set(["-t", "-w"])); continue; }
    if (name === "sudo") { shift(); skipOptions(new Set(["-u", "-g", "-C", "-D", "-h", "-p", "-U"])); continue; }
    if (name === "nice") { shift(); skipOptions(new Set(["-n"])); continue; }
    if (name === "timeout" || name === "gtimeout") { shift(); skipOptions(new Set(["-s", "-k"])); shift(); continue; }
    if (name === "xargs") {
      shift();
      skipOptions(new Set(["-I", "-L", "-n", "-P", "-s", "-E", "-d", "-a", "-J", "-R", "-S"]));
      viaXargs = true;
      continue;
    }
    if (name === "rtk") { shift(); if (words[0] === "proxy") shift(); continue; }
    if (SHELLS.has(name)) {
      const k = words.findIndex((w, idx) => idx > 0 && /^-[a-z]*c[a-z]*$/.test(w));
      if (k > 0 && words[k + 1] !== undefined) return simpleCommands(words[k + 1], depth + 1);
    }
    if (name === "eval") return simpleCommands(words.slice(1).join(" "), depth + 1);
    return [{ assigns, words, raws, viaXargs }];
  }
}

// ─── Rules ───

function gitArgs(args) {
  const config = [];
  let dir = null;
  let i = 0;
  while (i < args.length && args[i].startsWith("-")) {
    const a = args[i];
    if (a === "-C") { dir = args[i + 1]; i += 2; }
    else if (a === "-c" || a === "--config-env") { config.push(args[i + 1] ?? ""); i += 2; }
    else if (["--git-dir", "--work-tree", "--namespace", "--exec-path", "--super-prefix"].includes(a)) i += 2;
    else i++;
  }
  return { dir, config, sub: args[i], rest: args.slice(i + 1) };
}

// Options whose value is the next word, so a message such as "-n" or "--no-verify" is never read as a flag.
const VALUE_OPTS = {
  commit: new Set(["--message", "--file", "--author", "--date", "--template", "--reuse-message", "--reedit-message",
    "--fixup", "--squash", "--cleanup", "--trailer", "--pathspec-from-file"]),
  merge: new Set(["-m", "-F", "-s", "-X", "--message", "--file", "--strategy", "--strategy-option", "--into-name"]),
  rebase: new Set(["-s", "-X", "-x", "-C", "--onto", "--exec", "--strategy", "--strategy-option"]),
  pull: new Set(["-s", "-X", "--strategy", "--strategy-option", "--depth", "--upload-pack"]),
  am: new Set([]),
  push: new Set(["-o", "--push-option", "--receive-pack", "--repo", "--exec"]),
};

function skipsHooks(sub, rest) {
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a === "--") break;
    if (a === "--no-verify") return true;
    if (VALUE_OPTS[sub].has(a)) { i++; continue; }
    // In a commit's short-flag cluster, -n is --no-verify; the first value-taking letter ends the flags.
    if (sub === "commit" && /^-[A-Za-z]+$/.test(a)) {
      for (let k = 1; k < a.length; k++) {
        if (a[k] === "n") return true;
        if ("mFCct".includes(a[k])) { if (k === a.length - 1) i++; break; }
        if ("uS".includes(a[k])) break;
      }
    }
  }
  return false;
}

function pushAllowed(rest, state) {
  if (rest.length === 2 && REMOTES.has(rest[0])) {
    if (VERSION_TAG.test(rest[1])) return true;
    if (rest[1] === "HEAD") {
      const s = state();
      return s.branch === "main" && s.mainCheckout && RELEASE.test(s.subject);
    }
  }
  // The names usually arrive through xargs, where only the ref layer sees them.
  return rest[0] === "origin" && rest[1] === "--delete"
    && rest.slice(2).every((r) => r.startsWith("renovate/") || r.startsWith("refs/heads/renovate/"));
}

function stashesUntracked(rest) {
  let args = rest;
  if (args[0] === "push" || args[0] === "save") args = args.slice(1);
  else if (args.length && !args[0].startsWith("-")) return false;
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === "--") break;
    if (a === "--include-untracked" || a === "--all") return true;
    if (a === "-m" || a === "--message" || a === "--pathspec-from-file") { i++; continue; }
    if (/^-[A-Za-z]+$/.test(a)) {
      for (let k = 1; k < a.length; k++) {
        if (a[k] === "u" || a[k] === "a") return true;
        if (a[k] === "m") { if (k === a.length - 1) i++; break; }
      }
    }
  }
  return false;
}

function setsHooksPath(rest) {
  const k = rest.findIndex((a) => /^core\.hookspath$/i.test(a));
  if (k < 0) return false;
  const before = rest.slice(0, k);
  if (before.some((o) => o === "get" || o.startsWith("--get") || o === "--list" || o === "-l")) return false;
  if (before.some((o) => o === "unset" || o.startsWith("--unset") || o === "--remove-section")) return true;
  return rest[k + 1] !== undefined && rest[k + 1] !== ".githooks";
}

function gitRule(cmd, ctx) {
  const { dir, config, sub, rest } = gitArgs(cmd.words.slice(1));
  if (config.some((c) => /^core\.hookspath=/i.test(c))) return deny("hooksPath");
  const where = dir ? resolve(ctx.dir, dir) : ctx.dir;
  if (sub in VALUE_OPTS && skipsHooks(sub, rest)) return deny("noVerify");
  if (sub === "push" && !pushAllowed(rest, () => ctx.state(where))) return deny("push");
  if (sub === "stash" && stashesUntracked(rest)) return deny("stash");
  if (sub === "config" && setsHooksPath(rest)) return deny("hooksPath");
  return null;
}

function killsServer(cmd, ctx, line) {
  const args = cmd.words.slice(1);
  if (args.includes("-0")) return false;
  const pid = ctx.serverPid();
  if (pid && args.includes(pid)) return true;
  const text = cmd.viaXargs ? line : cmd.raws.join(" ");
  return /server\.pid|:(9000|9443)\b/.test(text);
}

function commandRule(cmd, ctx, line) {
  const { assigns, words } = cmd;
  if (assigns.get("JCLAW_SKIP_TESTS") || (words[0] === "export" && words.some((w) => /^JCLAW_SKIP_TESTS=./.test(w)))) {
    return deny("skipTests");
  }
  if ([...assigns.values()].some((v) => /core\.hookspath/i.test(v))) return deny("hooksPath");
  const name = basename(words[0] ?? "");
  if (name === "git") return gitRule(cmd, ctx);
  const approved = assigns.get(APPROVAL) === "1";
  const script = name === "jclaw.sh" ? words.slice(1)
    : SHELLS.has(name) && basename(words[1] ?? "") === "jclaw.sh" ? words.slice(2) : null;
  if (script && !approved && script.some((a) => STOPS.has(a))) return deny("stop");
  if (name === "kill" && !approved && killsServer(cmd, ctx, line)) return deny("stop");
  return null;
}

/** The refusal for a Bash command line, or null. `ctx`: {cwd, state(dir), serverPid()}. */
export function commandVerdict(line, ctx) {
  let dir = ctx.cwd;
  for (const cmd of simpleCommands(line)) {
    if (cmd.words[0] === "cd" && cmd.words[1] && !cmd.words[1].includes("\u0000")) {
      dir = resolve(dir, cmd.words[1].replace(/^~(?=$|\/)/, homedir()));
      continue;
    }
    const v = commandRule(cmd, { ...ctx, dir }, line);
    if (v) return v;
  }
  return null;
}

/** The refusal for an edit of `file`, or null. `ctx`: {cwd, checkoutOf(absolutePath) → jclaw checkout root or null}. */
export function pathVerdict(file, ctx) {
  if (!file) return null;
  const abs = resolve(ctx.cwd, file);
  const root = ctx.checkoutOf(abs);
  if (!root) return null;
  const m = relative(root, abs).split(sep).join("/").match(/^skills\/([^/]+)\//);
  return m && UPSTREAM_SKILLS.has(m[1]) ? deny("skills", REASONS.skills(m[1])) : null;
}

/** The refusal for git's pre-push ref lines, or null. `state(sha)` → {branch, subject, mainCheckout}. */
export function refsVerdict(text, remote, state) {
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    const [localRef, localSha, remoteRef] = line.split(" ");
    if (!refAllowed(localRef, localSha, remoteRef, remote, state)) return { ...deny("push"), ref: remoteRef };
  }
  return null;
}

function refAllowed(localRef, localSha, remoteRef, remote, state) {
  if (/^0+$/.test(localSha)) return remote === "origin" && remoteRef.startsWith("refs/heads/renovate/");
  if (!REMOTES.has(remote)) return false;
  if (VERSION_TAG.test(remoteRef)) return localRef === remoteRef;
  if (remoteRef !== "refs/heads/main") return false;
  const s = state(localSha);
  // git names the local ref as the refspec spelled it: `git push origin HEAD` arrives as HEAD.
  const fromMain = localRef === "refs/heads/main" || (localRef === "HEAD" && s.branch === "main");
  return fromMain && s.mainCheckout && RELEASE.test(s.subject);
}

// ─── Live context ───

function git(dir, ...args) {
  return execFileSync("git", ["-C", dir, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
}

function gitState(dir, rev = "HEAD") {
  try {
    const gitDir = git(dir, "rev-parse", "--path-format=absolute", "--git-dir");
    const common = git(dir, "rev-parse", "--path-format=absolute", "--git-common-dir");
    return { branch: git(dir, "rev-parse", "--abbrev-ref", "HEAD"), subject: git(dir, "log", "-1", "--format=%s", rev),
      mainCheckout: gitDir === common, common };
  } catch {
    return { branch: "", subject: "", mainCheckout: false, common: "" };
  }
}

function liveContext(cwd) {
  return {
    cwd,
    state: (dir) => gitState(dir),
    // The live instance runs from the main checkout, which a worktree reaches through the common git dir.
    serverPid: () => {
      const { common } = gitState(cwd);
      const file = common && join(dirname(common), "server.pid");
      return file && existsSync(file) ? readFileSync(file, "utf8").trim() : "";
    },
    checkoutOf: (abs) => {
      let dir = dirname(abs);
      while (!existsSync(dir) && dir !== dirname(dir)) dir = dirname(dir);
      try {
        const top = git(dir, "rev-parse", "--show-toplevel");
        return existsSync(join(top, "jclaw.sh")) ? top : null;
      } catch {
        return null;
      }
    },
  };
}

/** The refusal for a PreToolUse event, or null. */
export function hookVerdict(event, ctx = liveContext(event.cwd ?? process.cwd())) {
  const input = event.tool_input ?? {};
  if (event.tool_name === "Bash") return commandVerdict(input.command ?? "", ctx);
  if (EDITS.has(event.tool_name)) return pathVerdict(input.file_path ?? input.notebook_path, ctx);
  return null;
}

function main(argv) {
  const input = readFileSync(0, "utf8");
  if (argv[0] === "hook") {
    let v;
    try {
      v = hookVerdict(JSON.parse(input));
    } catch (e) {
      v = TRIGGER.test(input) ? deny("crash", `The policy guard failed on this call (${e.message}), so it refuses `
        + "anything touching what it guards until bin/policy-guard.mjs is fixed.") : null;
    }
    if (v) {
      process.stdout.write(`${JSON.stringify({ hookSpecificOutput: { hookEventName: "PreToolUse",
        permissionDecision: "deny", permissionDecisionReason: v.reason } })}\n`);
    }
    return;
  }
  if (argv[0] === "pre-push") {
    let v;
    try {
      v = refsVerdict(input, argv[1] ?? "", (sha) => gitState(process.cwd(), sha));
    } catch (e) {
      v = { ref: "", reason: `the policy guard failed (${e.message}), so it refuses the push.` };
    }
    if (v) {
      process.stderr.write(`[jclaw pre-push] Refused ${v.ref}: ${v.reason}\n`);
      process.exit(1);
    }
    return;
  }
  process.stderr.write(`${USAGE}\n`);
  process.exit(2);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
