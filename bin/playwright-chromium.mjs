#!/usr/bin/env node
// Whether the Chromium build the Java Playwright pins is installed, for ./jclaw.sh test's live-browser gate (JF-452).
// Contract and exit codes: bin/README.md.

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// services.browser.BrowserSetup.chromiumInstalledAt's rule, which the browser tool installs by: `install chromium`
// fetches these three, and each finished one leaves INSTALLATION_COMPLETE in <name, - as _>-<revision>/.
const CHROMIUM_SET = ["chromium", "chromium-headless-shell", "ffmpeg"];
const BROWSERS_JSON = "driver/package/browsers.json";

/** The Playwright driver version gradle.lockfile resolves, which is the jar holding browsers.json; null when none. */
export function lockedDriverVersion(lockfile) {
  return /^com\.microsoft\.playwright:driver:([^=\s]+)=/m.exec(lockfile ?? "")?.[1] ?? null;
}

/** The revisions browsers.json pins, by browser name. */
export function pinnedRevisions(browsersJson) {
  return Object.fromEntries((JSON.parse(browsersJson).browsers ?? []).map((b) => [b.name, b.revision]));
}

/**
 * Playwright's browsers directory, as BrowserSetup.browsersPath finds it on macOS and Linux: PLAYWRIGHT_BROWSERS_PATH,
 * else the per-OS cache. Null for "0", which keeps the browsers inside the driver package.
 */
export function browsersPath(env, platform, home) {
  const explicit = env.PLAYWRIGHT_BROWSERS_PATH;
  if (explicit?.trim()) return explicit === "0" ? null : explicit;
  if (platform === "darwin") return join(home, "Library", "Caches", "ms-playwright");
  return join(env.XDG_CACHE_HOME?.trim() ? env.XDG_CACHE_HOME : join(home, ".cache"), "ms-playwright");
}

/** The pinned build's folders that hold no INSTALLATION_COMPLETE marker: empty when the build is installed. */
export function missingFolders(root, revisions, exists = existsSync) {
  return CHROMIUM_SET.map((name) => `${name.replaceAll("-", "_")}-${revisions[name]}`)
    .filter((folder) => !exists(join(root, folder, "INSTALLATION_COMPLETE")));
}

/** The headless-shell revisions a cache holds complete, in numeric order, to name what it has instead. */
export function cachedRevisions(root, readdir = readdirSync, exists = existsSync) {
  let names;
  try {
    names = readdir(root);
  } catch {
    return [];
  }
  return names.filter((n) => exists(join(root, n, "INSTALLATION_COMPLETE")))
    .map((n) => /^chromium_headless_shell-(\d+)$/.exec(n)?.[1]).filter(Boolean)
    .sort((a, b) => Number(a) - Number(b));
}

function driverJar(gradleHome, version) {
  const dir = join(gradleHome, "caches", "modules-2", "files-2.1", "com.microsoft.playwright", "driver", version);
  let hashes;
  try {
    hashes = readdirSync(dir);
  } catch {
    return null;
  }
  return hashes.map((h) => join(dir, h, `driver-${version}.jar`)).find((jar) => existsSync(jar)) ?? null;
}

/** 0 when the pinned build is installed, 1 when it is not, 2 when it cannot tell; the message says which. */
export function check(root, env, platform, home) {
  let lockfile;
  try {
    lockfile = readFileSync(join(root, "gradle.lockfile"), "utf8");
  } catch {
    return { code: 2, message: `no gradle.lockfile in ${root}` };
  }
  const version = lockedDriverVersion(lockfile);
  if (!version) return { code: 2, message: "gradle.lockfile names no Playwright driver" };
  const jar = driverJar(env.GRADLE_USER_HOME?.trim() ? env.GRADLE_USER_HOME : join(home, ".gradle"), version);
  if (!jar) return { code: 2, message: `Playwright ${version}'s driver jar is not in the Gradle cache` };
  const unzip = spawnSync("unzip", ["-p", jar, BROWSERS_JSON], { encoding: "utf8" });
  if (unzip.status !== 0) return { code: 2, message: `unzip could not read ${BROWSERS_JSON} from ${jar}` };
  let revisions;
  try {
    revisions = pinnedRevisions(unzip.stdout);
  } catch {
    return { code: 2, message: `${BROWSERS_JSON} in ${jar} is not JSON` };
  }
  const unnamed = CHROMIUM_SET.filter((name) => !revisions[name]);
  if (unnamed.length) return { code: 2, message: `Playwright ${version}'s browsers.json pins no ${unnamed.join(", ")}` };
  const cache = browsersPath(env, platform, home);
  if (!cache) return { code: 2, message: "PLAYWRIGHT_BROWSERS_PATH=0 keeps the browsers inside the driver package" };
  const build = `Playwright ${version}'s Chromium ${revisions.chromium}`;
  if (missingFolders(cache, revisions).length === 0) return { code: 0, message: `${build} is installed in ${cache}` };
  const held = cachedRevisions(cache);
  return { code: 1, message: `${build} is not installed in ${cache}`
    + (held.length ? ` (it holds ${held.join(", ")})` : "") };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const { code, message } = check(root, process.env, process.platform, homedir());
  process.stdout.write(`${message}\n`);
  process.exit(code);
}
