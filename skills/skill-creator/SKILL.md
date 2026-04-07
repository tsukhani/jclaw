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
3. **Create** the skill using the filesystem tool:
   - Create a directory: `skills/{skill-name}/`
   - Write the SKILL.md file: `skills/{skill-name}/SKILL.md`
   - The file must start with YAML frontmatter between `---` markers
4. **Confirm** creation and explain the skill is now available in your workspace.

## Example SKILL.md format

```markdown
---
name: code-reviewer
description: Review code for bugs, security issues, and best practices
---

# Code Reviewer

When asked to review code:

1. Read the file using the filesystem tool
2. Analyze for:
   - Bugs and logic errors
   - Security vulnerabilities
   - Performance issues
   - Code style and readability
3. Provide a structured review with severity levels
```

## Guidelines

- Keep skill names short and descriptive (kebab-case)
- Write clear, actionable instructions that an LLM can follow
- Reference available tools by name (web_fetch, filesystem, task_manager, checklist)
- Skills should be focused on one task — create multiple skills for complex workflows
- Skills you create go into your workspace and are available only to you
- An admin can promote a skill to global by copying it to the shared skills directory
