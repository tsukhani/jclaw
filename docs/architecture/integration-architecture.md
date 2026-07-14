# Integration Architecture (Multi-Part)

How the `backend` (Play 1.x) and `frontend` (Nuxt 4) parts communicate, and how external channels integrate.

## Internal integration (backend ↔ frontend)

Two transports, one origin.

### 1. REST over `$fetch` (request/response)

- **Dev:** Nuxt's Nitro `devProxy` forwards `/api/**` from `:3000` to `http://localhost:${JCLAW_BACKEND_PORT||9000}/api` (see `frontend/nuxt.config.ts`). No CORS — same-origin from the browser's perspective.
- **Prod:** The SPA is built with `nuxi generate` and its `.output/public/` is staged into `public/spa/` (by `jclaw.sh` on bare metal, or baked at image-build time by the Dockerfile). Play maps `/_nuxt/*` → `staticDir:public/spa/_nuxt` and a catch-all `GET /{path}` → `Application.spa` (serves `public/spa/index.html`). Backend + frontend share origin `:9000` / `:9443`.
- **Auth:** Session cookie (`PLAY_SESSION`), set by `POST /api/auth/login`, consulted by `AuthCheck @Before`, surfaced client-side by `useAuth` (which probes the auth-status endpoint). The in-process `jclaw_api` tool authenticates separately with a Bearer `ApiToken`.
- **Validation:** high-risk reads go through `useApiParsed` (Zod schemas in `types/schemas.ts`); mismatches throw a distinct `SchemaParseError`.
- **Unauthenticated:** Backend returns **HTTP 401**; authenticated-but-forbidden actions return **403**. Client middleware treats any non-2xx from the auth check as an invalid session.

### 2. Server-Sent Events (server-push)

- Endpoint: `GET /api/events` (`ApiEventsController.stream`, backed by `services.NotificationBus`).
- Client: `frontend/composables/useEventBus.ts` opens a singleton `EventSource`; handlers registered per-type via `on(type, handler)`.
- Uses: broadcast events like `skill.promoted` and streaming notifications.
- Reconnect: exponential backoff; survives navigation.

### 3. Chat streaming SSE (`/api/chat/stream`)

A dedicated SSE channel per chat request (not multiplexed through the event bus). Event types: `init`, `token`, `reasoning`, `status`, `complete`, `error`. Client disconnect is detected by `CancellationManager` (polled between tool rounds and inside `LlmProvider` streaming handlers).

## Integration points (diagram)

```
Browser (Nuxt 4 SPA)
  ├─ $fetch ──────────────►  :9000/api/*               (REST, cookie session)
  ├─ EventSource ─────────►  :9000/api/events          (event-bus SSE)
  └─ EventSource ─────────►  :9000/api/chat/stream     (per-request chat SSE)

Slack / Telegram / WhatsApp
  ├─ HTTPS POST ─────────►  :9000/api/webhooks/{chan}  (signature-verified)
  └─ polling / socket ───►  Telegram long-poll, Slack Socket Mode, WhatsApp-Web (Cobalt)

Play backend
  ├─ Static ─────────────►  /_nuxt/* → public/spa/_nuxt
  └─ SPA fallback ────────►  Application.spa → public/spa/index.html
```

## External integration

### LLM providers (`app/llm/`)

- OpenAI-compatible HTTP/SSE calls (via **OkHttp 5** + `okhttp-sse`, provisioned by `utils.HttpFactories`) to OpenAI, Ollama, OpenRouter, and TogetherAI, plus local probes (Ollama, LM Studio, vLLM).
- Retries: max 3, backoff `[1s, 2s, 4s]`.
- Streaming: SSE parse inline; tool-call JSON accumulated across tokens.
- Per-provider differences handled via template methods in the sealed `LlmProvider` hierarchy.

### Outbound messaging (`app/channels/`)

- `SlackChannel` — Slack Web API (HTTP webhook + Socket Mode).
- `TelegramChannel` — Telegram Bot API (long-polling + webhook).
- `WhatsAppChannel` — WhatsApp **Cloud API**; `WhatsAppCobaltChannel` — **WhatsApp-Web** (QR-paired, group-capable) via the `it.auties.whatsapp` (Cobalt) library. `WhatsAppChannelFactory` selects the transport per binding.
- `WebChannel` — no outbound push; responses are DB-persisted as `Message` rows and streamed to the frontend over SSE.
- Dispatch goes through `ChannelRegistry`; each adapter has its own access policy, approval callback, inbound parser, and streaming sink.

### Inbound webhooks

- Telegram: path-secret (`/api/webhooks/telegram/{bindingId}/{secret}`) with per-binding rate limiting.
- Slack: in-controller signature verification (`/api/webhooks/slack/{bindingId}` + `/interactive`).
- WhatsApp: GET verify handshake + POST webhook.

### Browser automation

- Microsoft Playwright for Java (version pinned in `build.gradle.kts`). Chromium installed into `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` during Docker build (no runtime download). Reaped by `BrowserCleanupJob`.

### File / document handling & media

- Apache Tika 3 for text extraction (+ Tesseract OCR) in `app/tools/DocumentsTool.java`.
- `app/services/scanners/` integrations (VirusTotal, MetaDefender, MalwareBazaar, etc.) scan skill binaries before promotion/allowlisting.
- Local image/video generation runs in Python `sidecar/` daemons launched on demand and reached over `127.0.0.1`.

## Data sharing

- **No shared schema** across parts. `frontend/types/api.ts` mirrors backend JSON shapes (compile-time); `frontend/types/schemas.ts` carries the Zod runtime schemas for boundary-validated reads. No cross-language code generation today.
- Entity IDs are plain `Long`; timestamps are ISO-8601 strings over the wire (`GsonHolder` with a custom `Instant` adapter).

## Shared deploy artifact

The 1.13.x `playBundle` task writes a self-contained `dist/jclaw-bundle.zip` containing both the precompiled backend AND the static SPA — `nuxi generate` output is copied into `public/spa/`, which `playBundle` packs along with `precompiled/`, `conf/`, the framework jar + lib, Gradle-resolved deps, and a `./play` launcher. There is no independent frontend release pipeline. The Docker image stages the SPA at image-build time via a multi-stage `COPY` rather than reusing the bundled copy, but the bundle artifact itself carries the SPA bytes.

## Failure modes to know about

- **Frontend alone works in dev-mode against a running backend**, but a pure `pnpm build`-only deploy will 4xx every `/api/*` call — the backend must be present to serve the origin.
- **SSE through reverse proxies** requires `proxy_buffering off` for `/api/events` and `/api/chat/stream`.
- **Cookie session bound to backend origin** — if fronting with a proxy that rewrites the path prefix, `application.session.path` may need adjusting.
