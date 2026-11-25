---
description: Scan everything a release publishes since the last release, the newest v* tag (every tracked file across every commit in that range, and the commit messages, release notes included), for hardcoded secrets, API keys, tokens, private keys, connection strings, and PII — project-aware false-positive filtering, redacted evidence, rotate-first remediation. /release-check runs it on every release. Read-only.
---

**Secret & sensitive-data scan**

Surface anything sensitive hardcoded **in what the repository publishes**: API keys, tokens, passwords, private keys, connection strings, and PII embedded in code, comments, string literals, scripts, docs or commit messages. This is a **read-only defensive audit** — it never edits files, never sends anything anywhere, and **redacts every secret it reports** (printing a live credential in full would re-leak it into this transcript/logs). Use `/usr/bin/git` for every git invocation (project convention).

**Scope of `history` — source code only.** A `history` scan applies to the *contents of source-code files* across all four of JClaw's source trees: **`app/`** (backend Java), **`test/`** (test Java), **`frontend/`** (`.vue`/`.ts`/`.js`), and **`sidecar/`** (the Python ASR/diarize/TTS/image/video sidecars). It deliberately does **not** audit non-source files — `conf/` config, `certs/`, `.env*`, `package.json`/lockfiles, docs, CI, or build files — nor does it do file-name-based checks: those vectors are already handled by `.gitignore` (which excludes `certs/*`, `.env*`, `*.key`, `*.pem`, `*.p12`, `*.jks`) and the pre-commit hook. The value here is catching a secret that a developer typed *into code*, where no file-pattern rule would ever stop it.

**Range — since the last release.** By default the scan covers only what changed after the last version `/deploy` shipped: the commits after the newest `v*` tag reachable from `HEAD`, since `/deploy` tags each version it ships. What that tag holds has already shipped, and `history` audits it; scanning it again on every release only buries the new lines. Every commit in the range is scanned, not only the tree at `HEAD`, because `/deploy` publishes each commit to the public GitHub mirror: a secret added and deleted again before the release still ships in history. For the same reason the range reads **every tracked file**, not only the four source trees (`bin/`, `skills/`, `.claude/`, `docs/`, `conf/` and the rest ship too), and **every commit message**, since a release commit's body becomes the public release notes. Only new lines are read there, so the docs and scripts the source-only scope above keeps out of a whole-history scan cost little here. `/release-check` runs this scan on every release (its step 9), and a finding fails that release.

The bar is **signal over noise**: verify every candidate in context before reporting it, and suppress this repo's known-safe patterns (below). Do **not** dump a wall of raw grep hits.

---

**Arguments** — `$ARGUMENTS` may be:

- *(empty)* → scan **every tracked file** except this one, and **every commit message**, over **every commit since the last release**: `BASE=$(/usr/bin/git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD)` and the commits `$BASE..HEAD`. With no `v*` tag there is no release yet: say so and run `history` instead.
- a **path** (e.g. `app/services`, `bin/`, `skills/`, `docs/`) → scan that subtree instead, over the same range, without the commit messages.
- `staged` → scan only the **staged** source changes (`git diff --cached`) — a pre-commit gate against typing a secret into code.
- `history` → scan the **git history** of the source files, every reachable commit. A secret that was committed to code and later removed is **still in history and is compromised**; run this before making the repo public, after any suspected leak, or to audit what shipped before the last release.

Reject anything else with a clear message; do not guess.

**Phase 1 — Establish the source scope**

