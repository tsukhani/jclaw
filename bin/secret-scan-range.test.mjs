import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { after, before, describe, test } from "node:test";
import { fileURLToPath } from "node:url";

import { messageLines, newHits, newLines, parseGrep, run } from "./secret-scan-range.mjs";

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "secret-scan-range.mjs");
const env = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" };

function repo() {
  const dir = mkdtempSync(join(tmpdir(), "secret-scan-range-"));
  const git = (...args) => {
    const r = spawnSync("git", args, { cwd: dir, env, encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
    return r.stdout.trim();
  };
  git("init", "-q");
  for (const [k, v] of [["user.name", "t"], ["user.email", "t@example.invalid"], ["commit.gpgsign", "false"],
    ["tag.gpgsign", "false"]]) git("config", k, v);
  const write = (path, text) => {
    mkdirSync(dirname(join(dir, path)), { recursive: true });
    writeFileSync(join(dir, path), text);
  };
  const commit = (message) => {
    git("add", "-A");
    git("commit", "-q", "--allow-empty", "-m", message);
    return git("rev-parse", "HEAD");
  };
  return { dir, git, write, commit };
}

describe("secret-scan-range over a release and the commits after it", () => {
  let r;
  const sha = {};
  before(() => {
    r = repo();
    r.write("a.txt", "keep MARK-1\n");
    r.write("old:name.txt", "keep MARK-9\n");
    r.commit("the release");
    r.git("tag", "v1.0.0");
    r.write("a.txt", "inserted above\nkeep MARK-1\nnew MARK-2\ngone MARK-3\n");
    r.write("dir/a:b.txt", "colon MARK-4\n");
    r.write(".claude/commands/secret-scan.md", "documents MARK-5\n");
    r.write("dots.txt", "a.b literal\naxb not literal\n");
    sha.first = r.commit("adds lines\n\nThe body names MARK-2.\n\nCo-Authored-By: Someone <noreply@example.invalid>");
    r.write("a.txt", "inserted above\nkeep MARK-1\nnew MARK-2\n");
    sha.second = r.commit("deletes the MARK-3 line");
  });
  after(() => rmSync(r.dir, { recursive: true, force: true }));

  test("a line the range added is reported once, with the newest commit that holds it", () => {
    assert.deepEqual(newLines(r.dir, "new MARK-\\d"), [{ rev: sha.second, path: "a.txt", line: 3, text: "new MARK-2" }]);
  });

  test("a line the release held is not reported, even after the range moved it to another line", () => {
    assert.deepEqual(newLines(r.dir, "keep MARK-\\d"), []);
  });

  test("a line added and deleted again within the range is reported with the commit that still holds it", () => {
    assert.deepEqual(newLines(r.dir, "gone MARK-\\d"), [{ rev: sha.first, path: "a.txt", line: 4, text: "gone MARK-3" }]);
  });

  test("hits are reported when the release matched nothing, and a path with a colon keeps its whole name", () => {
    assert.deepEqual(newLines(r.dir, "colon MARK-\\d"),
      [{ rev: sha.second, path: "dir/a:b.txt", line: 1, text: "colon MARK-4" }]);
  });

  test("the scan's own command file is left out", () => {
    assert.deepEqual(newLines(r.dir, "documents MARK-\\d"), []);
  });

  test("paths after the pattern limit the scan to those subtrees", () => {
    assert.deepEqual(run(["lines", "-P", "MARK-[24]", "dir"], r.dir), { code: 0, out: `${sha.second}:dir/a:b.txt:1:colon MARK-4\n` });
    assert.deepEqual(run(["lines", "-P", "MARK-[24]"], r.dir).out.split("\n").filter(Boolean).length, 2);
  });

  test("a fixed string matches literally, where the same text as a pattern matches more", () => {
    assert.deepEqual(newLines(r.dir, "a.b", { fixed: true }).map((h) => h.text), ["a.b literal"]);
    assert.deepEqual(newLines(r.dir, "a.b").map((h) => h.text), ["a.b literal", "axb not literal"]);
  });

  test("a pattern that matches nothing gives no lines and exit 0, not an error", () => {
    assert.deepEqual(run(["lines", "-P", "NOWHERE-\\d"], r.dir), { code: 0, out: "" });
  });

  test("a pattern starting with a dash is a pattern, not an option", () => {
    assert.deepEqual(run(["lines", "-P", "-----BEGIN"], r.dir), { code: 0, out: "" });
  });

  test("lines prints sha:path:line:text, the shape the report reads", () => {
    assert.deepEqual(run(["lines", "-F", "new MARK-2"], r.dir), { code: 0, out: `${sha.second}:a.txt:3:new MARK-2\n` });
  });

  test("range prints the release tag and how many commits follow it", () => {
    assert.deepEqual(run(["range"], r.dir), { code: 0, out: "v1.0.0 2\n" });
  });

  test("each message line carries its commit's short sha, without the Co-Authored-By trailers", () => {
    const short = (s) => r.git("rev-parse", "--short", s);
    assert.deepEqual(messageLines(r.dir), [
      `${short(sha.second)}: deletes the MARK-3 line`,
      `${short(sha.first)}: adds lines`,
      `${short(sha.first)}: The body names MARK-2.`,
    ]);
  });

  test("the script run from its file prints the same as run()", () => {
    const cli = spawnSync(process.execPath, [SCRIPT, "lines", "-F", "new MARK-2"], { cwd: r.dir, env, encoding: "utf8" });
    assert.deepEqual([cli.status, cli.stdout, cli.stderr], [0, `${sha.second}:a.txt:3:new MARK-2\n`, ""]);
  });
});

describe("secret-scan-range refuses rather than reports nothing", () => {
  test("a checkout with no v* tag exits 2, naming git's reason", () => {
    const r = repo();
    try {
      r.write("a.txt", "x\n");
      r.commit("one");
      const result = run(["range"], r.dir);
      assert.equal(result.code, 2);
      assert.match(result.err, /^secret-scan-range: fatal: No names found/);
    } finally {
      rmSync(r.dir, { recursive: true, force: true });
    }
  });

  test("an invalid pattern exits 2 instead of matching nothing", () => {
    const r = repo();
    try {
      r.write("a.txt", "x\n");
      r.commit("one");
      r.git("tag", "v1.0.0");
      r.write("a.txt", "y\n");
      r.commit("two");
      const result = run(["lines", "-P", "(unclosed"], r.dir);
      assert.equal(result.code, 2);
      assert.match(result.err, /^secret-scan-range: /);
    } finally {
      rmSync(r.dir, { recursive: true, force: true });
    }
  });

  test("an unknown command, a missing mode or a missing pattern prints the usage and exits 2", () => {
    const usage = "usage: secret-scan-range.mjs range | lines -P <pattern> [path...] | lines -F <string> [path...] | messages\n";
    for (const args of [[], ["scan"], ["lines", "MARK"], ["lines", "-P"], ["range", "extra"]]) {
      assert.deepEqual(run(args, tmpdir()), { code: 2, err: usage }, args.join(" "));
    }
  });
});

describe("the filter on its own", () => {
  test("parseGrep splits at the first colon only, since a path may hold one", () => {
    assert.deepEqual(parseGrep("v1.0.0:a:b.txt\u00003\u0000text: with colon\n"),
      [{ rev: "v1.0.0", path: "a:b.txt", line: 3, text: "text: with colon" }]);
  });

  test("newHits keeps a hit the base never held, and only its first, newest occurrence", () => {
    const hit = (rev, path, line, text) => ({ rev, path, line, text });
    assert.deepEqual(newHits([hit("v1", "a", 1, "old")], [hit("c2", "a", 9, "old"), hit("c2", "a", 2, "new"),
      hit("c1", "a", 2, "new")]), [hit("c2", "a", 2, "new")]);
  });

  test("an empty base filters nothing out", () => {
    const hit = { rev: "c1", path: "a", line: 1, text: "new" };
    assert.deepEqual(newHits([], [hit]), [hit]);
  });
});
