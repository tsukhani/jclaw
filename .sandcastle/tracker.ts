// What the factory needs from a story source: Jira, or the repository's GitHub issues. Each maps the factory's states
// (claimed, running, in review, blocked) onto what it has, Jira statuses or GitHub labels, and writes its own markup.

export type Snapshot = {
  key: string;
  summary: string;
  description: string;
  labels: string[];
  comments: { author: string; body: string }[];
  parent?: { key: string; summary: string; description: string };
  blockedBy: { key: string; status: string; done: boolean }[];
  updated: string;
  fetchedAt: string;
  // Why the source will not let this story run as it stands; the harness blocks it with this reason.
  refused?: string;
};

export type Markup = {
  code: (text: string) => string;
  heading: (text: string) => string;
  bullet: (text: string) => string;
  met: string;
  unmet: string;
};

export interface Tracker {
  readonly name: string;
  // Every factory comment starts with this; the reviewer's own comments never do.
  readonly header: string;
  readonly markup: Markup;
  owns(key: string): boolean;
  intake(): Promise<Snapshot[]>;
  inReview(): Promise<string[]>;
  orphaned(): Promise<string[]>;
  snapshotToState(key: string): Promise<Snapshot>;
  claim(key: string): Promise<boolean>;
  started(key: string): Promise<void>;
  reviewing(key: string): Promise<void>;
  blocked(key: string): Promise<void>;
  requeued(key: string): Promise<void>;
  comment(key: string, body: string): Promise<void>;
  addLabel(key: string, label: string): Promise<void>;
  // Extra lines for the merge commit, such as GitHub's "Closes #12".
  mergeMessage(key: string): string | undefined;
}

// A story back in the queue after the factory offered it for review was sent back: the reviewer's comments since the
// last offer are the feedback ("" when there are none), skipping the factory's own. undefined means it was never offered.
export const rejectionFeedback = (s: Snapshot, header: string): string | undefined => {
  let offer = -1;
  s.comments.forEach((c, i) => {
    if (c.body.startsWith(`${header}: ready for review`)) offer = i;
  });
  if (offer < 0) return undefined;
  return s.comments
    .slice(offer + 1)
    .filter((c) => !c.body.startsWith(header))
    .map((c) => `${c.author}: ${c.body}`)
    .join("\n\n");
};

// The factory declined this story earlier and it is back in intake, so the reviewer removed `wont-do`: build it as written.
export const overruled = (s: Snapshot, header: string): boolean => s.comments.some((c) => c.body.startsWith(`${header}: won't do`));

// Everything the agent should read, as one block for the prompt's {{DESCRIPTION}}.
export const promptContext = (s: Snapshot): string =>
  [
    s.description,
    s.comments.length ? `\n### Comments on the ticket\n${s.comments.map((c) => `- ${c.author}: ${c.body}`).join("\n")}` : "",
    s.parent ? `\n### Parent epic ${s.parent.key}: ${s.parent.summary}\n${s.parent.description}` : "",
  ].join("\n");
