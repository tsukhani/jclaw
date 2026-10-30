# Frontend toolchain — pnpm and Node

Package manager: **pnpm 12+**, version pinned in `frontend/package.json`'s `packageManager` field. pnpm installs standalone (`curl -fsSL https://get.pnpm.io/install.sh | sh -`) and switches itself to the pinned version on first use — **not** through corepack, which cannot launch pnpm 12 (per-platform native binary, no `bin/pnpm.cjs`) and which Node 25+ no longer ships.

**Where the integrity check lives.** The pin is a bare version; a `+sha512-...` suffix, when one is present, is what corepack verified against, and pnpm ignores it — measured, not assumed. pnpm records its own per-platform releases in `frontend/pnpm-lock.yaml` under `packageManagerDependencies` and refuses to run one whose bytes do not match a published, signed npm release (`ERR_PNPM_PNPM_ENGINE_IDENTITY_MISMATCH`, reproduced by tampering with one integrity line). That is a stronger guarantee than the old one — provenance rather than agreement with a locally-edited string — and it is committed, reviewable in a diff, and covers every platform rather than whichever one last ran `corepack use`.

Two layers keep it honest:
1. The `.githooks/pre-commit` guard refuses to commit a `frontend/package.json` that pins pnpm while `frontend/pnpm-lock.yaml` carries no `packageManagerDependencies` block — the block's absence is what would silently disable the gate.
2. `./jclaw.sh start` (dev and prod) resolves the pin before any pnpm step and hard-fails when it is missing.

To bump the pin: `cd frontend && pnpm self-update <version>`, then commit `frontend/package.json` **and** `frontend/pnpm-lock.yaml` — the lockfile carries the new binaries' integrity.

**Node 25+ and the test suite.** Node 25 enabled its own Web Storage API by default, and that partial implementation shadows jsdom's: `localStorage` lands undefined and 93 specs fail on what looks like a jsdom bug. `frontend/vitest.config.ts` sets `--no-webstorage` via `NODE_OPTIONS`, gated on the running Node major because Node 24 rejects the flag outright. Keep the gate while anything still builds on Node 24 — CI is on `node-26` now, but local clones and older checkouts are not, and an ungated flag is a hard `node: bad option` failure there. Tracking: vitest-dev/vitest#8757.
