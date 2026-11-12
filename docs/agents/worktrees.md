# Worktrees

Read this when you create, set up or land a Jira ticket's worktree under `.claude/worktrees/<KEY>`. AGENTS.md's Policy holds the rules for creating and landing one; this holds the setup and what stays behind.

- The `post-checkout` hook seeds the worktree's `certs/.env` with its own `PLAY_TEST_PORT`, derived from the worktree's path (`jclaw.sh`'s `ensure_play_test_port`), so two paths can, rarely, land on the same port.
- `cd frontend && CI=true pnpm install` gives it the `node_modules` the pre-commit hook needs and the `.nuxt` vitest needs; without them the hook skips `lint-staged` and every vitest file fails at transform.
- A spec under `_bmad-output/` is gitignored, so it stays in the main checkout and is addressed by absolute path.
- The live instance builds from the main checkout, so a live check waits for the merge.
