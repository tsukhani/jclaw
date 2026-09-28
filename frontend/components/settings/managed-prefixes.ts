// Top-level Config DB prefixes claimed by a UI domain. Any row whose key starts
// with one of these is owned somewhere in the app and is NOT unmanaged —
// regardless of which page actually manages it. Keeps Settings free of
// exact-key knowledge about other pages' config.
export const MANAGED_PREFIXES: readonly string[] = [
  'alerts.', // Operator alert destination (alerts.delivery) — Settings (Alerts). JCLAW-1279.
  'app.', // Operator-wide settings — Settings (Timezone). app.timezone = the
  // assistant's wall-clock zone injected into the system prompt.
  'provider.', // LLM providers — Settings
  'dispatcher.', // OkHttp dispatcher caps — Settings (Performance)
  'transcription.', // Transcription provider + local model — Settings (Transcription)
  'tts.', // TTS engine + per-engine model/port (tts.engine, tts.local.port, tts.<engine>.model) — Settings (Speech). JCLAW-789/793.
  'caption.', // Image captioning cloud + local model (caption.cloud.*) — Settings (Image Captioning)
  'video.', // Video interpretation frame-sample density (video.sampleFrames) — Settings (Video Interpretation)
  'imagegen.', // Image generation provider selection (imagegen.provider) — Settings (Image Generation)
  'videogen.', // Video generation provider + job timeout (videogen.provider) — Settings (Video Generation)
  'ocr.', // OCR backends — Settings (Tesseract today; GLM-OCR planned)
  'search.', // Search providers — Settings
  'scanner.', // Malware scanners — Settings
  'chat.', // Chat settings — Settings
  'memory.', // JCLAW-930/932: vector embedding provider, model and dimensions — Settings
  // (Memory Embeddings). Also covers memory.jpa.vector.backfilledForModel, written by
  // the backfill job rather than by an operator, and the recall/decay/autocapture
  // tuning keys, which are code defaults with no UI surface.
  'verification.', // JCLAW-836: tool-result check toggles — seeded by DefaultConfigJob, no UI surface
  'shell.', // Shell execution defaults — Settings (allowlist + timeout)
  'db.backup.', // JCLAW-1165: backup directory, retention and daily schedule — Settings (Database)
  'web_fetch.', // JCLAW-773: outbound-host allowlist — operator-set through the Settings API, no UI surface
  'web_scrape.', // Crawl, robots and sitemap settings for web_scrape — Settings (Web Scraping)
  'browser.', // JCLAW-1274: browser tool engine — Settings (Browser)
  'decision.', // JCLAW-1302: decision providers' keys (decision.jev.apiKey) — Settings (Decision Providers)
  'playwright.', // JCLAW-172: namespace retired but kept in the prefix list
  // so leftover playwright.enabled / playwright.headless rows on upgraded
  // installs don't surface as "Unmanaged" diagnostic noise.
  'skillsPromotion.', // Skills promotion sanitization — Settings
  'agent.', // Per-agent config (shell privileges, queue mode, etc.) — Agents page
  'ollama.', // Ollama provider-specific settings — Settings
  'upload.', // Per-kind attachment size caps (JCLAW-131) — Settings
  'auth.', // Admin password hash — Settings (Password section, not rendered as a row)
  'onboarding.', // First-login guided tour flag — written by ApiOnboardingController, no UI surface
  'pricing.', // LiteLLM nightly price-refresh toggle (JCLAW-28 follow-up) — Settings (LLM Providers section)
  'subagent.', // JCLAW-266: subagent recursion caps — Settings (Subagents section)
  'printer.', // JCLAW-911: default printer + its job options — Settings (Printers)
  'tasks.', // JCLAW-259: task retention TTL — Settings (Tasks section)
  'logs.', // Event log retention — Settings (Logging)
  'llm.', // Primary-provider pin and circuit breaker tuning — Settings (LLM Providers)
  'router.', // Model router lists and budget thresholds — Settings (Model Router)
  'otel.', // JCLAW-34: OpenTelemetry export, endpoint and sampling — Settings (Telemetry)
  'tool.approval.', // JCLAW-1022: off-channel approval policy — Settings (Tool Approvals)
  'voice.', // Turn detection, live transcripts and speech pacing — Settings (Voice Mode)
  'telegram.', // Channel defaults — Channels page (Telegram); approval timeout — Settings (Tool Approvals)
  'tailscale.', // Funnel enable/port (tailscale.funnel.*) — managed on the Channels page
  'jtokkit.', // Token-count safety multipliers — `jtokkit.safetyMultiplier.unmatched`
  // is the operator-tunable global in the Advanced subsection of Chat, and the
  // per-(provider, model) `jtokkit.safetyMultiplier.<provider>.<modelId>`
  // entries are written autonomously by TokenizerCalibrationJob every 30 min
  // based on observed provider-vs-jtokkit deltas — operators don't manage
  // these directly. Surfacing them here would imply they're stale or
  // operator-actionable, neither of which is true.
]

export function isManagedKey(key: string): boolean {
  return MANAGED_PREFIXES.some(p => key.startsWith(p))
}
