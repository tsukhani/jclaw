// node --test bin/ghcr-prune.test.mjs
// Every case runs the real script with a `gh` and a `docker` on PATH that answer from a fixture, and asserts what
// the script deleted and printed.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = resolve(dirname(fileURLToPath(import.meta.url)), "ghcr-prune.mjs");

const FAKE_TOOL = `#!/usr/bin/env node
const { appendFileSync, readFileSync } = require("node:fs");
const { basename } = require("node:path");
const registry = JSON.parse(readFileSync(process.env.FAKE_REGISTRY, "utf8"));
const args = process.argv.slice(2);
if (basename(process.argv[1]) === "docker") {
  const digest = args.at(-1).split("@")[1];
  if (registry.failManifest === digest) process.exit(1);
  console.log(JSON.stringify(registry.manifests[digest]));
} else if (args[0] === "release") {
  if (registry.failReleases) process.exit(1);
  console.log(registry.releases.join("\\n"));
} else if (args.includes("DELETE")) {
  const id = Number(args.at(-1).split("/").at(-1));
  if (registry.failDelete === id) process.exit(1);
  appendFileSync(process.env.FAKE_DELETES, id + "\\n");
} else {
  for (const version of registry.versions) console.log(JSON.stringify(version));
}
`;

// One image is an index, tagged, and an untagged manifest per platform. `n` names its digests and ids.
function image(n, ...tags) {
  const digest = (part) => `sha256:${n}${part}`;
  return {
    versions: [
      { id: n * 10, name: digest("index"), tags },
      { id: n * 10 + 1, name: digest("arm64"), tags: [] },
      { id: n * 10 + 2, name: digest("amd64"), tags: [] },
    ],
    manifests: {
      [digest("index")]: { manifests: [{ digest: digest("amd64") }, { digest: digest("arm64") }] },
    },
  };
}

function registryOf(images, releases, overrides = {}) {
  return {
    versions: images.flatMap((one) => one.versions),
    manifests: Object.assign({}, ...images.map((one) => one.manifests)),
    releases,
    ...overrides,
  };
}

