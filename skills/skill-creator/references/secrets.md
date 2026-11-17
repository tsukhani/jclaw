# Secrets in a skill

A skill's folder holds only SKILL.md, references/ and tools/: never an API key, token or password, and never a settings file. The operator fills in each value the skill needs, in the skill's Secrets panel on the Skills page. This file says how a skill declares those values and uses them.

Contents: how a secret reaches a command; 1. the `## Secrets` section; 2. `$NAME` in commands; 3. settings; 4. renewed
secrets; 5. what a skill saves between runs; 6. telling the user; 7. checking the draft before saving it; refactoring a
skill that keeps values in a file.

## How a secret reaches a command

- A shell command gets a secret when it names it, as `$NAME` or `${NAME}`, or when it runs in the skill's folder or names it, as `skills/{skill-name}/`. A command started in the background gets none.
- The variable holds a stand-in that starts with `jclaw-secret-`, never the value. JClaw puts the real value where the stand-in stands, only in HTTPS requests to that secret's hosts: in the address, a header, a sign-in or the body. A request that carries it anywhere else is refused.
- So use `$NAME` only in a request to its own service. Do not print it, compare it, or save it: it is not the real value.
- Only HTTPS requests carry a secret. A command that connects by SSH or to a database cannot use one.
- A session key that a sign-in over HTTPS returns is a renewed secret (section 4): JClaw stores it, and the command gets its stand-in where the key was. Never parse, print or save it.
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

## 4. A key that a sign-in returns: renewed secrets

Some services hand out a session key when the skill signs in, and the skill signs in again when the key goes stale, on a 401. Declare that key as a renewed secret. Its line starts with `renewed`, gives an example host, and after `from` suggests where the key is in the sign-in's answer: the method, the path on the host, then `json` and the field, or `header` and the header's name. After `using` it names the secret the sign-in sends and, after `in`, where it sends it: `json` and the field, `form` and the field, `header` and the header's name, or `basic` for the password of Basic auth.

```secrets
setting SHOP_USER=admin                        # The sign-in user name
SHOP_PASSWORD=api.example-shop.com             # The sign-in password
renewed SHOP_SESSION_KEY=api.example-shop.com from POST /v1/sessions json session using SHOP_PASSWORD in json password   # The session key the sign-in returns
setting SHOP_URL=https://api.example-shop.com/v1   # The service's address, with https://
```

- The path is the whole path on the host, as the service receives it: `/v1/sessions` here, though the command writes `$SHOP_URL/sessions`. A redirect that answers the sign-in counts as its answer. JClaw follows no redirect, so when the key comes from the page a redirect leads to, give that page's path.
- `json` takes a field at the top of the answer's JSON object, such as `session` in `{"session": "…"}`, never one inside another object, and `in json` names a top-level field of the sign-in's body the same way. `in form` may name a nested field, such as `in form user[password]`. `header` takes a response header, but never a cookie: a rule cannot take `Set-Cookie`. A session kept in a cookie stays in the skill's cookie jar (section 5).
- The operator confirms the rule in the skill's Secrets panel, and JClaw takes nothing until they do. The line only suggests it: changing the line later changes nothing JClaw does.
- JClaw takes the key only from a sign-in that sends the secret after `using` exactly once, as its variable, in the place after `in`: here `"password": "$SHOP_PASSWORD"` in a JSON body. Write the variable there and nowhere else in that request. JClaw puts the real value wherever the variable stands, so a sign-in that sends it twice, or in another field, a header, the URL or Basic auth, gets its answer withheld (HTTP 502, below), and JClaw stores nothing. A sign-in with a password the command typed itself passes as before. A sign-in that renews the key by sending the key itself names the renewed secret: `using SHOP_SESSION_KEY in header api-key`, and the operator enters its current key to start with.
- When the sign-in answers with a success, JClaw stores the new key, and the command receives the stand-in of `$SHOP_SESSION_KEY` where the key was. That stand-in carries the new key at once, and the next command gets it too. JClaw replaces the key where it appears as it took it: a copy the service also sends back written another way, such as percent-encoded, is not replaced.
- So send `$SHOP_SESSION_KEY` in each call, and on a 401 sign in and retry with the same variable. Never read the key out of the answer, and never save it in a file.
- If `$SHOP_SESSION_KEY` is not set, or a call still answers 401 right after a sign-in that succeeded, the renewed secret or its rule is missing: stop, and ask the operator to add them in the skill's Secrets panel.
- A renewed secret is never required: it starts empty, and the first sign-in fills it.
- If the sign-in's answer does not hold the key where the rule says, JClaw withholds the whole answer: the command gets HTTP 502 and `JClaw withheld this response: renewing a secret from it failed.` So check the sign-in's status, and on a failure print that answer and stop: tell the user to check the rule in the Secrets panel.

