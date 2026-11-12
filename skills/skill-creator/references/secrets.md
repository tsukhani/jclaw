# Secrets in a skill

A skill's folder holds only SKILL.md, references/ and tools/: never an API key, token or password, and never a settings file. The operator fills in each value the skill needs, in the skill's Secrets panel on the Skills page. This file says how a skill declares those values and uses them.

## How a secret reaches a command

- A shell command gets a secret when it names it, as `$NAME` or `${NAME}`, or when it runs in the skill's folder or names it, as `skills/{skill-name}/`. A command started in the background gets none.
- The variable holds a stand-in that starts with `jclaw-secret-`, never the value. JClaw puts the real value where the stand-in stands, only in HTTPS requests to that secret's hosts: in the address, a header, a sign-in or the body. A request that carries it anywhere else is refused.
- So use `$NAME` only in a request to its own service. Do not print it, compare it, or save it: it is not the real value.
- Only HTTPS requests carry a secret. A command that connects by SSH or to a database cannot use one.
- A session id or key that a sign-in returns is ordinary text, not a stand-in: never print it. To reuse it on the next run, save it only in the skill's state folder (section 4).
- A name is used once on an agent. When another skill of the same agent already holds the secret, declare the same name: both skills' commands can use it.

## 1. Add a `## Secrets` section to the skill's SKILL.md

The section declares each secret in a fenced code block whose language is `secrets`, then tells the agent how to use them. JClaw reads the first such block in SKILL.md to fill the Secrets panel. Write the section like this:

````markdown
## Secrets

The operator adds these in this skill's Secrets panel on the Skills page. An agent cannot.

```secrets
WEATHER_API_KEY=api.example-weather.com   # The API key, from the account's Settings page
PROXY_PASS=api.example-weather.com        # Optional. The reverse proxy's password, when there is one
```

A command that names `$WEATHER_API_KEY` receives a stand-in, and JClaw puts the real key in only on requests to its host. Never look for the key in a file. If a call answers 401 or the variable is empty, the secret is missing: ask the operator to add it.
````

Each line of the block is `NAME=example-host   # what it is`:

- **NAME**: capital letters, digits and underscores, starting with a letter, at most 64 characters, such as `SONARR_API_KEY`. Not a name the shell already uses, such as `PATH`, `HOME`, `USER` or `HTTPS_PROXY`, and not one that starts with `JCLAW_`.
- **After `=`**: an example host only. A host name, with `:port` when the port is not 443, and no `https://` or path: `api.github.com`, `nas.example.com:5001`. Use the service's real host when everyone reaches the same one, and a made-up `example.com` name when each user runs their own. JClaw shows it as a hint; the operator types the real host.
- **After `#`**: what the secret is and where the operator finds it. Start it with `Optional` when the skill can run without the secret.
- **Never a value**, not even a sample one. A key in this block is treated as a leak.

Until every secret that is not marked Optional has a value, the skill shows **Needs secrets** and no agent is offered it.

Write one such block, at the top level of SKILL.md. A `secrets` block inside another code block is read as an example and declares nothing.

## 2. Use `$NAME` in the commands

```bash
curl -s -H "X-API-Key: $WEATHER_API_KEY" "https://api.example-weather.com/v1/today?city=Paris"
```

A skill that uses a secret runs its commands with the `exec` tool, so its `jclaw-tools` lists `exec`.

## 3. Settings: values that are not secret

**Secret or setting?** Ask what someone could do with the value if they saw it. If they could sign in, call the service, or act as the operator, it is a secret; otherwise it is a setting. When in doubt, declare a secret: a secret can hold any value, while a setting is shown to anyone who opens the panel and reaches every command as it is.

| Secret | Setting |
| --- | --- |
| API key, access token, personal access token | Service address, host or port |
| Password, passphrase, PIN | User name, account or tenant id |
| Client secret, signing secret, private key | OAuth client id, app id |
| Session id or cookie the operator copies in | Region, folder id, library path |
| The token part of a URL, such as a webhook's | A URL with no token in it |

A secret never holds a whole URL: the command needs the real host to connect to, and the stand-in is swapped only inside the request (its path, headers or body). For a webhook, the secret is the token part, its example host is the webhook's host, and the command writes the rest: `curl -s -X POST "https://hooks.slack.com/services/$SLACK_WEBHOOK_TOKEN" -d ...`.

An address, a user name or a path that differs from one install to the next is a setting. Declare it in the same block, on a line that starts with `setting`, with an example value:

```secrets
WEATHER_API_KEY=api.example-weather.com                       # The API key
setting WEATHER_BASE_URL=https://api.example-weather.com/v1   # The service's address, with https://
setting WEATHER_USER=admin                                    # Optional. The account's user name
```

The operator fills it in in the same panel, where its value is shown in clear. A command that names `$WEATHER_BASE_URL`, or runs in the skill's folder, gets the value itself, in the background too:

```bash
curl -s -H "X-API-Key: $WEATHER_API_KEY" "$WEATHER_BASE_URL/today?city=Paris"
```

A name that reads as a key, token or password (one with a part such as `KEY`, `PASS`, `TOKEN`, `SECRET`, `PWD`, `COOKIE`, `AUTH` or `SESSION`) is always a secret, even on a `setting` line: never label a password a setting. A value that is the same everywhere, or a default such as a city or a unit, is no setting: write it in SKILL.md. Never create a settings file such as `credentials/config.json`.

## 4. What a skill saves between runs

A skill that keeps something for its next run, such as a session key it renewed or a cookie jar, writes it under `skill-state/{skill-name}/` in the agent's workspace, never in its own folder, with its commands through `exec`:

```bash
mkdir -p skill-state/{skill-name} && printf '%s' "$SESSION_KEY" > skill-state/{skill-name}/session-key
```

The filesystem and documents tools refuse that folder, as it can hold a live credential: only the skill's commands read and write it. Removing the skill from the agent removes it.

## 5. Tell the user

When you confirm the skill, name each secret and setting and say where it goes: "Open the skill from this agent's column on the Skills page and fill in each one in its Secrets panel; give each secret the host of your service." Never ask for a value, and never offer to add one yourself.

## Refactoring a skill that keeps values in a file

1. Add the `## Secrets` section with its `secrets` block: a line for each key, token or password, and a `setting` line for each address, user name or path.
2. Replace each command that reads the file, such as `$(cat skills/x/credentials/api-key.txt)` or `$(jq -r .baseUrl ...)`, with `$NAME`.
3. Move anything the skill writes between runs to `skill-state/{skill-name}/` (section 4).
4. Do not read the values and do not copy them anywhere: the filesystem tool cannot open a credentials folder anyway. Tell the user to fill each one in in the Secrets panel, and to delete the old file once the skill works.
