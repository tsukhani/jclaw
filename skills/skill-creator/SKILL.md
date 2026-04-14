---
name: skill-creator
description: Create new skills or refactor existing skills to follow the standard directory structure.
version: 1.1.0
author: main
tools: [filesystem]
---
# Skill Creator & Refactorer

You help the user create new skills or refactor existing skills in your workspace to follow the standard directory structure.

## Standard Directory Structure

Every skill MUST follow this layout:

```
{skill-name}/                  # kebab-case name (required)
├── SKILL.md                   # Skill definition with YAML frontmatter (required)
├── credentials/               # Credential/config files only (optional)
│   └── *.json, *.txt, *.env   # API keys, tokens, auth configs — TEXT FILES ONLY
└── tools/                     # Binary tools and their docs (optional)
    ├── {binary}               # Executable/binary files
    └── *.md or *.txt          # Usage documentation for the tools
```

### Rules

- **Binaries are ONLY allowed in the `tools/` folder.** No binaries in the root folder or `credentials/` folder.
- The `credentials/` folder may ONLY contain text files (json, txt, env, yaml, properties).
- The skill root folder may ONLY contain `SKILL.md` and the two subfolders.
- Skill names must be kebab-case (e.g., `web-scraper`, `slack-notifier`).

## Tool Catalog (authoritative)

Every skill MUST declare the exact tools it needs in a `tools:` YAML list in its frontmatter. The authoritative list of valid tool names lives in the `## Tool Catalog` section of your system prompt — it is generated live from the running JClaw build and filtered to the tools this specific agent has enabled, so every name in the table is guaranteed to be callable. Pick ONLY from the names in that table. If a name isn't in the Tool Catalog section, it either doesn't exist or is disabled for this agent, and either way the skill cannot use it.

## Commands Catalog (shell binaries the skill bundles)

Separate from `tools:` — which names JClaw **tools** the skill consumes (`exec`, `filesystem`, `browser`, ...) — a skill that ships executable binaries in its `tools/` folder MUST declare them in a `commands:` YAML list in the frontmatter. `commands:` drives the per-agent shell allowlist: at promotion time these exact names become the blessed set for this skill in the global registry, and at skill-install time they are snapshotted into the consuming agent's effective shell allowlist so the agent can run them via `exec`.

### Rules for the `commands:` field

- Use inline YAML list form: `commands: [wacli, wacli-setup]`.
- Each entry is the **basename** of an executable in the skill's `tools/` folder. No paths, no arguments, no wildcards. `./tools/wacli` on disk becomes `wacli` in the list.
- Use an empty list when the skill ships no binaries: `commands: []`. Prompt-only skills, document generators, and reasoning-only skills all fall here.
- Omit the key entirely only if the skill predates the declaration system — new skills MUST include it (empty when not applicable) so reviewers can tell "this skill intentionally ships zero binaries" from "this skill is missing its declaration."
- Every entry must correspond to a file that actually exists under `tools/`. Declared-but-missing binaries fail the promotion malware scan and the install snapshot.
- Every executable under `tools/` MUST appear in `commands:`. Undeclared binaries are dormant — the agent can see them on disk but can't run them because they're not in its shell allowlist.
- Names are case-sensitive and match the literal filename. `wacli`, not `WACLI` or `wacli.sh`.

## Author field

Every skill MUST include an `author:` field in its frontmatter. The value is the **name of the agent creating the skill** — i.e., *your own* agent name, substituted from your Environment section of the system prompt (the line beginning `Agent:`). Operators use this to see which agent authored which skill when auditing the global registry after promotion, and to route bug reports back to the right agent's workspace.

- Use the agent's literal name, not a description. If your Environment says `Agent: main`, write `author: main`. If it says `Agent: research-bot`, write `author: research-bot`.
- The value is a single unquoted YAML scalar: `author: main` (no list, no object).
- When refactoring an existing skill, leave the existing `author:` value alone — it records the original creator. Only edit it if the user explicitly asks to reassign authorship.

## Creating a New Skill

1. **Ask** the user what the skill should do. Get a clear name and description.
2. **Determine required tools.** Walk through the steps the skill will perform and pick EVERY tool it will need from the `## Tool Catalog` section of your system prompt. Be exhaustive but exact: do not add tools the skill will not use, and do not omit tools the skill cannot work without. Read each tool's description in the Tool Catalog and match it against what the skill actually does.
   - Only use names that appear verbatim in the Tool Catalog table. If a capability isn't in the catalog, the skill cannot do it.
   - Because the Tool Catalog is already filtered to this agent's enabled tools, any name you pick from it is guaranteed to be callable — no pre-flight check needed.
   - **Pure reasoning / computation only (no I/O, no external data)** → `tools: []` (empty list). Do NOT invent tools for tasks the LLM can answer on its own. Example: "compute 2+2" or "rephrase this sentence" need zero tools.
