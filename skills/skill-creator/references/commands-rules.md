# Rules for the `jclaw-commands` entry

- Write the names on one line, separated by spaces: `jclaw-commands: wacli wacli-setup`.
- Each entry is the **basename** of an executable in the skill's `tools/` folder. No paths, no arguments, no wildcards, no spaces inside a name. `./tools/wacli` on disk becomes `wacli` in the list.
- Use an empty string when the skill ships no binaries: `jclaw-commands: ""`. Prompt-only skills, document generators, and reasoning-only skills all fall here.
- Omit the key entirely only if the skill predates the declaration system — new skills MUST include it (empty when not applicable) so reviewers can tell "this skill intentionally ships zero binaries" from "this skill is missing its declaration."
- Every entry must correspond to a file that actually exists under `tools/`.
- Every executable under `tools/` MUST appear in `jclaw-commands`. Undeclared binaries are dormant — the agent can see them on disk but can't run them because they're not in its shell allowlist.
- Names are case-sensitive and match the literal filename. `wacli`, not `WACLI` or `wacli.sh`.

