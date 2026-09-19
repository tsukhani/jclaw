# JCLAW-1227 — doc fragment

**Target AGENTS.md section:** `## Known pitfalls`, the "Personal Edition" bullet — extend the
existing `isAgentOriginated` list and add the path-normalisation rule. The second block below is a
new sentence for the `jclaw_api` description inside that same bullet.

---

## Edit 1 — extend the Personal Edition bullet's exception list

Replace the existing clause:

> The exception is whatever reaches past an agent's own grants, which refuses the agent principal
> via `RequestPrincipal.isAgentOriginated`: config writes (JCLAW-1022), tool grants, skill shell
> allowlists and `acpAllowed` (JCLAW-1023), any agent's workspace files (JCLAW-1058) and standing
> approvals (JCLAW-1062).

with:

> The exception is whatever reaches past an agent's own grants, which refuses the agent principal
> via `RequestPrincipal.isAgentOriginated`: config writes (JCLAW-1022), tool grants, skill shell
> allowlists and `acpAllowed` (JCLAW-1023), any agent's workspace files including the binary serve
> route (JCLAW-1058, JCLAW-1227), standing approvals (JCLAW-1062), and the writes that decide what
> an agent can be granted later — WhatsApp binding CRUD, the custom ACP-harness commands a
> `runtime=acp` spawn executes, and the four global-skill-registry writes (import, promote, rename,
> delete) (JCLAW-1227).

## Edit 2 — new sentences for the same bullet

Append:

> The `jclaw_api` deny layers gate `HttpUrl.encodedPath()`, never the model's string: OkHttp
> resolves dot-segments while parsing, so `/api/skills/x/files/../../../logs` checked raw passed
> both layers and left as `/api/logs`, defeating the whole `PATH_BLOCKLIST` through any wildcard
> route (JCLAW-1227). Parse first, then gate; `%2e%2e` and a backslash normalise the same way and a
> `#` truncates. A workspace file is served `attachment` with `Content-Security-Policy: sandbox`
> unless it is a raster image, audio or PDF — the app declares no global CSP, so that response
> carries its own.

---

## Why, for the reviewer

1. **The traversal was measured, not reasoned.** Against the shipped `okhttp-jvm-5.5.0`,
   `HttpUrl.parse("http://127.0.0.1:9000/api/skills/x/files/../../../logs").encodedPath()` is
   `/api/logs`. Two forms the ticket did not name behave the same: `%2e%2e` also resolves to
   `/api/logs` (so a raw-string check alone cannot catch it — the ordering is the fix), and
   `/api/foo\bar` parses as `/api/foo/bar`. `#` truncates. `//` is *not* collapsed, so that clause
   is belt-and-braces rather than a live bypass.
2. **Why the gate had to move rather than gain a check.** The exploit needs a non-`@ChatHidden`
   route whose pattern swallows the traversal. `conf/routes` has two, both `{<.+>filePath}` and
   both GET. Gating the parsed path removes the class of bug instead of the two instances.
3. **`serveWorkspaceFile` was the seventh of seven.** The four Workspace Manager routes added after
   the JCLAW-1058 audit all carry `requireOperatorForWorkspace()` + `@ChatHidden`; this one carried
   neither, so it served any agent's workspace bytes with a content type from the extension and no
   CSP. SVG and `text/html` both matched the old `image/` + PDF inline test.
4. **The ACP write moved to `setWithSideEffects`.** `subagent.acp.customCommands` matches no
   `PrivilegedConfig` rule today, so behaviour is unchanged — the point is that a rule added for
   that key later applies to `AcpHarnessProbe` too, not only to `POST /api/config`.
5. **`toSkillMd` collapses scalars to one line** rather than quoting them. The AC allows either.
   `SkillLoader`'s frontmatter readers are regexes that strip surrounding quotes but do not decode
   `\n`, so a quoted-and-escaped value would read back with literal backslashes; one line is
   lossless for this reader and still closes the injection.
6. **Out of scope, as the ticket says:** the ArchUnit rule is JCLAW-1253. The nearest thing that
   exists is `JClawApiToolTest.theCallableApiSurfaceIsExactlyTheseRoutes`, a golden list of every
   route an agent can drive — this story removed ten lines from it.
