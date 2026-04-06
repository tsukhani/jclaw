---
name: example
description: Example skill demonstrating the SKILL.md format. Use this as a template for creating new skills.
---

# Example Skill

This is a template skill. To create a new skill:

1. Create a directory under `workspace/{agent}/skills/{skill-name}/`
2. Add a `SKILL.md` file with YAML frontmatter containing `name` and `description`
3. The `description` field is what the agent sees when deciding whether to load this skill
4. The body (this section) contains the detailed instructions the agent follows

## When to use this skill
- When the user asks about creating skills
- When demonstrating the skill system

## Instructions
1. Explain that skills are markdown files with YAML frontmatter
2. Show the directory structure: `workspace/{agent}/skills/{name}/SKILL.md`
3. Emphasize that the `description` field is critical for matching