function prune(registry, ...args) {
  const dir = mkdtempSync(join(tmpdir(), "ghcr-prune-"));
  try {
    const tool = join(dir, "gh");
    writeFileSync(tool, FAKE_TOOL);
    chmodSync(tool, 0o755);
    const docker = join(dir, "docker");
    writeFileSync(docker, FAKE_TOOL);
    chmodSync(docker, 0o755);
    const fixture = join(dir, "registry.json");
    writeFileSync(fixture, JSON.stringify(registry));
    const deletes = join(dir, "deletes");
    const result = spawnSync(process.execPath, [SCRIPT, ...args], {
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: `${dir}${delimiter}${process.env.PATH}`,
        FAKE_REGISTRY: fixture,
        FAKE_DELETES: deletes,
      },
    });
    return {
      status: result.status,
      stdout: result.stdout,
      stderr: result.stderr,
      deleted: existsSync(deletes) ? readFileSync(deletes, "utf8").trim().split("\n").map(Number) : [],
    };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const fiveImages = () => [image(1, "v5", "latest"), image(2, "v4"), image(3, "v3"), image(4, "v2"), image(5, "v1")];
const FIVE_RELEASES = ["v5", "v4", "v3", "v2", "v1"];

test("every listed Release keeps its index and both platform manifests", () => {
  const result = prune(registryOf(fiveImages(), FIVE_RELEASES), "--apply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 0);
  strictEqual(result.stdout, "5 Releases and latest, 15 package versions.\nNothing to delete.\n");
});

test("an image no Release lists goes whole, and the last listed one stays", () => {
  const result = prune(registryOf([...fiveImages(), image(6, "v0")], FIVE_RELEASES), "--apply");
  deepStrictEqual(result.deleted, [60, 61, 62]);
  strictEqual(result.status, 0);
  strictEqual(
    result.stdout,
    "5 Releases and latest, 18 package versions.\n" +
      "Deleting 60 sha256:6index\nDeleting 61 sha256:6arm64\nDeleting 62 sha256:6amd64\n",
  );
});

test("latest survives when no Release lists it", () => {
  const result = prune(registryOf([image(1, "latest"), image(2, "v1")], ["v1"]), "--apply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 0);
});

test("an untagged manifest no image names is deleted", () => {
  const orphan = { versions: [{ id: 99, name: "sha256:orphan", tags: [] }], manifests: {} };
  const result = prune(registryOf([image(1, "v1", "latest"), orphan], ["v1"]), "--apply");
  deepStrictEqual(result.deleted, [99]);
  strictEqual(result.status, 0);
});

test("a platform manifest a deleted image shares with a kept one stays", () => {
  const kept = image(1, "v2", "latest");
  const old = {
    versions: [{ id: 20, name: "sha256:2index", tags: ["v1"] }],
    manifests: { "sha256:2index": { manifests: [{ digest: "sha256:1amd64" }] } },
  };
  const result = prune(registryOf([kept, old], ["v2"]), "--apply");
  deepStrictEqual(result.deleted, [20]);
  strictEqual(result.status, 0);
});

test("a Release whose image is already gone is reported and stops nothing", () => {
  const result = prune(registryOf([image(1, "v3", "latest"), image(2, "v0")], ["v3", "v2", "v1"]), "--apply");
  deepStrictEqual(result.deleted, [20, 21, 22]);
  strictEqual(result.status, 0);
  strictEqual(
    result.stdout,
    "3 Releases and latest, 6 package versions.\nNo image left for v2, v1.\n" +
      "Deleting 20 sha256:2index\nDeleting 21 sha256:2arm64\nDeleting 22 sha256:2amd64\n",
  );
});

test("a single-platform image, with no manifest list, keeps its own version", () => {
  const single = {
    versions: [{ id: 10, name: "sha256:single", tags: ["v1", "latest"] }],
    manifests: { "sha256:single": { schemaVersion: 2, config: {} } },
  };
  const result = prune(registryOf([single], ["v1"]), "--apply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 0);
});

test("no listed Release refuses, because nothing then says what to keep", () => {
  const result = prune(registryOf([image(1, "latest"), image(2, "v1")], []), "--apply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 1);
  strictEqual(result.stderr, "GHCR prune stopped: no Release is listed, so nothing says which images to keep\n");
});

test("a failed Release listing deletes nothing", () => {
  const result = prune(registryOf(fiveImages(), FIVE_RELEASES, { failReleases: true }), "--apply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 1);
});

test("a failed manifest lookup deletes nothing, not even what was already decided", () => {
  const images = [image(1, "v2", "latest"), image(2, "v1"), image(3, "v0")];
  const result = prune(registryOf(images, ["v2", "v1"], { failManifest: "sha256:2index" }), "--apply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 1);
});

test("a failed delete stops the run with a failing exit", () => {
  const images = [image(1, "v1", "latest"), image(2, "v0")];
  const result = prune(registryOf(images, ["v1"], { failDelete: 21 }), "--apply");
  deepStrictEqual(result.deleted, [20]);
  strictEqual(result.status, 1);
});

test("without --apply it names what it would delete and deletes nothing", () => {
  const result = prune(registryOf([image(1, "v1", "latest"), image(2, "v0")], ["v1"]));
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 0);
  strictEqual(
    result.stdout,
    "1 Release and latest, 6 package versions.\n" +
      "Would delete 20 sha256:2index\nWould delete 21 sha256:2arm64\nWould delete 22 sha256:2amd64\n",
  );
});

test("an unknown flag is refused before anything runs", () => {
  const result = prune(registryOf([image(1, "v1", "latest"), image(2, "v0")], ["v1"]), "--aply");
  deepStrictEqual(result.deleted, []);
  strictEqual(result.status, 1);
  strictEqual(result.stderr, "Usage: node bin/ghcr-prune.mjs [--apply]\n");
});
