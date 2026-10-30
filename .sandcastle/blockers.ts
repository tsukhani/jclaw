// The open stories the factory will not build that keep a candidate waiting, directly or through another such story, for
// the board's `blockers`. Pure apart from `fetch`, so the offline checks drive it with a fake.
import type { BoardBlocker } from "./board.ts";
import type { Blocker } from "./tracker.ts";

// Stories fetched per walk at most. Each fetch may add a second request, for its epic's labels, so a walk makes at most
// twice this many requests.
export const BLOCKER_LIMIT = 50;

// The list the board shows after a poll: the walk's, or the last one when the walk failed or a source went unread, since
// an unread source contributed no candidates and the walk would drop its stories' rows. `failure` is what the walk threw.
export const nextBlockers = async (
  last: BoardBlocker[],
  sourceUnread: boolean,
  walk: () => Promise<BoardBlocker[]>,
): Promise<{ blockers: BoardBlocker[]; failure?: unknown }> => {
  if (sourceUnread) return { blockers: last };
  try {
    return { blockers: await walk() };
  } catch (failure) {
    return { blockers: last, failure };
  }
};

// Breadth first from the candidates in rank order. A candidate or a key `known` names (one the board holds) is never
// fetched, each other key is fetched once, so a cycle ends, and a Done story is left out with its own blockers. A fetch
// that throws rejects the walk, for the caller to keep its previous list; one that returns undefined has no source.
export const outsideBlockers = async (
  candidates: { key: string; blockedBy: { key: string; done: boolean }[] }[],
  known: (key: string) => boolean,
  fetch: (key: string) => Promise<Blocker | undefined>,
  limit = BLOCKER_LIMIT,
): Promise<BoardBlocker[]> => {
  const seen = new Set(candidates.map((c) => c.key));
  const unseen = (keys: string[]) => {
    const out: string[] = [];
    for (const k of keys) {
      if (seen.has(k) || known(k)) continue;
      seen.add(k);
      out.push(k);
    }
    return out;
  };
  const found: BoardBlocker[] = [];
  let level = unseen(candidates.flatMap((c) => c.blockedBy.filter((b) => !b.done).map((b) => b.key)));
  let fetched = 0;
  while (level.length > 0 && fetched < limit) {
    const batch = level.slice(0, limit - fetched);
    fetched += batch.length;
    const open = (await Promise.all(batch.map(fetch))).filter((b): b is Blocker => b !== undefined && !b.done);
    for (const { done: _, ...b } of open) found.push(b);
    level = unseen(open.flatMap((b) => b.blockedBy));
  }
  return found;
};
