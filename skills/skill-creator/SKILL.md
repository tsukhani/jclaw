---
name: skill-creator
description: Create new skills or refactor existing skills to follow the standard directory structure.
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

## Creating a New Skill

1. **Ask** the user what the skill should do. Get a clear name and description.
2. **Draft** the SKILL.md content. A good skill includes:
   - YAML frontmatter with `name` and `description` fields
   - A clear title and purpose
   - Step-by-step instructions the agent should follow
   - Which tools to use and how
   - Example inputs and expected outputs
   - Edge cases and error handling
3. **Create** the skill using the filesystem tool:
   - Create the directory: `skills/{skill-name}/`
   - Write `skills/{skill-name}/SKILL.md` with YAML frontmatter
   - If credentials are needed, create `credentials/` with placeholder files explaining what keys are required
   - If binary tools are needed, create `tools/` and add documentation explaining how to obtain/install them
4. **Confirm** creation and explain the skill is now available in your workspace.

## Refactoring an Existing Skill

When asked to refactor or update an existing skill, or when you notice a skill that doesn't follow the standard structure:

1. **Audit** the existing skill directory by listing all files and subfolders.
2. **Identify violations:**
   - Any binary files in the root folder or `credentials/` → must move to `tools/`
   - Any credential/config files in the root folder → must move to `credentials/`
   - Any files other than SKILL.md in the root folder → categorize and move to the correct subfolder
   - Missing YAML frontmatter in SKILL.md → add it
   - Non-kebab-case skill name → rename the folder
3. **Move files** to the correct locations using the filesystem tool:
   - Move binaries to `tools/`
   - Move credential/config files to `credentials/`
   - Create subfolders if they don't exist
4. **Update SKILL.md** to reference the new file paths (e.g., `tools/wacli` instead of `./wacli`).
5. **Verify** the final structure matches the standard layout.
6. **Report** what was changed to the user.

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
---

# WhatsApp Notifier

When asked to send a WhatsApp message:

1. Read credentials from `credentials/api-config.json`
2. Use the `tools/wacli` binary with the shell tool to send the message
3. Confirm delivery status to the user
```

## Guidelines

- Keep skill names short and descriptive (kebab-case)
- Write clear, actionable instructions that an LLM can follow
- Reference available tools by name (web_fetch, filesystem, task_manager, checklist)
- Skills should be focused on one task — create multiple skills for complex workflows
- Skills you create go into your workspace and are available only to you
- An admin can promote a skill to global by copying it to the shared skills directory