```bash
: "${SHOP_SESSION_KEY:?SHOP_SESSION_KEY is not set: ask the operator to add it, with its rule, in the Secrets panel}"
me() { curl -s -o /dev/null -w '%{http_code}' -H "api-key: $SHOP_SESSION_KEY" "$SHOP_URL/me"; }
if [ "$(me)" = 401 ]; then
  reply=$(curl -s -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d "{\"user\": \"$SHOP_USER\", \"password\": \"$SHOP_PASSWORD\"}" "$SHOP_URL/sessions")
  status=${reply##*$'\n'}
  case "$status" in
    2??|3??) ;;
    *) echo "Sign-in failed (HTTP $status): ${reply%$'\n'*}" >&2; exit 1 ;;
  esac
  if [ "$(me)" = 401 ]; then
    echo "Signed in, but the session key still fails: SHOP_SESSION_KEY or its rule is missing. Ask the operator to check it in the Secrets panel." >&2
    exit 1
  fi
fi
curl -s -H "api-key: $SHOP_SESSION_KEY" "$SHOP_URL/me"
```

## 5. What a skill saves between runs

A skill that keeps a secret for its next run that JClaw cannot renew for it, such as a cookie jar, writes it under `skill-state/{skill-name}/` in the agent's workspace, never in its own folder, with its commands through `exec`:

```bash
mkdir -p skill-state/{skill-name} && curl -s -c skill-state/{skill-name}/cookies.txt -b skill-state/{skill-name}/cookies.txt "$SITE_URL/"
```

The filesystem and documents tools refuse that folder, as it can hold a live credential: only the skill's commands read and write it. Removing the skill from the agent removes it.

Keep only secrets there. JClaw masks what it finds in that folder wherever a tool's result shows it: a file of one word whole, and any long string of letters and digits in other files. So anything else the skill keeps between runs, such as a last-seen id, a cache or a folder path, goes in `skill-data/{skill-name}/`, where the agent can read it and nothing masks it.

## 6. Tell the user

When you confirm the skill, name each secret and setting and say where it goes: "Open the skill from this agent's column on the Skills page and fill in each one in its Secrets panel; give each secret the host of your service." For a renewed secret, ask the user to add it with its hosts and check its capture rule, the secret its sign-in sends included; it needs no value. Never ask for a value, and never offer to add one yourself.

## 7. Check the draft before saving it

Run these over the whole draft, SKILL.md and every file under `tools/`, before you write it, and again after each fix.

**What it reads.** List every name it takes from the environment:
- in a shell command or script: `$NAME`, `${NAME}`, `${NAME:-x}`, and `printenv NAME`;
- in Python: `os.environ["NAME"]`, `os.environ.get("NAME")`, `os.getenv("NAME")`;
- in Node: `process.env.NAME` and `process.env["NAME"]`; in Ruby: `ENV["NAME"]` and `ENV.fetch("NAME")`; in Go: `os.Getenv("NAME")`.
Each one must be on a line of the `secrets` block. A name with a part such as `KEY`, `PASS`, `TOKEN`, `SECRET`, `PWD`, `COOKIE`, `AUTH` or `SESSION` is a secret (section 3), whatever the line says. `HOME`, `PATH`, `TMPDIR` and the like, which every command has, need no line.

**Where a value comes from.** Nothing may read a key, token or password from a file: no `cat`, `jq`, `source`, `open(...)`, `readFileSync`, dotenv loader or config parser aimed at a credentials file, a `credentials/` folder, a `.env` or `.netrc` file, or `skill-state/`. The one exception, until JClaw's secrets proxy holds cookies for a skill, is a cookie jar the skill reads and writes under `skill-state/{skill-name}/` (section 5). No file of the skill holds a value either: an example in SKILL.md is a placeholder such as `api.example.com`, never a real key.

**A sign-in.** A step that signs in and keeps what comes back is a `renewed` secret with a suggested rule (section 4). A step that writes that key to a file is a fault, `skill-state/` included.

**The folder.** `SKILL.md` at the root, and beside it only `tools/` and `references/`: no `credentials/`, no other folder, no other file.

A draft that fails any of these is fixed before it is saved: never save it and fix it afterwards, as an agent offered the half-made skill could run it.

## Refactoring a skill that keeps values in a file

1. Add the `## Secrets` section with its `secrets` block: a line for each key, token or password, and a `setting` line for each address, user name or path.
2. Replace each command that reads the file, such as `$(cat skills/x/credentials/api-key.txt)` or `$(jq -r .baseUrl ...)`, with `$NAME`.
3. Declare a session key the skill signs in for as a renewed secret (section 4), and drop the step that saved it. Move anything else the skill writes between runs to `skill-state/{skill-name}/` (section 5).
4. Do not read the values and do not copy them anywhere: the filesystem tool cannot open a credentials folder anyway. Tell the user to fill each one in in the Secrets panel, and to delete the old file once the skill works.
