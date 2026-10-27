#!/usr/bin/env node
// Writes docs/user-guide/llms-full.txt: the guide's llms.txt, then every page it lists, in its order.
// The output is gitignored, so wherever the guide is published runs this rather than reading a copy.

import { readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const GUIDE_DIR = resolve(fileURLToPath(import.meta.url), "..", "..", "docs", "user-guide");

const PAGE_LINK = /^- \[[^\]]+\]\(([^)]+)\)/gm;

/** The whole guide in `guideDir` as one document; throws on a page llms.txt lists but the folder lacks. */
export function buildLlmsFull(guideDir) {
  const index = readFileSync(join(guideDir, "llms.txt"), "utf8");
  const pages = [...index.matchAll(PAGE_LINK)].map(([, page]) =>
    `---\n\nSource: ${page}\n\n${readFileSync(join(guideDir, page), "utf8").trimEnd()}`);
  return [index.trimEnd(), ...pages].join("\n\n") + "\n";
}

if (import.meta.main) {
  const out = join(GUIDE_DIR, "llms-full.txt");
  writeFileSync(out, buildLlmsFull(GUIDE_DIR));
  console.log(out);
}
