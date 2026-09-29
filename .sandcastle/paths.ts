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
