# Integration Architecture (Multi-Part)

How the `backend` (Play 1.x) and `frontend` (Nuxt 3) parts communicate, and how external channels integrate.

## Internal integration (backend ↔ frontend)

Two transports, one origin.

### 1. REST over `$fetch` (request/response)

- **Dev:** Nuxt's Nitro `devProxy` forwards `/api/**` from `:3000` to `http://localhost:9000/api` (see `frontend/nuxt.config.ts`). No CORS, same-origin from the browser's perspective.
- **Prod:** The SPA is built with `nuxi generate` and its `.output/public/` is copied into the Play dist at `public/spa/`. Play's routes map `/_nuxt/*` → `staticDir:public/spa/_nuxt` and a catch-all `GET /{path}` → `Application.spa` which serves `public/spa/index.html`. Backend + frontend therefore share origin `:9000`.
- **Auth:** Session cookie (`PLAY_SESSION`). Set by `POST /api/auth/login`, consulted by `AuthCheck @Before`, surfaced client-side by `useAuth.checkAuth()` which probes `GET /api/config`.
- **Unauthenticated:** Backend returns **HTTP 401** with `{"error":"Authentication required"}`. Login failure is also `401`. Authenticated-but-forbidden actions (e.g. disabling a system tool) return `403`. Client middleware treats any non-2xx from `checkAuth` as invalid session.

### 2. Server-Sent Events (server-push)

- Endpoint: `GET /api/events` (`ApiEventsController.stream`, backed by `services.NotificationBus`).
- Client: `frontend/composables/useEventBus.ts` opens a singleton `EventSource` once `useAuth.authenticated` is true. Event shape `{type, data}`; handlers registered per-type via `on(type, handler)`.
- Uses: streaming chat tokens (via `/api/chat/stream`, which is its own SSE — not the event-bus), plus broadcast events like `skill.promoted`.
- Reconnect: exponential backoff; counter resets on successful message.
- Auth gate: bus refuses to connect until authenticated — prevents an infinite 401-reconnect loop when session expires mid-session.

### 3. Chat streaming SSE (`/api/chat/stream`)

A dedicated SSE channel per chat request (not multiplexed through the event bus). Six event types: `init`, `token`, `reasoning`, `status`, `complete`, `error`. Client disconnect is detected by the backend in `AgentRunner.checkCancelled` (an `AtomicBoolean` polled between tool rounds and inside `LlmProvider` streaming handlers).

## Integration points (diagram)

```
Browser
  ├─ $fetch  ─────────────►  :9000/api/*               (REST, cookie session)
  ├─ EventSource ─────────►  :9000/api/events          (event-bus SSE)
  └─ EventSource ─────────►  :9000/api/chat/stream     (per-request chat SSE)

Slack / Telegram / WhatsApp
  └─ HTTPS POST ─────────►  :9000/api/webhooks/{chan}  (signature-verified)

Play backend
  ├─ Static ─────────────►  /_nuxt/* → public/spa/_nuxt
  └─ SPA fallback ────────►  Application.spa → public/spa/index.html
```

## External integration

### LLM providers (`app/llm/`)

- OpenAI-compatible HTTP/SSE calls to OpenAI, OpenRouter, and Ollama.
- Retries: max 3, backoff `[1s, 2s, 4s]`.
- Streaming: SSE parse inline; tool-call JSON accumulated across tokens.
- Per-provider differences handled via template methods in the sealed `LlmProvider` hierarchy.

### Outbound messaging (`app/channels/`)

- `SlackChannel` — Slack Web API (chat.postMessage etc.).
- `TelegramChannel` — Telegram Bot API.
- `WhatsAppChannel` — WhatsApp Cloud API.
- `WEB` — no outbound adapter; responses are DB-persisted as `Message` rows and picked up by the frontend via polling + SSE.

### Inbound webhooks

- Telegram: path-secret (`/api/webhooks/telegram/{secret}`).
- Slack: in-controller signature verification.
- WhatsApp: GET `verify` handshake + POST webhook.

All webhook inbounds flow: `WebhookXController.webhook` → signature verify → `AgentRouter.resolve(channelType, peerId)` → `AgentRunner.run` (queued via `ConversationQueue`) → `ChannelType.resolve().send(...)`.

### Browser automation

- Microsoft Playwright for Java 1.52. Chromium installed into `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` during Docker build (no runtime download). Reaped by `BrowserCleanupJob`.

### File/document handling

- Apache Tika 3.2 for text extraction from uploaded files (`app/tools/DocumentsTool.java`).
- `app/services/scanners/` integrations: VirusTotal, MetaDefender, MalwareBazaar (binary scanning before promotion/allowlisting).

## Data sharing

- **No shared schema** across parts. `frontend/types/api.ts` is a hand-maintained mirror of backend JSON shapes; there is no code generation today.
- Entity IDs are plain `Long`; timestamps are ISO-8601 strings over the wire (Gson default).

## Shared deploy artifact

A single Play dist zip contains both runtimes. The Jenkins `Package` stage injects `frontend/.output/public/` into `<dist>/public/spa/`, and the Dockerfile does the same across build stages. There is no independent frontend release pipeline.

## Failure modes to know about

- **Frontend alone works dev-mode against a running backend**, but a pure `pnpm build`-only deploy will 4xx every `/api/*` call — the backend must be present to serve the origin.
- **SSE through reverse proxies** requires `proxy_buffering off` (see `conf/nginx.example.conf`).
- **Cookie session bound to backend origin** — if fronting with a proxy that rewrites the path prefix, `application.session.path` may need adjusting.
