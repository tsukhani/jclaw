// The brief the review agent writes for the human reviewer, the pre-existing defects it reports and their filing, and
// the review-comment section the harness renders from them.
import { z } from "zod";
import type { Markup, Snapshot } from "./tracker.ts";

// `preExisting` defaults to empty so a brief written to the older shape still validates rather than costing a resume.
export const Brief = z.object({
  summary: z.string(),
  acceptanceCriteria: z.array(z.object({ criterion: z.string(), met: z.boolean(), evidence: z.string() })),
  decisions: z.array(z.string()),
  risks: z.array(z.string()),
  preExisting: z.array(z.object({ location: z.string(), defect: z.string() })).default([]),
  testsRun: z.array(z.string()),
});
export type Brief = z.infer<typeof Brief>;
export type Finding = Brief["preExisting"][number];

// A filed finding carries no `afk` and no sprint, so intake never takes it until the operator plans it into one.
export const FOUND_LABEL = "factory-found";

// The summary is how a later review recognizes a location already filed. Jira caps a summary at 255 characters.
const SUMMARY_PREFIX = "Pre-existing defect at ";
export const findingSummary = (f: Finding): string => {
  const s = `${SUMMARY_PREFIX}${f.location}: ${f.defect}`;
  return s.length <= 255 ? s : `${s.slice(0, 254)}…`;
};
export const findingLocation = (summary: string): string | undefined =>
  summary.startsWith(SUMMARY_PREFIX) ? summary.slice(SUMMARY_PREFIX.length).match(/^(\S+?): /)?.[1] : undefined;

export type FindingsTracker = {
  // Location to key, for every story ever filed with FOUND_LABEL, Done ones included: a defect the operator closed
  // as not worth fixing is not filed again.
  filedFindings(): Promise<Map<string, string>>;
  fileFinding(source: Snapshot, f: Finding): Promise<string>;
};
export type Filed = Finding & { filed?: string; already?: boolean; error?: string };

const reason = (e: unknown) => (e instanceof Error ? e.message : String(e)).split("\n")[0];

// Files each finding once per location. A failure leaves that finding unfiled and the story unharmed.
export const fileFindings = async (t: FindingsTracker, source: Snapshot, found: Finding[]): Promise<Filed[]> => {
  if (found.length === 0) return [];
  let known: Map<string, string>;
  try {
    known = await t.filedFindings();
  } catch (e) {
    return found.map((f) => ({ ...f, error: reason(e) }));
  }
  const out: Filed[] = [];
  for (const f of found) {
    const prior = known.get(f.location);
    if (prior) {
      out.push({ ...f, filed: prior, already: true });
      continue;
    }
    try {
      const key = await t.fileFinding(source, f);
      known.set(f.location, key);
      out.push({ ...f, filed: key });
    } catch (e) {
      out.push({ ...f, error: reason(e) });
    }
  }
  return out;
};

export const preExistingSection = (found: Filed[], m: Markup): string[] => {
  if (found.length === 0) return [];
  const status = (d: Filed) => (d.error ? `not filed: ${d.error}` : `${d.already ? "already filed as" : "filed as"} ${d.filed}`);
  const lines = ["", m.heading("Pre-existing, left unchanged"), ...found.map((d) => m.bullet(`${m.code(d.location)}: ${d.defect} (${status(d)})`)),
    `The branch did not introduce these, so they do not block it. Each is filed as a story labelled ${m.code(FOUND_LABEL)}, outside any sprint, so the factory builds none until you plan it into one.`];
  if (found.some((d) => d.error)) lines.push("File the ones marked not filed by hand.");
  return lines;
};
