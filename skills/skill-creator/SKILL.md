---
name: skill-creator
description: Create new skills or refactor existing skills to follow the standard directory structure.
version: 1.0.0
tools: [filesystem, web_fetch]
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

Every skill MUST declare the exact tools it needs in a `tools:` YAML list in its frontmatter. The authoritative list of valid tool names lives in the `## Tool Catalog` section of your system prompt — it is generated live from the running JClaw build, so it always reflects what actually exists. Pick ONLY from the names in that table. If a name isn't in the Tool Catalog section, it is not a real tool.

## Creating a New Skill

1. **Ask** the user what the skill should do. Get a clear name and description.
2. **Determine required tools.** Walk through the steps the skill will perform and pick EVERY tool it will need from the `## Tool Catalog` section of your system prompt. Be exhaustive but exact: do not add tools the skill will not use, and do not omit tools the skill cannot work without. Read each tool's description in the Tool Catalog and match it against what the skill actually does.
   - Only use names that appear verbatim in the Tool Catalog table. If a capability isn't in the catalog, the skill cannot do it.
   - **Pure reasoning / computation only (no I/O, no external data)** → `tools: []` (empty list). Do NOT invent tools for tasks the LLM can answer on its own. Example: "compute 2+2" or "rephrase this sentence" need zero tools.
3. **Pre-flight check (mandatory).** Before writing any files, verify the agent actually has every required tool enabled:
   - Read the `Agent ID` field from the `## Environment` section at the end of the system prompt.
   - Use `web_fetch` to GET `http://localhost:9000/api/agents/{agent-id}/tools` (substituting the real numeric id).
   - Parse the JSON response. Each entry has `name` and `enabled` fields. Collect the names where `enabled = true`.
   - Compute the set difference: `required_tools − enabled_tools = missing_tools`.
   - **If `missing_tools` is non-empty**: STOP. Do not write any files. Tell the user: "Cannot create skill `{name}` for this agent: it requires tools [list] that are disabled. Ask an admin to enable them on the Agents page and try again."
   - **If `missing_tools` is empty (or `required_tools` is empty)**: proceed to step 4.
4. **Draft** the SKILL.md content:
   - YAML frontmatter with `name`, `description`, `version: 1.0.0` (every new skill starts here), and `tools:` (the exact list from step 2)
   - A clear title and purpose
   - Step-by-step instructions the agent should follow
   - Reference each declared tool by name in the body
   - Example inputs and expected outputs
   - Edge cases and error handling
5. **Create** the skill using the filesystem tool:
   - Create the directory: `skills/{skill-name}/`
   - Write `skills/{skill-name}/SKILL.md`
   - If credentials are needed, create `credentials/` with placeholder files
   - If binary tools are needed, create `tools/` with documentation
6. **Confirm** creation. Tell the user the declared `tools:` list so they can verify.

### Rules for the `tools:` field

- Use inline YAML list form: `tools: [tool_a, tool_b]` — substitute real names from the Tool Catalog.
- Use an empty list if the skill uses no tools at all: `tools: []`
- Tool names are case-sensitive and MUST match the Tool Catalog exactly.
- NEVER invent tool names. If a name isn't in the Tool Catalog section of your system prompt, it doesn't exist. Common mistakes: writing `readFile` instead of `filesystem`, `shell` instead of `exec`, `http` instead of `web_fetch`.
- The declared list must equal the set of tools actually referenced in the skill body — no more, no less.

## Refactoring an Existing Skill

When asked to refactor or update an existing skill, or when you notice a skill that doesn't follow the standard structure:

1. **Audit** the existing skill directory by listing all files and subfolders.
2. **Identify violations and changes needed:**
   - Any binary files in the root folder or `credentials/` → must move to `tools/`
   - Any credential/config files in the root folder → must move to `credentials/`
   - Any files other than SKILL.md in the root folder → categorize and move to the correct subfolder
   - Missing YAML frontmatter in SKILL.md → add it
   - Missing `version:` in frontmatter → add `version: 1.0.0`
   - Non-kebab-case skill name → rename the folder
3. **Move files** to the correct locations using the filesystem tool.
4. **Update SKILL.md** to reference the new file paths (e.g., `tools/wacli` instead of `./wacli`).
5. **Bump the version** in the frontmatter. The convention is:
   - **Patch bump** (e.g., `1.2.3` → `1.2.4`) for any refactor, wording tweak, bug fix, or change to the instructions. This is the default — use it for almost every refactor.
   - **Minor bump** (e.g., `1.2.3` → `1.3.0`) only if the user explicitly asks for a minor or feature-level change, or if you are adding new steps/capabilities that existing users might want to know about.
   - **Major bump** (e.g., `1.2.3` → `2.0.0`) only if the user explicitly asks for a breaking change or if the required `tools:` list is changing in a way that would break existing agents.
   - Never skip the bump. If ANY byte of the skill changes, the version MUST be incremented.
6. **Verify** the final structure matches the standard layout.
7. **Report** what was changed and the new version to the user.

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

```markdown
---
name: whatsapp-notifier
description: Send WhatsApp messages using the wacli tool
version: 1.0.0
tools: [exec, filesystem]
---

# WhatsApp Notifier

When asked to send a WhatsApp message:

1. Read credentials from `credentials/api-config.json` using the `filesystem` tool
2. Use the `exec` tool to run `tools/wacli` to send the message
3. Confirm delivery status to the user
```

## Guidelines

- Keep skill names short and descriptive (kebab-case)
- Write clear, actionable instructions that an LLM can follow
- Reference tools ONLY by the exact names from the live Tool Catalog in your system prompt — nothing else is valid
- Skills should be focused on one task — create multiple skills for complex workflows
- Skills you create go into your workspace and are available only to you
- An admin can promote a skill to global by copying it to the shared skills directory