Enumerate the target source files with `git ls-files -- <scope>` restricted to source extensions — `*.java`, `*.vue`, `*.ts`, `*.js`, `*.mjs`, `*.py` (`history`'s scope: `app/ test/ frontend/ sidecar/`). Use `git grep` for the detection passes; it searches exactly the tracked set and is fast. Exclude this command file itself (`.claude/commands/secret-scan.md`) — it documents the regexes below and would self-match. For the range, the pathspec is `-- . ':!.claude/commands/secret-scan.md'` (every tracked file but this one), any extension, since `-I` already skips binaries; record `BASE` and the commit count, both for the report, which `node bin/secret-scan-range.mjs range` prints (`v1.0.30 15`). A count of 0 means nothing since `BASE`, so the verdict is CLEAN once the control below has passed. For `staged`, use `git diff --cached`; for `history`, plan the cross-revision pass (Phase 4).

**Phase 2 — Detection battery (grep source files, then verify each hit in context)**

Run these patterns with **`git grep -nIP`** (PCRE). Treat every hit as a *candidate*; open the surrounding lines and classify it against Phase 3 before it becomes a finding.

**`-P`, never `-E`.** macOS git (Apple Git 2.50.1) silently ignores `\b`, `\d` and `\s` under `-E` — no error, just zero matches. Most patterns below are `\b`-anchored, so under `-E` the battery matches nothing and the scan reports CLEAN whatever the files contain. Verified on this repo: `git grep -cE '\bMemorySafety\b'` → 0 where `-P` → 94.

Three more invocation traps with the same false-CLEAN signature:

- **Write the pathspec literally.** zsh does not word-split unquoted expansions, so `-- $SCOPE` passes the single pathspec `"app test frontend sidecar"` and matches no file. Use literal paths or a shell array.
- **`-e` before any pattern starting with `-`.** The two `-----BEGIN` patterns are otherwise parsed as command-line options: `git grep -nIP -e '-----BEGIN( [A-Z0-9]+)? PRIVATE KEY-----'`.
- **Never `2>/dev/null` a detection pass.** It hides precisely these errors, which is what makes a broken scan indistinguishable from a clean repo.

**The range (the default): new lines only.** For each pattern, `bin/secret-scan-range.mjs` greps every commit in the range and `BASE`, and keeps what `BASE` did not hold; for a path argument, give the path after the pattern:

```bash
node bin/secret-scan-range.mjs lines -P "$P"
```

Each line reads `<sha>:<path>:<line>:<text>`. A path and text `BASE` already held shipped before and are dropped, even where the range moved the line; what is left was added in the range. The SHA on a kept line is the newest commit holding it: `HEAD`'s SHA means the line is in the tree, and any other means a later commit deleted it while the range's history still carries it. The script leaves this file out, passes the pattern with `-e` and each revision as its own argument, so the dash and zsh traps above cannot reach it, and exits 2 with git's reason when a pass fails, an invalid pattern or a checkout with no `v*` tag among them, so a broken pass never reads as a clean one. Its tests pin each of those, and a pattern the release matched nothing for, which an earlier filter here filed as already released. The filters are a script rather than shell here because a command run with arguments has each dollar sign followed by a digit in its text replaced by an argument's word: the `awk` this command once held came out garbled when `/release-check` ran it.

**The commit messages (the range only).** `messages` prints each message line with its commit's short SHA in front, leaving out the `Co-Authored-By:` trailers, whose `noreply@` address is attribution and would hit the email pattern on every commit. Write it to a temporary directory `T` (the session's scratchpad when it has one) and grep that file:

```bash
node bin/secret-scan-range.mjs messages > "$T/messages.txt"
(cd "$T" && /usr/bin/git grep --no-index -nIP -e "$P" -- messages.txt)
```

Each hit reads `messages.txt:<n>:<sha>: <the message line>`. Nothing is subtracted here, since every message in the range is new. Run in `/release-check`'s worktree, the range ends at the release commit, so its body, the release notes, is scanned too. The control: the newest commit's subject, as a fixed string (`-F`), must be found in `messages.txt`.

**Prove the harness before trusting a zero.** Before reporting anything, run a known-positive control — `git grep -cP 'looksLikeSecret' -- test` must be non-zero, and each tree's source-file count must be non-zero. For the range, run the control over the range's commits (`/usr/bin/git grep -cP 'looksLikeSecret' $(/usr/bin/git rev-list "$BASE"..HEAD) -- test`, non-zero), and prove the filter as well: give one distinctive line the range added to `node bin/secret-scan-range.mjs lines -F '<line>'`, choosing a line `/usr/bin/git grep -F` does not find at `BASE`, and it must come out. A pass that has not been shown capable of matching is not evidence of absence (AGENTS.md §6: *a green result you did not scope is not evidence*). State in the report that the control passed, so the CLEAN verdict is falsifiable.

*Cryptographic material (CRITICAL — a private key literal in code):*
- `-----BEGIN( [A-Z0-9]+)? PRIVATE KEY-----` · `-----BEGIN PGP PRIVATE KEY BLOCK-----`

