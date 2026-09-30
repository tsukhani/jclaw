import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

// The harness's code lives in the repo; everything it writes lives in FACTORY_HOME, outside any checkout, because
// /deploy stages the whole working tree.
export const HERE = import.meta.dirname;
export const REPO_ROOT = path.resolve(HERE, "..");
export const FACTORY_HOME = process.env.FACTORY_HOME ?? path.join(os.homedir(), ".jclaw-factory");
export const CLONE = path.join(FACTORY_HOME, "jclaw");
export const LOGS = path.join(FACTORY_HOME, "logs");
export const STATE = path.join(FACTORY_HOME, "state");
export const ENV_FILE = path.join(FACTORY_HOME, ".env");
export const JIRA_ENV_FILE = path.join(FACTORY_HOME, "jira.env");
export const SETTINGS_FILE = path.join(FACTORY_HOME, "settings.env");

// KEY=VALUE lines; blank lines and `#` comments are skipped.
export const readEnvFile = (file: string): Record<string, string> =>
  fs.existsSync(file)
    ? Object.fromEntries(
        fs.readFileSync(file, "utf8").split("\n").map((l) => l.trim()).filter((l) => l.includes("=") && !l.startsWith("#"))
          .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
      )
    : {};

// launchd passes the harness no shell environment, so the operator's settings live in a file. They load here because
// every entry point imports this module before any module that reads a setting; a variable already set wins.
// FACTORY_TICKET and FACTORY_PLAN_ONLY stay out: either one makes a supervised harness exit after one round.
const SETTINGS = ["FACTORY_MAX_PARALLEL", "FACTORY_POLL_SECONDS", "FACTORY_MODEL", "FACTORY_CPUS"];
for (const [key, value] of Object.entries(readEnvFile(SETTINGS_FILE))) {
  if (!SETTINGS.includes(key)) throw new Error(`${SETTINGS_FILE}: ${key} is not a setting it can hold (${SETTINGS.join(", ")})`);
  process.env[key] ??= value;
}
