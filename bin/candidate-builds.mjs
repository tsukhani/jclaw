#!/usr/bin/env node
// The green and the kept /release-check candidate builds, read from the build records on the Jenkins host, for the
// Jenkinsfile: its sandbox cannot reach another job's builds, and candidates build in a job of their own (JF-453).
// Contract and exit codes: bin/README.md.

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { JOBS } from "./candidate-keep.mjs";
import { testedCommit, vouchingBuild } from "./candidate-vouch.mjs";

const SHA = /^[0-9a-f]{40}$/;
const VERSION = /^\d+\.\d+\.\d+$/;
const ENTITIES = { lt: "<", gt: ">", amp: "&", quot: "\"", apos: "'" };
const FIELDS = new Set(["result", "description", "displayName", "keepLog"]);

const decode = (text) => text.replace(/&(lt|gt|amp|quot|apos);/g, (_, name) => ENTITIES[name]);

/**
 * The fields of a build record (its build.xml) that say what the build was: only the root element's own children,
 * because the parameter definitions the record nests carry descriptions of their own. A build still running has no
 * result yet, so it reads as building.
 */
export function buildRecord(xml) {
  const record = { result: null, description: null, displayName: null, keepLog: false };
  let depth = 0;
  let open = null;
  for (const tag of xml.matchAll(/<(\/?)([A-Za-z_][\w.-]*)[^>]*?(\/?)>|<[?!][^>]*>/g)) {
    if (!tag[2]) continue;
    if (tag[1]) {
      depth--;
      if (open && depth === 1 && tag[2] === open.name) {
        const text = decode(xml.slice(open.from, tag.index));
        record[open.name] = open.name === "keepLog" ? text === "true" : text;
        open = null;
      }
    } else if (!tag[3]) {
      depth++;
      if (depth === 2 && FIELDS.has(tag[2])) open = { name: tag[2], from: tag.index + tag[0].length };
    }
  }
  return { ...record, building: record.result === null };
}

/**
 * Each job's build records, newest first, the candidate job's ahead of the older one's, or null when no job's builds
 * could be listed: an empty answer then would read as "nothing kept", and Cleanup would prune every candidate image.
 */
function records(home) {
  let listed = 0;
  const all = JOBS.flatMap((job) => {
    let numbers;
    try {
      numbers = readdirSync(join(home, "jobs", job, "builds")).filter((n) => /^\d+$/.test(n)).map(Number);
      listed++;
    } catch {
      return [];
    }
    return numbers.sort((a, b) => b - a).flatMap((number) => {
      try {
        return [{ job, number, ...buildRecord(readFileSync(join(home, "jobs", job, "builds", `${number}`, "build.xml"), "utf8")) }];
      } catch {
        return [];
      }
    });
  });
  return listed > 0 ? all : null;
}

/**
 * The first twelve characters of each kept candidate's commit, which the registry tags its image with, less
 * {@code except}: the candidate a release build has just copied to GHCR, still kept until /deploy drops it.
 */
export function keptCandidates(builds, except) {
  return builds.filter((b) => b.keepLog).map((b) => testedCommit(b, b.job))
    .filter((sha) => sha && sha !== except).map((sha) => sha.slice(0, 12));
}

/**
 * The rc number the next candidate of {@code version} takes: one past the highest that version's candidate builds
 * were named, failed ones too, so a number is never reused once a build has shown it (JF-465).
 */
export function nextRc(builds, version) {
  const named = new RegExp(`^v${version.replaceAll(".", "\\.")}-rc\\.(\\d+)$`);
  return 1 + Math.max(0, ...builds.filter((b) => b.job === JOBS[0])
    .map((b) => Number((b.displayName ?? "").match(named)?.[1] ?? 0)));
}

/** 0 and the answer, 1 when no green candidate tested the commit, 2 when the records cannot be read. */
export function run(args, home) {
  const kept = args[0] === "kept" && (args.length === 1 || (args.length === 3 && args[1] === "--except" && SHA.test(args[2])));
  const green = args[0] === "green" && args.length === 2 && SHA.test(args[1]);
  const rc = args[0] === "next-rc" && args.length === 2 && VERSION.test(args[1]);
  if (!kept && !green && !rc) return { code: 2, out: "give green <full sha>, kept [--except <full sha>], or next-rc <x.y.z>" };
  if (!home) return { code: 2, out: "JENKINS_HOME is not set, so there are no build records to read" };
  const builds = records(home);
  if (!builds) return { code: 2, out: `no builds of ${JOBS.join(" or ")} under ${home}/jobs` };
  if (kept) return { code: 0, out: keptCandidates(builds, args[2]).join(",") };
  if (rc) return { code: 0, out: `${nextRc(builds, args[1])}` };
  const build = vouchingBuild(builds, args[1]);
  return build ? { code: 0, out: `${build.job} ${build.number}` } : { code: 1, out: `no green candidate build tested ${args[1]}` };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { code, out } = run(process.argv.slice(2), process.env.JENKINS_HOME);
  (code === 0 ? process.stdout : process.stderr).write(`${out}\n`);
  process.exit(code);
}
