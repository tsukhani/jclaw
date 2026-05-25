# Radarr Skill Credentials

The radarr skill family (radarr, radarr-delete, radarr-library,
radarr-recommend) reads runtime configuration from a single file at:

```
workspace/main/skills/radarr/credentials/radarr.json
```

This directory holds **only this README** — the actual `radarr.json` is
operator-local and lives under the gitignored `workspace/main/skills/`
copy, populated once by the operator after cloning. The skill is
shared; the secrets are not.

## Expected `radarr.json` shape

```json
{
  "radarr": {
    "url": "https://radarr.example.com",
    "apiKey": "<32-char hex string from Radarr → Settings → General → Security>",
    "username": "<basic-auth user, if Radarr sits behind a reverse proxy>",
    "password": "<basic-auth password, if applicable>"
  },
  "torrents": {
    "url": "https://torrents.example.com",
    "username": "<Transmission RPC user>",
    "password": "<Transmission RPC password>",
    "rpcPath": "/transmission/rpc"
  },
  "synology": {
    "url": "https://nas.example.com",
    "username": "<DSM account with FileStation API access>",
    "password": "<URL-encoded if it contains special characters>"
  },
  "paths": {
    "volumePrefix": "/volume1",
    "syncEnglish": "/video/Sync/English",
    "syncHindi": "/video/Sync/Hindi",
    "libraryEnglish": "/video/Movies",
    "libraryHindi": "/video/Hindi Movies",
    "downloadsComplete": "/downloads/complete"
  }
}
```

## Field reference

| Section | Field | Used by | Notes |
|---|---|---|---|
| `radarr.url` | base URL with scheme + host | every skill | no trailing slash |
| `radarr.apiKey` | Radarr API key | every skill | sent as `X-Api-Key` header |
| `radarr.username` / `password` | basic-auth credentials | every skill | only if Radarr is behind an auth proxy |
| `torrents.url` | Transmission base URL | radarr, radarr-delete | the skills call `<url>/transmission/rpc` |
| `torrents.username` / `password` | Transmission RPC auth | radarr, radarr-delete | sent as HTTP Basic |
| `torrents.rpcPath` | RPC endpoint path | (informational) | the skill hard-codes `/transmission/rpc`; this field documents the convention |
| `synology.url` | Synology DSM base URL | radarr-delete | for FileStation cleanup of leftover download folders |
| `synology.username` / `password` | DSM account | radarr-delete | password must be URL-encoded if it contains `%`, `&`, `+`, etc. |
| `paths.volumePrefix` | DSM volume root | radarr-delete | typically `/volume1` |
| `paths.syncEnglish` / `syncHindi` | inbox folders | (informational) | where Radarr drops imports before final move |
| `paths.libraryEnglish` / `libraryHindi` | final library folders | radarr, radarr-delete | where Radarr stores managed movies |
| `paths.downloadsComplete` | Transmission download cache | radarr | the skill triggers `DownloadedMoviesScan` here |

## Setup

After `git clone` + `./jclaw.sh setup`:

```bash
mkdir -p workspace/main/skills/radarr/credentials
$EDITOR workspace/main/skills/radarr/credentials/radarr.json
```

Paste the shape above, fill in your values, save. The skill reads from
`skills/radarr/credentials/radarr.json` relative to the agent's workspace
root, which `AgentService.workspacePath` resolves via parent-chain walk
to `workspace/main/skills/radarr/credentials/radarr.json`.

## What this directory must NOT contain

- The actual populated `radarr.json` — that goes under `workspace/main/`.
- Any file with real API keys, bot tokens, or passwords.
- Anything outside this README and (optionally) a sample template named
  `radarr.json.example` if you need an explicit shape file for tooling.

If you accidentally commit a real `radarr.json` here, treat the
contents as compromised: rotate the Radarr API key, the Transmission
RPC password, and the Synology account password before reverting.
