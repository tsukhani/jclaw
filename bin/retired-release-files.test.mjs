// node --test bin/retired-release-files.test.mjs
// The filters run on literals; the table check reads this clone's tags and index, and is skipped in a clone with no
// v1.0.23 tag, as a shallow CI checkout can be.

import { deepStrictEqual, ok, strictEqual } from "node:assert";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { LAST_UNLISTED, TABLE, distignorePrefixes, render, retirable, ships, versionAtMost } from "./retired-release-files.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const git = (...args) => spawnSync("git", args, { cwd: ROOT, maxBuffer: 1 << 30 });

test("only releases up to the last one without a list count, by version rather than by name", () => {
  strictEqual(versionAtMost("v1.0.23", "v1.0.23"), true);
  strictEqual(versionAtMost("v1.0.9", "v1.0.23"), true);
  strictEqual(versionAtMost("v0.19.54", "v1.0.23"), true);
  strictEqual(versionAtMost("v1.0.24", "v1.0.23"), false);
  strictEqual(versionAtMost("v1.1.0", "v1.0.23"), false);
  strictEqual(versionAtMost("v1.0.23-rc.1", "v1.0.23"), false, "a candidate name is no release");
});

test("a .distignore line is a path prefix, comments and blank lines are not", () => {
  deepStrictEqual(distignorePrefixes("# a comment\n\ndocs/agents/\n  test/  \nAGENTS.md\n"), ["docs/agents/", "test/", "AGENTS.md"]);
});

test("a path ships unless .distignore drops it or an upgrade replaces its folder wholesale", () => {
  const prefixes = ["docs/agents/", "AGENTS.md"];
  strictEqual(ships("skills/git/SKILL.md", prefixes), true);
  strictEqual(ships("docs/agents/testing.md", prefixes), false);
  strictEqual(ships("AGENTS.md", prefixes), false);
  strictEqual(ships("AGENTS.md.bak", prefixes), false, "the packager matches by prefix, so this goes too");
  for (const path of ["lib/x.jar", "framework/play.jar", "modules/docviewer/x", "precompiled/java/A.class",
    "public/spa/index.html", ".classpath", "server.pid"]) {
    strictEqual(ships(path, []), false, path);
  }
  strictEqual(ships("public/images/logo.png", []), true, "only public/spa is replaced wholesale");
});

test("the operator's paths are never retired, whatever a release once put there", () => {
  for (const path of ["workspace/main/AGENT.md", "workspace-test/x", "data/jclaw.mv.db", "logs/a.log", "certs/.env.example",
    "tmp/x", "app/controllers/A.java", "skills/gamma-presenter/credentials/gamma-api-key.txt"]) {
    strictEqual(retirable(path), false, path);
  }
  for (const path of ["COMMERCIAL-LICENSE.md", "skills/radarr-delete/SKILL.md", "skills/credentials.md", "workspaces.md"]) {
    strictEqual(retirable(path), true, path);
  }
});

test("the table sorts by path, then content, under its header", () => {
  strictEqual(render([["b", "2"], ["a", "9"], ["a", "1"]]).split("\n").filter((l) => !l.startsWith("#")).join("|"), "a\t1|a\t9|b\t2|");
});

test(`the table lists every file ${LAST_UNLISTED} shipped that this tree no longer ships, as ${LAST_UNLISTED} shipped it`, (t) => {
  if (git("rev-parse", "-q", "--verify", `${LAST_UNLISTED}^{commit}`).status !== 0) {
    t.skip(`no ${LAST_UNLISTED} tag in this clone`);
    return;
  }
  const prefixesThen = distignorePrefixes(git("cat-file", "-p", `${LAST_UNLISTED}:.distignore`).stdout.toString());
  const prefixesNow = distignorePrefixes(readFileSync(join(ROOT, ".distignore"), "utf8"));
  const shippedNow = new Set(git("ls-files", "-z").stdout.toString().split("\0").filter((p) => p && ships(p, prefixesNow)));
  const table = new Set(readFileSync(join(ROOT, TABLE), "utf8").split("\n").filter((l) => l && !l.startsWith("#")));

  const missing = [];
  for (const entry of git("ls-tree", "-r", "-z", "--full-tree", LAST_UNLISTED).stdout.toString().split("\0")) {
    const m = /^(100644|100755) blob ([0-9a-f]+)\t(.+)$/s.exec(entry);
    if (!m || shippedNow.has(m[3]) || !ships(m[3], prefixesThen) || !retirable(m[3])) continue;
    const sha = createHash("sha256").update(git("cat-file", "blob", m[2]).stdout).digest("hex");
    if (!table.has(`${m[3]}\t${sha}`)) missing.push(m[3]);
  }
  deepStrictEqual(missing, [], `${TABLE} is stale: run node bin/retired-release-files.mjs`);
  ok(table.size > 0);
});
