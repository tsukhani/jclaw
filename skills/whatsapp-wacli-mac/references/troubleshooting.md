# wacli troubleshooting

Moved from the agent's AGENT.md on 2026-11-09. Installed binary: `skills/whatsapp-wacli-mac/tools/wacli`, v0.25.1 (reinstalled 2026-10-27; sessions survived, no re-auth needed).

## "Client outdated (405)" fix (learned 2026-10-21)
Symptom: every `wacli send` fails with `Client outdated (405) connect failure (client version: 2.3000.1041485407)`. `wacli doctor` shows `AUTHENTICATED true` but `CONNECTED false` and `CONNECTION_STATE disconnected`. Not a lock, not a credentials problem, and re-auth will NOT fix it.
Cause: the bundled binary hardcodes a WhatsApp Web client version that WhatsApp has since retired. The client version lives in whatsmeow's `store/clientpayload.go` (`waVersion`), NOT in wacli source, so a wacli rebuild against the same whatsmeow pin does NOT help. Upstream whatsmeow `main` had `2.3000.1050728964` while the bundled build had `2.3000.1041485407`.
Fix: upgrade the wacli binary, which pulls a newer whatsmeow.
- Installed in skill at `skills/whatsapp-wacli-mac/tools/wacli`. Upstream is `github.com/openclaw/wacli` (the local source tree at `~/wacli` is a stale fork of `steipete/wacli`, do not build from it).
- Check latest: `curl -s https://api.github.com/repos/openclaw/wacli/releases/latest | grep tag_name`
- Download asset pattern: `wacli_<version>_darwin_arm64.tar.gz` plus `checksums.txt` from the same release. Verify with `shasum -a 256` against the published checksum BEFORE installing.
- Extract, `xattr -d com.apple.quarantine`, install over the skill copy, keep the old binary as `wacli.v<old>.bak`.
- The new binary auto-migrates the store at `~/.wacli` (adds `session.db`). Back up `~/.wacli` first.
- Confirm the fix with a read-only call, e.g. `wacli chats list --limit 3`, before sending anything.
- v0.11.1 -> v0.23.0 upgrade (2026-10-21): `send file` flags are backwards compatible; auth and store carried over with no re-auth needed.

Related gotchas in this skill:
- Stale `~/.wacli/LOCK` (pid of a dead process) blocks the next command. Check `ps aux | grep wacli`; if the pid is gone, `rm -f ~/.wacli/LOCK`.
- The lock is per-command, not per-session. Clear it before every wacli invocation in an agent loop.
- Verify delivery with `wacli messages list --chat <num> --from-me --limit N --json` (`chats messages` is not a valid subcommand; `messages list` is).
- `Failed to sync app state ... mismatching LTHash` on send is a benign app-state sync warning, not a send failure. The send still succeeded. Judge by the `Sent ... to ...` line and exit code.
- A send to a phone number resolves to a `@lid` JID (an opaque number followed by `@lid`) in the success line. That is normal, not an error.
