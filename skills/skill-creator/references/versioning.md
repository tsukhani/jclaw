# Setting a skill's version by hand

Leave the `version` entry out by default: the filesystem tool auto-bumps the patch component on every material write, and new skills start at `1.0.0`.

**Set an explicit `version` only when the user asks to promote the skill forward** — e.g., "bump to v1.0.0", "promote to 1.1", "this is a breaking change, make it 2.0.0". In that case, add `version: "X.Y.Z"` under `metadata` with your intended target. The value MUST be strictly greater than the next auto-bump target; anything lower or invalid is silently ignored and the system uses its auto-bump instead. Patterns: minor bump (new feature) `1.0.3 → 1.1.0`, major bump (breaking change) `1.2.0 → 2.0.0`, stable promotion from alpha `0.0.6 → 1.0.0`. A version-only promotion (no other content changes) can be done with a single `editFile` that replaces the existing `version: "…"` line.
