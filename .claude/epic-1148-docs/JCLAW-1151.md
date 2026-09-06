# JCLAW-1151 — AGENTS.md fragment

**Target section:** `## Architecture` → `### Outbound HTTP — OkHttp 5`.

**Placement:** append as a new paragraph directly after the existing paragraph that
begins "All HTTP-client provisioning lives in `app/utils/HttpFactories.java`…" and
ends "…`SsrfGuard` plugs in a per-request DNS allow-list against tool-fetch SSRF)."

## Prose to merge (the two sentences the AC asks for)

Because every call site reaches the transport through those factory methods, it is
substitutable in one place: `HttpFactories.runWith(client, body)` — and its
value-returning twin `callWith` — binds a `ScopedValue` that all six accessors (the
three tiers plus their SSRF-guarded variants) honour for the dynamic extent of
`body`, so a test installs a canned-response OkHttp interceptor instead of standing
up a mock server on a port. Nothing binds it in production, and a `ScopedValue`
does not follow an unrelated thread, so one test class cannot leak a transport into
another that play1 is running concurrently — but for that same reason the binding
does not reach Play's own request threads, and a `FunctionalTest` driving a
controller still needs a per-collaborator seam such as
`WhatsAppCloudApiProbe.installForTest`.

## Optional third sentence (merge only if the section has room)

`ArchitectureTest` enforces both halves: OkHttp clients must come from
`HttpFactories`, and `java.net.Socket`, `java.net.ServerSocket` and
`URL.openConnection` are banned outside `services.printing` (LPD and JetDirect 9100
are socket protocols) and `services.LocalSidecarDaemon` (a loopback bind that probes
whether a port is already held).

## Notes for the integrator

- Fragment lives under `.claude/`, which `.gitignore` excludes; it was committed with
  `git add -f`. Delete it once merged so the tracked copy does not outlive its use.
- No AGENTS.md file was edited by this story.