3. **Determine bundled commands.** If the skill will ship executable binaries under `tools/`, list every binary's basename in the `commands:` field. If the skill ships no binaries, use `commands: []`. See the Commands Catalog section above for rules. A skill that uses the `exec` tool but calls only system commands already in the global allowlist (`git`, `ls`, etc.) still ships zero commands — `commands:` only covers binaries the skill itself contributes.
4. **Draft** the SKILL.md content:
   - YAML frontmatter with (in this order): `name`, `description`, `author` (your own agent name — see the Author field section), `tools:` (the exact list from step 2), and `commands:` (the exact list from step 3)
   - A clear title and purpose
   - Step-by-step instructions the agent should follow
   - Reference each declared tool by name in the body
   - If the skill has entries in `commands:`, reference each bundled binary by its path in the body (e.g., `tools/wacli`) so the reader can find it
   - Example inputs and expected outputs
   - Edge cases and error handling
   - **Output Location:** If this skill generates files (HTML diagrams, documents, etc.), include: `Outputs are written to \`workspace/{agent-name}/{skill-name}\` (e.g., \`workspace/main/my-skill/\`).`
   - **Leave the `version:` field out by default.** When you omit it, the system auto-bumps the patch component on every material write (e.g., `1.0.3 → 1.0.4`) and new skills start at `1.0.0`. **Set an explicit `version:` only when the user asks to promote the skill forward** — e.g., "bump to v1.0.0", "promote to 1.1", "this is a breaking change, make it 2.0.0". In that case, add `version: X.Y.Z` to the frontmatter with your intended target. The value MUST be strictly greater than the next auto-bump target; anything lower or invalid is silently ignored and the system uses its auto-bump instead. Patterns: minor bump (new feature) `1.0.3 → 1.1.0`, major bump (breaking change) `1.2.0 → 2.0.0`, stable promotion from alpha `0.0.6 → 1.0.0`. A version-only promotion (no other content changes) can be done with a single `editFile` that replaces the existing `version:` line.
5. **Create** the skill using the filesystem tool:
   - Create the directory: `skills/{skill-name}/`
   - Write `skills/{skill-name}/SKILL.md` with `writeFile` (brand-new files use `writeFile`; existing files should use `editFile` — see the Refactoring section).
   - If credentials are needed, create `credentials/` with placeholder files
   - If binary tools are needed, create `tools/` with documentation. Every binary dropped here MUST also appear in the frontmatter `commands:` list, and every name in `commands:` MUST correspond to a file in `tools/`.
   - **Creating multiple files at once?** Use `applyPatch` to add several files atomically — it validates all ops before touching disk, so you won't end up with half a skill on a parse error. Example: `*** Begin Patch` / `*** Add File: skills/foo/SKILL.md` / `+---` / `+name: foo` / ... / `*** End of File` / `*** Add File: skills/foo/credentials/api.json` / ... / `*** End Patch`.
6. **Confirm** creation. Tell the user the declared `tools:` and `commands:` lists so they can verify. If the skill ships binaries, remind the user that installing this skill to another agent will grant that agent execution rights for those exact command names via the shell allowlist.

### Rules for the `tools:` field

- Use inline YAML list form: `tools: [tool_a, tool_b]` — substitute real names from the Tool Catalog.
- Use an empty list if the skill uses no tools at all: `tools: []`
- Tool names are case-sensitive and MUST match the Tool Catalog exactly.
- NEVER invent tool names. If a name isn't in the Tool Catalog section of your system prompt, it doesn't exist. Common mistakes: writing `readFile` instead of `filesystem`, `shell` instead of `exec`, `http` instead of `web_fetch`.
- The declared list must equal the set of tools actually referenced in the skill body — no more, no less.

## Self-modification restriction

**`skill-creator` itself is read-only for every agent except `main`.** Only the `main` agent can modify the skill-creator skill. All other agents can use skill-creator to create or refactor OTHER skills, but cannot alter skill-creator's own SKILL.md or any file inside `skills/skill-creator/`. If a user asks a non-main agent to modify skill-creator, explain: "I cannot modify my own skill-creator — only the main agent can. If you want skill-creator updated, modify it on the main agent and promote it to the global registry, then drag it from global onto my agent card to get the new version."

If the global skill-creator is updated to a newer version, out-of-date copies on non-main agents are automatically hidden from `<available_skills>` — the agent cannot use skill-creator again until the user drags the updated version from global onto the agent card.

## Refactoring an Existing Skill

When asked to refactor or update an existing skill, or when you notice a skill that doesn't follow the standard structure:

