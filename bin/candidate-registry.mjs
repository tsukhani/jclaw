#!/usr/bin/env node
// Deletes from the Jenkins host's candidate registry every candidate image whose build is no longer kept (JF-449).
// Contract and exit codes: bin/README.md.

import { fileURLToPath } from "node:url";

const REPO = "jclaw-candidate";
const TIMEOUT_MS = 30_000;
const SHORT_SHA = /^[0-9a-f]{12}$/;
const USAGE = "Usage: node bin/candidate-registry.mjs prune --keep <short sha>[,<short sha>…]";
// A multi-arch candidate is an image index; the Accept list names it so the registry answers with the index's digest.
const INDEX_TYPES = ["application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json",
  "application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json"].join(", ");

/** The tags to delete: every candidate tag but those the kept builds name. */
export function doomedTags(tags, keep) {
  return (tags ?? []).filter((tag) => !keep.includes(tag));
}

function parse(args) {
  if (args.length !== 3 || args[0] !== "prune" || args[1] !== "--keep") return null;
  const keep = args[2] === "" ? [] : args[2].split(",");
  return keep.every((sha) => SHORT_SHA.test(sha)) ? keep : null;
}

/** 0 when every doomed image is deleted or none is, 1 when the arguments are refused or a request fails. */
async function run(args, registry) {
  const keep = parse(args);
  if (!keep) return { code: 1, lines: [USAGE] };
  const call = (path, init = {}) => fetch(`${registry}/v2/${REPO}/${path}`,
    { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
  const lines = [];
  try {
    const listed = await call("tags/list");
    // A registry that never took a candidate does not know the repository yet.
    if (listed.status === 404) return { code: 0, lines: ["No candidate image to prune."] };
    if (!listed.ok) return { code: 1, lines: [`The candidate registry answered ${listed.status} for its tags`] };
    const doomed = doomedTags((await listed.json()).tags, keep);
    if (doomed.length === 0) return { code: 0, lines: ["No candidate image to prune."] };
    for (const tag of doomed) {
      const head = await call(`manifests/${tag}`, { method: "HEAD", headers: { Accept: INDEX_TYPES } });
      const digest = head.headers.get("docker-content-digest");
      if (!head.ok || !digest) {
        lines.push(`The candidate registry answered ${head.status} for ${tag}, with no digest to delete`);
        return { code: 1, lines };
      }
      const deleted = await call(`manifests/${digest}`, { method: "DELETE" });
      if (deleted.status !== 202) {
        lines.push(`The candidate registry answered ${deleted.status} to deleting ${tag} (${digest})`);
        return { code: 1, lines };
      }
      lines.push(`Deleted ${tag} ${digest}`);
    }
    return { code: 0, lines };
  } catch (error) {
    lines.push(`The candidate registry could not be asked: ${error.message}`);
    return { code: 1, lines };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const registry = process.env.JCLAW_CANDIDATE_REGISTRY ?? "http://127.0.0.1:5000";
  const { code, lines } = await run(process.argv.slice(2), registry);
  (code === 0 ? process.stdout : process.stderr).write(`${lines.join("\n")}\n`);
  process.exit(code);
}
