# Skill examples

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

Note: the `version` entry is omitted from the frontmatter you write — the filesystem tool injects it under `metadata` when the file is saved. The `author` entry uses your own agent name (the example below assumes the `main` agent is creating this skill).

**Output Location:** If this skill generates files (HTML, documents, etc.), store them in `{skill-name}/` at the root of the agent's workspace (e.g., `my-skill/`) so users can easily find and download them.

```markdown
---
name: whatsapp-notifier
description: >-
  Send WhatsApp messages using the wacli tool. Use when the user asks to message someone on WhatsApp.
metadata:
  author: main
  jclaw-icon: "💬"
  jclaw-tools: exec filesystem
  jclaw-commands: wacli
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
description: >-
  Rephrase a sentence in a different tone. Use when the user asks to reword something.
metadata:
  author: main
  jclaw-icon: "✍️"
  jclaw-tools: ""
  jclaw-commands: ""
---

# Sentence Rephraser

Rephrase the user's sentence in the requested tone. No tool calls needed — reply directly.
```

