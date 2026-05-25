# Radarr Skill Credentials

The radarr skill family (radarr, radarr-delete, radarr-library,
radarr-recommend) reads runtime configuration from a single file at:

```
workspace/<agent>/skills/radarr/credentials/radarr.json
```

where `<agent>` is the agent that owns the workspace using the skill.
This directory holds **only this README** — the actual `radarr.json` is
operator-local and lives under the gitignored per-agent workspace copy.
The skill is shared across agents; the secrets are not.

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

Two paths, depending on what your agent has access to:

**With skill-creator access (easiest):**
Ask the skill creator to populate `radarr.json` under the radarr
skill's `credentials/` folder with values from the shape above. It
will write the file into the correct workspace location for your
agent.

**Manual:**
Create the file yourself at
`workspace/<agent>/skills/radarr/credentials/radarr.json`, paste the
shape above, fill in your values, save. The skill reads from
`skills/radarr/credentials/radarr.json` relative to the calling
agent's workspace root, which resolves to your agent's copy via the
workspace parent-chain lookup.

## What this directory must NOT contain

- The actual populated `radarr.json` — that lives under the per-agent
  workspace, never in the registry.
- Any file with real API keys, bot tokens, or passwords.
- Anything outside this README and (optionally) a sample template named
  `radarr.json.example` if you need an explicit shape file for tooling.

If you accidentally commit a real `radarr.json` here, treat the
contents as compromised: rotate the Radarr API key, the Transmission
RPC password, and the Synology account password before reverting.
