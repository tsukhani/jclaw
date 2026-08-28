---
name: gamma-presenter
description: Create beautiful AI-generated presentations, documents, webpages, and social posts via the Gamma API. Supports generation from text prompts, templates, multi-page gammas, exports to PDF/PPTX/PNG, and management of existing gammas.
author: main
tools: [web_fetch, filesystem, datetime, exec]
commands: []
icon: 🎨
version: 1.0.3
---
# Gamma Presenter

Generate polished presentations, documents, webpages, and social media posts using the Gamma API.

## Prerequisites

- Gamma account with Pro, Ultra, Team, or Business plan (required for API key)
- API key stored in `credentials/gamma-api-key.txt`

## API Key Setup

1. Log in to Gamma at https://gamma.app
2. Go to **Settings → API Keys**
3. Generate a new API key (starts with `sk-gamma-`)
4. Save it to `credentials/gamma-api-key.txt` in this skill's directory

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
  -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
  -d '{
    "inputText": "Your content or prompt here",
    "textMode": "generate",
    "format": "presentation",
    "numCards": 10,
    "theme": "theme-id-here",
    "language": "en",
    "exportAs": "pdf"
  }'
```

**textMode options:** `generate` (AI writes), `assist` (AI helps refine), `paste` (no AI, use your text as-is)

**format options:** `presentation`, `document`, `webpage`, `social_post`

**exportAs options:** `pdf`, `pptx`, `png` (PNG returns a ZIP with one image per card)

### 2. Poll for Generation Completion

```bash
curl -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
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
  -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
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
  -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
  -d '{
    "format": "pptx"
  }'
```

Then poll: `GET /v1.0/exports/{exportId}`

### 5. Generate a Standalone Image

```bash
curl -X POST "https://public-api.gamma.app/v1.0/images" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
  -d '{
    "prompt": "A hero image of a futuristic city skyline at sunset",
    "aspectRatio": "16:9"
  }'
```

## Available Themes and Folders

### List Themes
```bash
curl -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
  "https://public-api.gamma.app/v1.0/themes"
```

### List Folders
```bash
curl -H "X-API-KEY: $(cat credentials/gamma-api-key.txt)" \
  "https://public-api.gamma.app/v1.0/folders"
```

## Workflow: Create → Poll → Download

When asked to create a Gamma presentation:

1. **Read the API key** from `credentials/gamma-api-key.txt` using the `filesystem` tool
2. **Build the request payload** based on user's specifications (format, numCards, theme, exportAs, etc.)
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
- Use `textMode: "paste"` when you already have the full content written

## Error Handling

| Status | Meaning | Action |
|---|---|---|
| `401` | Invalid API key | Check `credentials/gamma-api-key.txt` |
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
