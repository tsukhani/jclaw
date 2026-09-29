// Two stories that change the same file conflict at merge time even when each passes its own gate. In board order, a
// story is picked unless its files meet those of a branch awaiting review (`owners`) or of a story picked before it.
// A story whose files are unknown (absent from `files`) runs only when nothing else is in flight, and then alone.
export const pickNonOverlapping = <T extends { key: string }>(
  stories: T[],
  files: Map<string, Set<string>>,
  inFlight: Map<string, string>,
): { picked: T[]; deferred: { key: string; reason: string }[] } => {
  const owners = new Map(inFlight);
  const picked: T[] = [];
  const deferred: { key: string; reason: string }[] = [];
  let alone = false;
  for (const story of stories) {
    const mine = files.get(story.key);
    if (alone || (mine === undefined && (owners.size > 0 || picked.length > 0))) {
      deferred.push({ key: story.key, reason: "its files are unknown, so it runs only when nothing else is in flight" });
      continue;
    }
    if (mine === undefined) {
      picked.push(story);
      alone = true;
      continue;
    }
    const clash = [...mine].filter((f) => owners.has(f));
    if (clash.length > 0) {
      deferred.push({ key: story.key, reason: clash.map((f) => `${f} is changed by ${owners.get(f)}`).join("; ") });
      continue;
    }
    picked.push(story);
    for (const f of mine) owners.set(f, `${story.key} (this run)`);
  }
  return { picked, deferred };
};
