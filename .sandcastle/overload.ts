// A phase whose Claude Code run gave up on a model API overload is requeued with a backoff instead of blocked: the API
// is up, the overload just landed on that phase. The count lives in a file so a harness restart cannot reset it.
import * as fs from "node:fs";
import { writeAtomically } from "./board.ts";

export const MAX_OVERLOADS = 3;
export const BACKOFF_MS = 10 * 60 * 1000;

// Sandcastle appends each run to the phase's log after this line; only the last run says why it ended.
const RUN_STARTED = "--- Run started: ";
// Claude Code's final line when its own retries ran out: "API Error: Repeated 529 Overloaded errors", "API Error: 503 …",
// or a body naming overloaded_error or rate_limit_error.
const TRANSIENT = /^API Error\b.*?(\b(429|500|502|503|504|529)\b|overloaded|rate.?limit)/i;

// The line that shows the log's last run ended on a transient model-API failure, or undefined. It is cut before any
// JSON body, whose braces would break Jira's {{code}} markup in the comment.
export const transientApiFailure = (log: string): string | undefined => {
  const lastRun = log.slice(Math.max(0, log.lastIndexOf(RUN_STARTED)));
  const line = lastRun.split("\n").map((l) => l.trim()).filter(Boolean).slice(-10).find((l) => TRANSIENT.test(l));
  return line?.split("{")[0].trim().slice(0, 200);
};

// Ten minutes after the first overload, doubling with each consecutive one.
export const backoffMs = (count: number) => BACKOFF_MS * 2 ** (count - 1);

// Only an agent phase completing resets the count: a gate passes between a requeue and the agent phase that overloaded.
export const resetsOverloads = (phase: string) => !phase.startsWith("gate-");

export const overloadReason = (until: Date) => `the model API was overloaded; eligible again at ${until.toISOString()}`;

// What a failed phase's log means for its story: undefined blocks it as any failure does; a verdict counts an overload.
export const afterFailure = (overloads: Overloads, key: string, log: string | undefined, now: Date): { line: string; verdict: Verdict } | undefined => {
  const line = log === undefined ? undefined : transientApiFailure(log);
  return line === undefined ? undefined : { line, verdict: overloads.failed(key, now) };
};

export type Verdict = { requeue: true; count: number; until: Date } | { requeue: false; count: number };
type Entry = { count: number; until: string };

export class Overloads {
  constructor(private readonly file: string) {}

  private read(): Record<string, Entry> {
    try {
      return JSON.parse(fs.readFileSync(this.file, "utf8"));
    } catch {
      return {};
    }
  }

  private save(entries: Record<string, Entry>) {
    writeAtomically(this.file, `${JSON.stringify(entries, null, 2)}\n`);
  }

  // One more consecutive overload: requeue until the backoff passes, or block at the last one and forget the count.
  failed(key: string, now: Date): Verdict {
    const entries = this.read();
    const count = (entries[key]?.count ?? 0) + 1;
    if (count >= MAX_OVERLOADS) {
      delete entries[key];
      this.save(entries);
      return { requeue: false, count };
    }
    const until = new Date(now.getTime() + backoffMs(count));
    entries[key] = { count, until: until.toISOString() };
    this.save(entries);
    return { requeue: true, count, until };
  }

  // A phase completed, so the next overload is the first again.
  completed(key: string) {
    const entries = this.read();
    if (!(key in entries)) return;
    delete entries[key];
    this.save(entries);
  }

  // When the story becomes eligible again, while it is still waiting out a backoff.
  holding(key: string, now: Date): Date | undefined {
    const until = this.read()[key]?.until;
    return until && Date.parse(until) > now.getTime() ? new Date(until) : undefined;
  }

  // The stories a round may start now, and those still waiting out a backoff.
  split<T extends { key: string }>(stories: T[], now: Date): { ready: T[]; held: { story: T; until: Date }[] } {
    const ready: T[] = [], held: { story: T; until: Date }[] = [];
    for (const story of stories) {
      const until = this.holding(story.key, now);
      if (until) held.push({ story, until });
      else ready.push(story);
    }
    return { ready, held };
  }
}