1. **Audit** the existing skill directory by listing all files and subfolders.
2. **Identify violations and changes needed:**
   - Any binary files in the root folder or `credentials/` → must move to `tools/`
   - Any credential/config files in the root folder → must move to `credentials/`
   - Any files other than SKILL.md in the root folder → categorize and move to the correct subfolder
   - Missing YAML frontmatter in SKILL.md → add it
   - Non-kebab-case skill name → rename the folder
   - Missing `commands:` key in frontmatter → add it. Populate from binaries in `tools/` (basenames), or `commands: []` if none.
   - Binaries present under `tools/` that aren't listed in `commands:` → either add them to the list (if they're intended for execution) or delete them (if they're leftovers). No dormant binaries.
   - Missing `author:` key in frontmatter → add it. For legacy skills with no recorded author, set `author:` to the agent name that's doing the refactor (i.e., your own agent name from your Environment section). Do NOT invent a historical author.
3. **Move files** to the correct locations using the filesystem tool.
4. **Update SKILL.md** to reference the new file paths (e.g., `tools/wacli` instead of `./wacli`).
5. **Update the SKILL.md with `editFile`, not `writeFile`.** For any refactor that touches an existing SKILL.md, use `editFile` with a batch of `{oldText, newText}` entries. This is dramatically more efficient than rewriting the whole file and is also the only way to edit a skill body that would exceed your output token budget as a single `writeFile` argument. Each `oldText` must appear exactly once in the file — include enough surrounding context (a surrounding line or two) to guarantee uniqueness. For cross-file refactors (e.g., moving a binary from the skill root into `tools/` AND updating SKILL.md to reference the new path), use `applyPatch` to do both changes atomically in one tool call. Leave the `version:` field alone by default — the filesystem tool auto-bumps the patch component on every material write. **Only set an explicit `version:` when the user asks to promote the skill forward** (e.g., "bump to v1.0.0", "promote to 2.0", "this is a breaking change"); in that case include a `version: X.Y.Z` edit in the same batch, with a value strictly greater than the auto-bump target. The filesystem tool response will quote the final version — report it to the user.
6. **Verify** the final structure matches the standard layout.
7. **Report** what was changed and quote the new version as reported by the filesystem tool.

## Example

```
whatsapp-notifier/
├── SKILL.md
├── credentials/
│   └── api-config.json       # WhatsApp API credentials
└── tools/
    ├── wacli                  # WhatsApp CLI binary
    └── README.md              # Usage docs for the binary
```

### Example SKILL.md

Note: the `version:` line is omitted from the frontmatter you write — the filesystem tool injects it deterministically when the file is saved. The `author:` line uses your own agent name (the example below assumes the `main` agent is creating this skill).

**Output Location:** If this skill generates files (HTML, documents, etc.), store them in `workspace/{agent-name}/{skill-name}/` so users can easily find and download them.

```markdown
---
name: whatsapp-notifier
description: Send WhatsApp messages using the wacli tool
author: main
tools: [exec, filesystem]
commands: [wacli]
---

# WhatsApp Notifier

When asked to send a WhatsApp message:

1. Read credentials from `credentials/api-config.json` using the `filesystem` tool
2. Use the `exec` tool to run `tools/wacli` to send the message
3. Confirm delivery status to the user
```

Compare with a **prompt-only** skill (no binaries, no file I/O):

```markdown
---
name: sentence-rephraser
description: Rephrase a sentence in a different tone
author: main
tools: []
commands: []
---

# Sentence Rephraser

Rephrase the user's sentence in the requested tone. No tool calls needed — reply directly.
```

## Output File Locations

Skills that generate files (HTML diagrams, courses, reports, etc.) MUST store output in the user's workspace under a directory matching the skill name:

```
workspace/
└── {agent-name}/
    └── {skill-name}/           # e.g., visual-explainer/
        └── {output-files}      # e.g., jclaw-architecture.html
```

### Rules

1. **Destination:** All output files go to `workspace/<agent-name>/<skill-name>/`
2. **Create if missing:** Use `filesystem` tool to create the directory if it doesn't exist
3. **Filenames:** Use descriptive, kebab-case filenames (e.g., `jclaw-architecture.html`)
4. **No .agent directory:** Do NOT put skill outputs in `.agent/` or other hidden directories
5. **Delivery:** After writing the file, provide a markdown link: `[filename](<relative/path>)` so the user can download it

### Example

A visual-explainer skill generating an architecture diagram:
```markdown
1. Create directory `workspace/main/visual-explainer/` if it doesn't exist
2. Write the HTML file to `workspace/main/visual-explainer/jclaw-architecture.html`
3. Report: `[jclaw-architecture.html](workspace/main/visual-explainer/jclaw-architecture.html)`
```

## Guidelines

- Keep skill names short and descriptive (kebab-case)
- Write clear, actionable instructions that an LLM can follow
- Reference tools ONLY by the exact names from the live Tool Catalog in your system prompt — nothing else is valid
- Skills should be focused on one task — create multiple skills for complex workflows
- Skills you create go into your workspace and are available only to you
- An admin can promote a skill to global by copying it to the shared skills directory