*Provider keys / tokens (CRITICAL — usually live):*
- AWS `\bA(KIA|SIA)[0-9A-Z]{16}\b` · Google `\bAIza[0-9A-Za-z_-]{35}\b`
- GitHub `\bgh[pousr]_[0-9A-Za-z]{36}\b` / `\bgithub_pat_[0-9A-Za-z_]{60,}\b` · GitLab `\bglpat-[0-9A-Za-z_-]{20}\b`
- Slack `\bxox[baprs]-[0-9A-Za-z-]{10,}\b` · Stripe `\b[sr]k_live_[0-9A-Za-z]{16,}\b`
- OpenAI `\bsk-(proj-)?[0-9A-Za-z_-]{20,}\b` · Anthropic `\bsk-ant-[0-9A-Za-z_-]{20,}\b`
- Telegram bot token `\b\d{8,10}:[0-9A-Za-z_-]{35}\b` · Twilio `\bAC[0-9a-f]{32}\b` · SendGrid `\bSG\.[0-9A-Za-z_-]{22}\.[0-9A-Za-z_-]{43}\b` · npm `\bnpm_[0-9A-Za-z]{36}\b`
- JWT `\beyJ[0-9A-Za-z_-]+\.eyJ[0-9A-Za-z_-]+\.[0-9A-Za-z_-]+\b`

*Generic hardcoded secrets (HIGH — verify the value is real, not an env-ref/placeholder/key-name):*
- `(?i)(passwd|password|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|auth[_-]?token|client[_-]?secret|private[_-]?key|encryption[_-]?key)\s*[:=]\s*["'][^"'\n]{8,}["']`
- Connection strings with inline creds: `\b[a-z][a-z0-9+.-]*://[^\s:/@]+:[^\s:/@]+@` and `(?i)jdbc:[^\s"']*password=[^\s"'&]+`

*PII (MEDIUM — real personal data in code, seed/fixture data, or comments):*
- Email `\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b` (then subtract the noise, Phase 3)
- US SSN `\b\d{3}-\d{2}-\d{4}\b` · payment card `\b(?:\d[ -]?){13,16}\b` (report only if it passes a **Luhn** check)
- Phone number — E.164 `\+[1-9][0-9]{0,3}[ .()-]?[0-9][0-9 .()-]{5,13}[0-9]` (a `+<country-code>` number) or separated national `\(?[0-9]{3}\)?[ .-][0-9]{3}[ .-][0-9]{4}`. Run this over comments **and** string/seed data. A `+<cc>` number sitting next to a **person's name** must be *checked* rather than assumed fixture noise — but decide it on the digits and the file's history, not on the surrounding prose. Worked example, because this one has been read both ways: the `+60` number in the memory-search seed data (`DirectLuceneMessageSearchRepositoryTest`) is attributed to a named person and so reads as PII, yet the value is the sequential placeholder `12-345 6789`, and `git log -S'+60'` on that file returns exactly one commit — the one that introduced it — so it has never held anything else. Dismissed. Had that search returned two commits, the earlier value would still be in history and compromised.

**Phase 3 — Suppress the known-safe (this is what makes the scan usable here)**

Do **not** report a candidate that is any of the following. Note it in the "checked & dismissed" appendix instead:

- **Externalized to the environment** — the *correct* pattern in source, not a leak: `System.getenv(...)`, `Play.configuration.getProperty(...)`, `${VAR}` / `${PLAY_SECRET}`, `process.env.X`, `import.meta.env`, `@Value("${...}")`, `os.environ`. A key *name* next to one of these is fine; only a literal value is a finding.
- **Config-key-name constants**, not values — e.g. `KEY_SIGNING_SECRET = "signingSecret"`, `"webhookSecret"`, `"appSecret"`: the string is a config *identifier*, not a credential. Read the context to tell a key name from a value.
- **This repo's deliberate design**: runtime secrets live in the gitignored `certs/.env` (not in code); `application.secret=${PLAY_SECRET}` is env-sourced; API tokens and passwords are stored **hashed** (`TokenHasher` SHA-256, `PasswordHasher` PBKDF2) — a stored hash is not a plaintext secret; the internal-API token for live tests is read from H2/env, not committed. The codebase's own `MemorySafety.looksLikeSecret` guard has test fixtures containing *fake* key-shaped strings — those are test data, not leaks.
- **Placeholders / examples**: `YOUR_…`, `<…>`, `xxx…`, `changeme`, `example`, `dummy`, `placeholder`, `redacted`, `****`, all-identical-character strings, and anything shown as illustration in a comment.
- **Public material**: publishable/public keys, public certificates without a private key, public JWKS.
- **PII noise**: `@example.com` / `@test` addresses, `noreply@`, the org's own domains (e.g. `abundent.com`) used as config defaults, obviously-fabricated fixture data.
- **Phone numbers — only the fiction-reserved ranges are dismissible**: US `555-01xx` / `(…) 555-xxxx`, UK Ofcom drama `+44 7700 900xxx` and `+44 20 7946 0xxx`, and clearly-sequential placeholders (`+60 12-345 6789`, `123-456-7890`). Any *other* real-format number is a MEDIUM PII finding — **do not** wave it through just because it's in a test. Malaysia (`+60`) and most countries have **no** 555-style fiction range, so a plausibly-real `+<country-code>` number, or any number tied to a person's name, must be reported.

