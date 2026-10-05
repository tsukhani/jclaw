// FACTORY_HOME/board.json: the factory's state for readers outside the harness, such as JClaw's Software Factory panel.
// README.md's "Board" section is the contract. It names stories and states only, never ticket text or a credential.
import * as fs from "node:fs";
import * as path from "node:path";

export const BOARD_SCHEMA = 1;
// A merged or blocked story stays this long, so the file stays small.
export const RETENTION_MS = 14 * 24 * 60 * 60 * 1000;

export type State =
  | { state: "waiting"; reason: string }
  | { state: "running"; phase: string }
  | { state: "review" }
  | { state: "blocked"; reason: string }
  | { state: "refused"; reason: string }
  | { state: "merged"; sha: string | null; by: "factory" | "operator" };

type Shown = Exclude<State, { state: "running" }> | { state: "running"; phase: string; phaseStartedAt: string };
export type About = { summary: string; autoMerge: boolean };
export type Entry = { key: string; summary: string; source: "jira" | "github"; autoMerge: boolean; since: string } & Shown;
export type Story = Entry & { logs: string[] };

export type Settings = { FACTORY_MAX_PARALLEL: number; FACTORY_CPUS: number; FACTORY_POLL_SECONDS: number; FACTORY_MODEL: string };
export type Document = {
  schema: number;
  updatedAt: string;
  harness: { pid: number; startedAt: string; main: string | null };
  settings: Settings;
  stories: Story[];
};

// The story or its epic carries `afk-merge`, and `no-afk-merge` does not exempt it.
export const autoMerges = (s: { labels: string[]; parent?: { labels: string[] } }): boolean =>
  !s.labels.includes("no-afk-merge") && (s.labels.includes("afk-merge") || Boolean(s.parent?.labels.includes("afk-merge")));

const sourceOf = (key: string): Entry["source"] => (/^GH-\d+$/.test(key) ? "github" : "jira");

// The entry after `next`, or undefined when nothing a reader sees has changed. `since` moves only with the state, and
// `phaseStartedAt` only with the phase.
export const transition = (entry: Entry | undefined, key: string, next: State, now: Date, about?: Partial<About>): Entry | undefined => {
  const at = now.toISOString();
  const shown: Shown = next.state === "running"
    ? { ...next, phaseStartedAt: entry?.state === "running" && entry.phase === next.phase ? entry.phaseStartedAt : at }
    : next;
  const since = entry?.state === next.state ? entry.since : at;
  const after: Entry = {
    key,
    summary: about?.summary ?? entry?.summary ?? "",
    source: sourceOf(key),
    autoMerge: about?.autoMerge ?? entry?.autoMerge ?? false,
    ...shown,
    since,
  };
  return entry && JSON.stringify(entry) === JSON.stringify(after) ? undefined : after;
};

// Merged and blocked are the factory's last word on a story; past retention it leaves the board.
export const expired = (entry: Entry, now: Date): boolean =>
  (entry.state === "merged" || entry.state === "blocked") && now.getTime() - Date.parse(entry.since) >= RETENTION_MS;

// The temporary file sits beside the target, so the rename is atomic and a reader sees the old file or the new one.
export const writeAtomically = (file: string, text: string) => {
  const temporary = path.join(path.dirname(file), `.${path.basename(file)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, text);
  fs.renameSync(temporary, file);
};

export class Board {
  // Oldest change first: a change re-inserts its story at the end.
  private readonly entries = new Map<string, Entry>();
  main: string | undefined;

  constructor(
    private readonly opts: { file: string; logs: string; pid: number; startedAt: Date; settings: Settings; enabled: boolean },
  ) {}

  // The previous run's board, so merged and blocked stories keep their retention across restarts. A story it left
  // running was interrupted: a landing is back in review, and anything else waits to be resumed.
  load() {
    let previous: Document;
    try {
      previous = JSON.parse(fs.readFileSync(this.opts.file, "utf8"));
    } catch {
      return;
    }
    if (previous.schema !== BOARD_SCHEMA || !Array.isArray(previous.stories)) return;
    for (const { logs: _, ...entry } of [...previous.stories].reverse()) {
      if (entry.state !== "running") this.entries.set(entry.key, entry);
      else {
        const { phase: _phase, phaseStartedAt: _started, ...rest } = entry;
        const landing = entry.phase === "merge" || entry.phase === "gate-merge";
        const since = new Date().toISOString();
        this.entries.set(entry.key, { ...rest, since, ...(landing ? { state: "review" } : { state: "waiting", reason: "interrupted by the last stop" }) });
      }
    }
  }

  has(key: string) {
    return this.entries.has(key);
  }

  keys(...states: State["state"][]): string[] {
    return [...this.entries.values()].filter((e) => states.includes(e.state)).map((e) => e.key);
  }

  set(key: string, next: State, about?: Partial<About>, now = new Date()) {
    const after = transition(this.entries.get(key), key, next, now, about);
    if (!after) return;
    this.entries.delete(key);
    this.entries.set(key, after);
    this.write(now);
  }

  // The story's description changed, not its state: it keeps its place.
  describe(key: string, about: Partial<About>) {
    const entry = this.entries.get(key);
    if (entry) this.entries.set(key, { ...entry, ...about });
  }

  remove(key: string) {
    if (this.entries.delete(key)) this.write();
  }

  document(now = new Date()): Document {
    for (const entry of this.entries.values()) if (expired(entry, now)) this.entries.delete(entry.key);
    let files: string[] = [];
    try {
      files = fs.readdirSync(this.opts.logs).sort();
    } catch {
      // No logs directory yet: no story has logs.
    }
    return {
      schema: BOARD_SCHEMA,
      updatedAt: now.toISOString(),
      harness: { pid: this.opts.pid, startedAt: this.opts.startedAt.toISOString(), main: this.main ?? null },
      settings: this.opts.settings,
      stories: [...this.entries.values()].reverse().map((e) => ({ ...e, logs: files.filter((f) => f.startsWith(`${e.key}-`)) })),
    };
  }

  // A board that cannot be written never stops the factory.
  write(now = new Date()) {
    if (!this.opts.enabled) return;
    try {
      writeAtomically(this.opts.file, `${JSON.stringify(this.document(now), null, 2)}\n`);
    } catch (error) {
      console.log(`[factory] could not write ${this.opts.file}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}
