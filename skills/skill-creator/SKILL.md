---
name: skill-creator
description: Create new skills for the agent's workspace. Guides you through defining skill name, description, and content.
---

# Skill Creator

You are helping the user create a new skill in your workspace.

## Process

1. **Ask** the user what the skill should do. Get a clear name and description.
2. **Draft** the skill content in markdown format. A good skill includes:
   - YAML frontmatter with `name` and `description` fields
   - A clear title and purpose
   - Step-by-step instructions the agent should follow
   - Which tools to use and how
   - Example inputs and expected outputs
   - Edge cases and error handling
3. **Create** the skill using the filesystem tool with this directory structure:
   ```
   {skill-name}/
   ├── SKILL.md              # Skill definition (required)
   ├── credentials/           # Credential files (optional)
   │   └── *.json or *.txt    # API keys, tokens, auth configs
   └── tools/                 # Binary tools and docs (optional)
       ├── {binary}           # Executable files
       └── *.md or *.txt      # Usage documentation for the tools
   ```
   - Skill name must be **kebab-case** (e.g., `web-scraper`, `slack-notifier`)
   - Create the directory: `skills/{skill-name}/`
   - Write the SKILL.md file: `skills/{skill-name}/SKILL.md`
   - The file must start with YAML frontmatter between `---` markers
   - If the skill requires credentials, create a `credentials/` subfolder with placeholder files explaining what keys are needed
   - If the skill uses binary tools, place them in a `tools/` subfolder with accompanying documentation
4. **Confirm** creation and explain the skill is now available in your workspace.

## Example Skill Structure

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