- **Already released** (the range only): the recipe drops a line `BASE` held, but one moved or re-indented in the range comes back. When `git log -S<value>` shows the value was already in `BASE`, list it among the dismissed as released in `BASE`, which a `history` scan audits.

A real-looking secret is still a finding **even inside a test file** — it's committed and, if live, compromised. Fake-but-real-shaped test data is not.

**Phase 4 — (history mode only) scan the source history**

Run the Phase-2 high-signal patterns across history — `git grep -nIP '<pattern>' $(git rev-list --all) -- '<source scope>'` (`-P` for the reason in Phase 2; under `-E` this pass silently finds nothing), or `git log -p -S<needle> -- '<scope>'` for a specific token. Anything found in *any* reachable commit is compromised regardless of the current tree.

`git log -S<needle> -- <file>` is also how you settle whether a suspicious literal was ever a different value. A single introducing commit means the value in the tree is the value it was born with — which distinguishes a sanitized placeholder from a real secret that was swapped out later and still sits in history.

---

**Output — a graded, redacted report**

Lead with the verdict, then findings grouped by severity, then the appendix. For every reported secret, **redact the value** — show only enough to locate it, e.g. `AKIA1234…(len 20)`, never the full credential.

```
Secret scan — scope: <every tracked file and <N> commit messages | path> since <BASE> (<N> commits) | staged | history · <N> files searched
Harness: git grep -P · control '<pattern>' matched <N> · new-line filter passed '<line>' — detection proven live
Verdict: <CLEAN | N findings (C critical / H high / M medium / L low)>

CRITICAL
  <path>:<line> · <category> · `<redacted>` — <why it's live/sensitive> · <in the tree | removed in a later commit, still in <sha>>
    Fix: ROTATE immediately (assume compromised), then <remove + externalize + purge>.
HIGH / MEDIUM / LOW …

Checked & dismissed (why they're NOT findings): <env-refs, config-key-names, hashed-at-rest, MemorySafety test fixtures, example.com PII, …>

Most urgent action: <the single thing to do first>
```

**Remediation always leads with rotation.** A secret that reached the repo is compromised the moment it's pushed or cloned — deleting the line does **not** un-leak it (it survives in git history and in every existing clone/CI cache). Order: **(1) rotate/revoke the credential at its source**, (2) replace the literal with an env/secret-manager reference (the pattern already used across `app/`), (3) if it was ever committed, purge it from history (`git filter-repo` or BFG) and force-push — flag this as a coordinated action, don't do it as a side effect. For PII: remove it and note any retention/deletion obligations.

A finding from the range has not reached GitHub yet, since `/deploy` is the only push of `main`, but it is in this clone and on `origin` in any candidate branch that carries it, so rotate it all the same. A new commit fixes the tree, not the range: `/deploy` would still publish the commit that added it, and a secret in a commit message can only be reworded out of that commit. Whether to rewrite the unreleased commits so the value never reaches GitHub is the operator's call, made before the release and never as a side effect; nothing here force-pushes.

**Rules of engagement:** read-only — do not edit, stage, commit, or fetch/send anything. Redact all secrets in the output. If a CRITICAL live credential is present, say so plainly and put rotation at the very top. If nothing survives verification, report **CLEAN** with the scope and the categories checked, so the green result is trustworthy rather than a shrug. Run by `/release-check`, the scan changes nothing either: the release check acts on its verdict.
