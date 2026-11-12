# Secrets in a skill

A skill never holds an API key, token or password. JClaw keeps each one in a secret store that only the operator fills, in the skill's Secrets panel on the Skills page. This file says how a skill declares its secrets and uses them.

## How a secret reaches a command

- A shell command gets a secret when it names it, as `$NAME` or `${NAME}`, or when it runs in the skill's folder or names it, as `skills/{skill-name}/`. A command started in the background gets none.
- The variable holds a stand-in that starts with `jclaw-secret-`, never the value. JClaw puts the real value where the stand-in stands, only in HTTPS requests to that secret's hosts: in the address, a header, a sign-in or the body. A request that carries it anywhere else is refused.
- So use `$NAME` only in a request to its own service. Do not print it, compare it, or save it: it is not the real value.
- Only HTTPS requests carry a secret. A command that connects by SSH or to a database cannot use one.
- A session id or key that a sign-in returns is ordinary text, not a stand-in: never print or store it.
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

## 3. Settings are not secrets

An address, a user name or a path is a setting. Keep it in a file under `credentials/`, such as `credentials/config.json` holding `{"baseUrl": "", "user": ""}`, and let the user fill it in. The skill's commands read it through `exec`, for example `"$(jq -r .baseUrl skills/{skill-name}/credentials/config.json)"`. The filesystem tool writes files in `credentials/` and does not read them. Never put a secret there.

## 4. Tell the user

When you confirm the skill, name each secret and say where it goes: "Open the skill from this agent's column on the Skills page and add each secret in its Secrets panel, with the host of your service." Never ask for a value, and never offer to add one yourself.

## Refactoring a skill that keeps a secret in a file

1. Add the `## Secrets` section with its `secrets` block.
2. Replace each command that reads the file, such as `$(cat skills/x/credentials/api-key.txt)` or `$(jq -r .apiKey ...)`, with `$NAME`.
3. Do not read the value and do not copy it anywhere. Tell the user to add it in the Secrets panel, and to remove it from the old file once the skill works.
