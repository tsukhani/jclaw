#!/usr/bin/env node
// Deletes the GHCR package versions no listed Release needs, so every signed IMAGE_DIGEST stays pullable.
// Contract: bin/README.md.

import { execFileSync } from "node:child_process";

const USAGE = "Usage: node bin/ghcr-prune.mjs [--apply]";
const REPO = "tsukhani/jclaw";
const IMAGE = "ghcr.io/tsukhani/jclaw";
const PACKAGE_VERSIONS = "packages/container/jclaw/versions";
const MAX_OUTPUT = 64 * 1024 * 1024;

const tool = (name, ...args) =>
  execFileSync(name, args, { encoding: "utf8", maxBuffer: MAX_OUTPUT, stdio: ["ignore", "pipe", "inherit"] });
const lines = (text) => text.split("\n").filter(Boolean);

function releaseTags() {
  return lines(tool("gh", "release", "list", "--repo", REPO, "--limit", "100", "--json", "tagName", "--jq", ".[].tagName"));
}

function packageVersions() {
  const fields = ".[] | {id, name, tags: .metadata.container.tags}";
  const output = tool("gh", "api", "--paginate", `/users/tsukhani/${PACKAGE_VERSIONS}?per_page=100`, "--jq", fields);
  return lines(output).map((line) => JSON.parse(line));
}

function platformManifests(digest) {
  const raw = tool("docker", "buildx", "imagetools", "inspect", "--raw", `${IMAGE}@${digest}`);
  return (JSON.parse(raw).manifests ?? []).map((manifest) => manifest.digest);
}

function plan(versions, tags) {
  const keep = new Set();
  const missing = [];
  for (const tag of [...tags, "latest"]) {
    const index = versions.find((version) => version.tags.includes(tag));
    if (!index) {
      missing.push(tag);
      continue;
    }
    keep.add(index.name);
    // Each platform manifest is an untagged package version of its own, and deleting one breaks this image.
    for (const digest of platformManifests(index.name)) keep.add(digest);
  }
  return { missing, doomed: versions.filter((version) => !keep.has(version.name)) };
}

function main(args) {
  const apply = args[0] === "--apply";
  if (args.length > 1 || (args.length === 1 && !apply)) {
    console.error(USAGE);
    process.exit(1);
  }
  const tags = releaseTags();
  if (tags.length === 0) throw new Error("no Release is listed, so nothing says which images to keep");
  const versions = packageVersions();
  const { missing, doomed } = plan(versions, tags);
  console.log(`${tags.length} Release${tags.length === 1 ? "" : "s"} and latest, ${versions.length} package versions.`);
  if (missing.length > 0) console.log(`No image left for ${missing.join(", ")}.`);
  if (doomed.length === 0) console.log("Nothing to delete.");
  for (const version of doomed) {
    console.log(`${apply ? "Deleting" : "Would delete"} ${version.id} ${version.name}`);
    if (apply) tool("gh", "api", "-X", "DELETE", `/user/${PACKAGE_VERSIONS}/${version.id}`);
  }
}

try {
  main(process.argv.slice(2));
} catch (error) {
  console.error(`GHCR prune stopped: ${error.message}`);
  process.exit(1);
}
