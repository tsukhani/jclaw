---
name: gamma-presenter
description: >-
  Create beautiful AI-generated presentations, documents, webpages, and social posts via the Gamma API. Supports generation from text prompts, templates, multi-page gammas, exports to PDF/PPTX/PNG, and management of existing gammas.
metadata:
  version: "1.0.9"
  author: main
  jclaw-icon: "🎨"
  jclaw-tools: filesystem exec
  jclaw-commands: ""
---
# Gamma Presenter

Generate polished presentations, documents, webpages, and social media posts using the Gamma API.

## Prerequisites

- Gamma account with Pro, Ultra, Team, or Business plan (required for API key)
- The API key in JClaw's secret store, as `GAMMA_API_KEY`

## Secrets

```secrets
GAMMA_API_KEY=public-api.gamma.app   # A Gamma API key (starts with sk-gamma-), from Settings, API Keys
```

The operator adds it once; an agent cannot.

1. Log in to Gamma at https://gamma.app
2. Go to **Settings → API Keys**
3. Generate a new API key (starts with `sk-gamma-`)
4. In JClaw, open this skill from the agent's column on the Skills page and add a secret named `GAMMA_API_KEY`, with the host `public-api.gamma.app`

Any command that names `$GAMMA_API_KEY` receives a stand-in, and JClaw puts the real key in only on requests to that host. Never look for the key in a file. If a call answers 401 or `$GAMMA_API_KEY` is empty, the secret is missing: ask the operator to add it.

## Quick Reference

| Task | Endpoint | Method |
|---|---|---|
| Generate from text | `/v1.0/generations` | POST |
| Generate from template | `/v1.0/generations/from-template` | POST |
| Check status | `/v1.0/generations/{id}` | GET |
| Generate image | `/v1.0/images` | POST |
| Check image status | `/v1.0/images/{id}` | GET |
| List themes | `/v1.0/themes` | GET |
| List folders | `/v1.0/folders` | GET |
| Search gammas | `/v1.0/gammas/search` | GET |
| Get gamma | `/v1.0/gammas/{gammaId}` | GET |
| Archive gamma | `/v1.0/gammas/{gammaId}/archive` | POST |
| Export gamma | `/v1.0/gammas/{gammaId}/export` | POST |
| Check export status | `/v1.0/exports/{id}` | GET |
| Delete gamma | `/v1.0/gammas/{gammaId}` | DELETE |

## Base Configuration

| Setting | Value |
|---|---|
| Base URL | `https://public-api.gamma.app` |
| Version | `v1.0` |
| Auth header | `X-API-KEY` |
| Content-Type | `application/json` |

## Core Operations

### 1. Generate a Presentation from Text

```bash
curl -X POST "https://public-api.gamma.app/v1.0/generations" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: $GAMMA_API_KEY" \
  -d '{
    "inputText": "Your content or prompt here",
    "textMode": "generate",
    "format": "presentation",
    "numCards": 10,
    "themeId": "theme-id-here",
    "textOptions": { "language": "en" },
    "exportAs": "pdf"
  }'
```

**textMode options:** `generate` (Gamma rewrites and expands your text), `condense` (Gamma summarizes it), `preserve` (Gamma keeps your text as it is)

`themeId` and `textOptions` are optional: leave `themeId` out for the workspace's default theme. The language goes inside `textOptions`; a top-level `language` is rejected with HTTP 400.

**format options:** `presentation`, `document`, `webpage`, `social`

**exportAs options:** `pdf`, `pptx`, `png` (PNG returns a ZIP with one image per card)

### 2. Poll for Generation Completion

```bash
curl -H "X-API-KEY: $GAMMA_API_KEY" \
  "https://public-api.gamma.app/v1.0/generations/{generationId}"
```

Poll every 5 seconds until `status` is `completed` or `failed`.

**Completed response includes:**
- `gammaUrl` — live presentation link
- `exportUrl` — downloadable file (if exportAs was specified)
- `gammaId` — ID for future management operations
- `credits` — deduction and remaining balance

### 3. Generate from a Template

```bash
curl -X POST "https://public-api.gamma.app/v1.0/generations/from-template" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: $GAMMA_API_KEY" \
  -d '{
    "templateGammaId": "template-id-here",
    "textMode": "generate",
    "inputText": "New content to adapt the template to"
  }'
```

### 4. Export an Existing Gamma

```bash
curl -X POST "https://public-api.gamma.app/v1.0/gammas/{gammaId}/export" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: $GAMMA_API_KEY" \
  -d '{
    "format": "pptx"
  }'
```

Then poll: `GET /v1.0/exports/{exportId}`

### 5. Generate a Standalone Image

```bash
curl -X POST "https://public-api.gamma.app/v1.0/images" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: $GAMMA_API_KEY" \
  -d '{
    "prompt": "A hero image of a futuristic city skyline at sunset",
    "aspectRatio": "16:9"
  }'
```

## Available Themes and Folders

### List Themes
```bash
curl -H "X-API-KEY: $GAMMA_API_KEY" \
  "https://public-api.gamma.app/v1.0/themes"
```

### List Folders
```bash
curl -H "X-API-KEY: $GAMMA_API_KEY" \
  "https://public-api.gamma.app/v1.0/folders"
```

## Workflow: Create → Poll → Download

When asked to create a Gamma presentation:

1. **Use `$GAMMA_API_KEY`** in each `curl` call as shown above; never read or print the key
2. **Build the request payload** based on user's specifications (format, numCards, themeId, exportAs, etc.)
3. **Start generation** via `exec` using `curl` to POST `/v1.0/generations`
4. **Parse the `generationId`** from the response
5. **Poll for completion** every 5 seconds using `exec` with `curl` GET `/v1.0/generations/{id}`
6. **Report results** — share the `gammaUrl` and `exportUrl` (if applicable) with the user
7. **Save metadata** (generationId, gammaId, gammaUrl, exportUrl) to a local file in `gamma-presenter/` for reference, and clean up transient artifacts after use

## Tips for Best Results

- Be specific: "10-slide marketing strategy covering target audience, channels, budget, and metrics"
- Describe style: "professional and minimal", "colorful and creative", "corporate and clean"
- Specify text density: "brief bullet points" vs "detailed explanations"
- Mention folders: "save to my Marketing folder" (use folder ID from `/folders`)
- Use `textMode: "preserve"` when you already have the full content written

## Error Handling

| Status | Meaning | Action |
|---|---|---|
| `401` | Invalid or missing API key | Ask the operator to check the `GAMMA_API_KEY` secret on the Skills page |
| `403` | Insufficient credits | Purchase more credits in Gamma settings |
| `429` | Rate limited | Wait and retry with exponential backoff |
| `422` | Invalid parameters | Check request payload against API docs |
| `5xx` | Server error | Retry after a short delay |

## Important Notes

- API calls must be **server-side only** — never from browser JavaScript
- Generations are async — always poll for completion
- Credits are deducted per generation; check remaining credits in the response
- Export URLs expire after a period; download files promptly
- Multi-page gammas require the `generate_multi_page` tool (via MCP) — REST API generates single gammas per call
