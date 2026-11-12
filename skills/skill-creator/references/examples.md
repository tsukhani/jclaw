# Skill examples

```
whatsapp-notifier/
├── SKILL.md
└── tools/
    ├── wacli                  # WhatsApp CLI binary
    └── README.md              # Usage docs for the binary
```

### Example SKILL.md

Note: the `version` entry is omitted from the frontmatter you write — the filesystem tool injects it under `metadata` when the file is saved. The `author` entry uses your own agent name (the example below assumes the `main` agent is creating this skill).

**Output Location:** If this skill generates files (HTML, documents, etc.), store them in `{skill-name}/` at the root of the agent's workspace (e.g., `my-skill/`) so users can easily find and download them.

````markdown
---
name: whatsapp-notifier
description: >-
  Send WhatsApp messages using the wacli tool. Use when the user asks to message someone on WhatsApp.
metadata:
  author: main
  jclaw-icon: "💬"
  jclaw-tools: exec
  jclaw-commands: wacli
---

# WhatsApp Notifier

## Secrets

The operator adds this in the skill's Secrets panel on the Skills page. An agent cannot.

```secrets
WHATSAPP_API_TOKEN=api.example-whatsapp.com             # The API token, from the provider's dashboard
setting WHATSAPP_API_URL=https://api.example-whatsapp.com/v1   # The API's address
setting WHATSAPP_SENDER=+15550100                        # The sending number
```

When asked to send a WhatsApp message:

1. Use the `exec` tool to run `tools/wacli` to send the message. It takes the address and number as `$WHATSAPP_API_URL` and `$WHATSAPP_SENDER`, and the token as `$WHATSAPP_API_TOKEN`
2. Confirm delivery status to the user
````

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

