# Skills, Tools & MCP Servers

Out of the box, an agent can read your messages, reason, and reply. To do anything else (search the web, run a shell command, query Postgres, write a file, talk to your team's Jira) it needs **capabilities**. This section covers the three ways JClaw lets you add capabilities to an agent.

| Page                             | What it provides                                                  |
|----------------------------------|-------------------------------------------------------------------|
| [Skills](/skills)                | Reusable instruction bundles you attach to agents (markdown the agent reads when a task calls for it). |
| [Tools](/tools)                  | First-party capabilities built into JClaw (web fetch, shell, file system, search, etc.). |
| [MCP Servers](/mcp-servers)      | External servers that expose additional tools via the Model Context Protocol. |

Each page is a catalog. The actual binding of "which agent gets which capability" happens on the [Agents](/agents) page.

---

## Skills

A skill is a chunk of markdown: instructions, examples, a methodology. An agent reads it when a task calls for it. Its system prompt lists each attached skill's name and description, not the body. Use skills for capabilities you want to reuse across multiple agents:

- A coding style guide.
- A research methodology ("always cite, always cross-check").
- An output template ("respond in JSON with these fields").
- A persona ("you are a careful, thorough technical reviewer").

Skills can also contribute shell commands to an agent's effective allowlist (visible on the [Agents](/guide#agents) edit form under *Shell Allowlist*). So a "git wrangler" skill can grant the commands it needs, without you editing the global allowlist by hand.

### Global skills vs agent skills

The [Skills](/skills) page splits into two columns:

- **Global Skills** (left): the library you have built. Drag any global skill onto an agent on the right to assign it.
- **Agent Skills** (right): every agent, with the skills currently attached to each.

Assigning a skill runs two checks before anything is copied, and a failure shows as a banner on the page. First, every tool the skill's `tools:` frontmatter lists must exist and be enabled for that agent. Otherwise you get *Cannot add skill '…' to agent '…': missing tools — disabled: […]; unknown: […]. Enable the required tools for this agent and try again.* Second, JClaw malware-scans every binary file in the skill by SHA-256 hash, with the scanners configured in [Settings → Malware and Virus Scanners](/guide#settings-malware-and-virus-scanners), and a hit refuses with *malware detected*. With no scanner enabled, the scan is skipped. A scanner that errors or times out fails open. In both cases the skill is added. Verdicts are logged under category `scanner` in [Logs](/logs). Dragging a skill the agent already holds at the same or a newer version only re-enables it, and neither check runs.

You can also **promote** an agent-specific skill back into the global library by dragging it from the right column to the left. This is the workflow for refining a skill on one agent first, then making it reusable everywhere. Promotion routes the skill through an LLM sanitization pass; see [Settings → Skills Promotion](/guide#settings) for the provider/model/timeout knobs.

### Creating a skill

Skills are not written on this page. Two ways to get one:

- **Browse catalog**: the button above the Global Skills column opens the importable-skills catalogs: static ones (the GitHub/Mastra index, with search, sort and category facets) and the dynamic ClawHub registry (search plus paging). **Import** pulls a skill straight into **Global Skills** in one click.
- **Author it** with the built-in **skill-creator** skill: ask an agent (in [Chat](/chat)) to make a skill, and it writes one into that agent's workspace. A skill has a **Name** (short identifier), a **Description** (shown in the library), and **Content** (the markdown body the agent reads when it uses the skill). Once it is good, drag it from the agent's column to **Global Skills** to promote it for reuse.

### Viewing

Click any skill row to open a **read-only** viewer of its files. A global skill can be **renamed** inline (double-click its name in the Global Skills column) and **deleted** from its row; to change its content, edit it via the skill-creator skill in an agent's workspace (then re-promote if it is a global skill). Each agent runs its own copy of a skill, made when the skill was assigned. So a re-promoted global skill reaches an agent only once you assign it to that agent again; past conversations keep the prompt they were created with.

:::tip Start broad, then split
A skill that is too narrow gets duplicated. A skill that is too broad gets attached to agents that do not need most of it. When in doubt, start broad and split when you notice an agent ignoring half the skill's content.
:::

---

## Tools

The [Tools](/tools) page is a catalog of every built-in capability JClaw ships with. Each card shows:

- **Icon and name.**
- **Category**: `System`, `Web`, `Files`, or `Utilities`. (MCP-server tools are not listed here; they live on the [MCP Servers](#skills-tools-mcp-mcp-servers) page.)
- **Description**: what the tool does in one or two sentences.
- **Functions**: the individual actions the tool exposes. Expand the card to see the per-function detail.

Use the **category pills** at the top of the page to filter, and **Expand all** / **Collapse all** to control density.

### How tools become available to an agent

Tools listed here are *available*. To make a tool *active* for a specific agent:

1. Open [Agents](/agents).
2. Click the agent.
3. Scroll to **Tools** in the edit form.
4. Turn on the switch next to the tool.

A few tools require extra setup (an API key, a workspace path, a shell allowlist entry); that config lives under the matching [Settings](/guide#settings) section.

When `exec`, `filesystem`, `web_fetch` or `web_scrape` fails, the agent gets the error in three parts (what broke, what to check, and how to retry), so it can correct the call rather than repeat it.

:::note Why is this not just one page?
Tools are a *catalog*, and binding tools to agents is an *agent* concern. Keeping them on separate pages means the catalog stays clean as your roster of agents grows.
:::

### Printing

The `printer` tool discovers printers on the local network and sends jobs to them, with no CUPS, no `lp`, and no OS printing subsystem. It is JVM-native end to end, so it behaves identically on macOS, Linux, and Windows, and works inside a container that has no print stack at all.

Four actions: `discover` (mDNS/Bonjour scan), `print` (a workspace file by path, or literal text), `status`, and `cancel`.

JClaw tries three backends in order, and the order is about **how much each can tell you**, not speed:

| Backend | Port | Reports back? |
|---|---|---|
| IPP | 631 | Yes: job id and printer-reported state |
| Raw socket (JetDirect) | 9100 | No: a successful write only proves the bytes left this machine |
| LPD (RFC 1179) | 515 | Barely: a one-byte acknowledgement of receipt |

An agent can pass `protocol` (`IPP`, `IPPS`, `RAW` or `LPD`) to use that one backend, with no fallback.

JClaw surfaces that difference rather than hiding it. When a job goes out over raw socket or LPD, the tool's reply says explicitly that the backend cannot confirm the document printed. An agent reporting "printed successfully" off a blind write would be stating something it has no way to know.

**Job options.** `sides` (one-sided, two-sided-long-edge, two-sided-short-edge), `color` (color, monochrome, auto) and `media` (a paper size like `iso_a4_210x297mm`, or a tray name the printer advertises). Omit any of them to use the job options saved in [Settings → Printers](/guide#settings-printers), then the printer's own default.

These options are **IPP-only**. They travel as RFC 8011 job-template attributes, and the byte-stream backends have nowhere to put them. If a job asks for double-sided and then falls back to raw socket or LPD, it prints single-sided; the tool says so explicitly rather than letting you discover it from the paper.

:::caution Printing is physical and irreversible
Paper comes out of a device in someone's room and there is no undo. The tool never guesses a target. `print` goes to the printer the agent names, or to the default saved in [Settings → Printers](/guide#settings-printers), and refuses when there is neither. The tool is **off by default for every agent**. Turn it on deliberately, per agent, on the [Agents](/agents) page.
:::

If `discover` returns nothing, that is often the network rather than the printer: mDNS is link-local, so it is routinely blocked on VPNs and in containers without a multicast route. Pass the printer's address as `host` to bypass discovery. An address that is neither a discovered printer nor the saved default is treated as a dangerous action and goes through the same approval gate as `exec` (see [Settings → Tool Approvals](/guide#settings-tool-approvals)); a link-local, multicast or `0.0.0.0` address is refused outright.

### Image generation

`generate_image` draws an image from a text prompt and shows it inline in the reply. It is **off by default for every agent**, and it works only once you choose a backend in [Settings → Image Generation](/guide#settings-image-generation); until then a call returns an error asking you to pick one. An agent can set a size or an aspect ratio, restyle the image the user last uploaded in the conversation (`use_reference_image`), and write a copy to its workspace with `save_to`.

On the Replicate backend, `model` picks the model for one image, as an owner/name slug such as `black-forest-labs/flux-kontext-pro`; the configured model is left as it is. The call is refused, and nothing is sent to the provider, when the backend is not Replicate, when the slug is not in the Replicate catalog (the refusal lists the ones that are), or when that catalog cannot be loaded.

---

### Web fetching and scraping

`web_fetch` reads one page. `web_scrape` reads a page and the pages it links to. Both return readable Markdown by default, and an agent can ask for something else:

- **`format`**: `text` for the content without Markdown markup, or `json` for a record per page (address, which fetcher succeeded, content, and for a single page its links). `web_fetch` also offers `html` for the raw page source.
- **`extract`**: named values instead of the whole page. Each field is a CSS selector, with `@attr` to read an attribute, for example `{"price": ".price", "next": "a.next@href"}`. Every page comes back as those fields only, which keeps a crawl small.
- **`metadata`**: the page's own structured data, including its title, description, canonical address, OpenGraph and Twitter card properties, and JSON-LD blocks. Many product, article, recipe and event pages publish their facts this way.
- **`save`** (`web_scrape` only): write the result to the agent's workspace as Markdown, text, or JSON Lines with one page per line, and return only a summary and the file name. A saved crawl is not limited by the size of a chat reply.

Asking for `extract` or `metadata` always makes the result JSON. To route this traffic through a proxy, see [Settings → Proxy Providers](/guide#settings-proxy-providers).

**Background scrapes.** A crawl inside a chat turn stops after 25 pages or 60 seconds by default. For more than that, an agent passes `background: true` to `web_scrape`, and the crawl becomes a job that keeps running after the turn ends. The agent gets the job's number straight away. Each page is written to its workspace as it is read, under `scrapes/JOB/`, numbered in the order the crawl read them. When the job ends, a file named `combined` in the same folder holds every page, as Markdown, text or JSON Lines. The chat that started the job then gets a message saying how it went, and the agent replies to it, so a finished job costs one more model call. `maxMinutes` sets how long the job may run. An agent's job is capped at 500 pages and 60 minutes unless you change the limits in [Settings → Web Scraping](/guide#settings-web-scraping). At most two jobs run at once by default (also set in [Settings → Web Scraping](/guide#settings-web-scraping)), and the rest wait their turn. A job can also name a DataImpulse plan to go out through, with `proxy: {"provider": "dataimpulse", "plan": "mobile"}`, when you or a binding's owner asks for it; see [Scrapes](/guide#scrapes).

A job can be paused and resumed. Pausing lets the pages being fetched finish and keeps the job's place, and resuming continues from the pages it already has, without reading any of them again. A job that was running when JClaw stopped continues the same way on its own once JClaw is back. After a job has been interrupted three times, it waits for you to resume it, so a job that brings JClaw down cannot keep doing so. The time limit counts only time spent running, not time paused or while JClaw was down.

### Jev mode

The `browser` tool drives a headless Chromium for pages that need JavaScript, and by default the agent's own model steers it one selector at a time. If this machine does not have the browser yet, the first call downloads it and the chat shows the progress under that reply; [Settings → Browser](/guide#settings-browser) can download it ahead of time. When [Settings → Browser](/guide#settings-browser) selects Jev and a TypeSafe key is set in [Settings → Decision Providers](/guide#settings-decision-providers), the tool changes shape. It offers only two actions:

- **`run`** with a `url` and a `goal`, both required. JClaw opens the URL through the same checks as `navigate`, then Jev works through the goal: clicking, typing, choosing from dropdowns, scrolling and waiting, one decision at a time. Jev can act only on what it saw on the page. Nothing it answers becomes a selector or a script, and JClaw checks every action against the current page just before it happens.
- **`close`**, as before.

The other actions (`navigate`, `click`, `fill`, `getText`, `screenshot`, `evaluate`) are hidden while Jev is selected, and calling one returns an error listing `run` and `close`.

Jev follows explicit instructions best, so the tool asks the agent for a step-by-step goal that names the controls: fill the fields, submit the search, set the filters, then open the result. "Use the destination search: type Lisbon, submit it, set the category to Design, tick Free cancellation, then open Casa Flora" works where "Find Design stays in Lisbon" can leave the search unsubmitted. The calling agent's own model writes the text, from the goal, so the goal should carry every value to enter.

The model answers with `{"text": "…"}`, at most 2,000 characters. Reasoning before that object, or a code fence around it, is ignored. Anything after it, a key other than `text`, or an empty value types nothing and ends the run with an error. When the goal gives no value for a field, the model answers `{"text": null}`, and the run ends with an error saying so; put the value in the goal and run again.

A run ends as **done** or **blocked**. The result gives the final page's title and address, each action taken in order, and the text visible at the end. Done means Jev judged the goal met, not that anything verified it, and the result says so, so the agent compares the final page with the goal before reporting success. If the final page had more than 250 controls, the result says how many Jev was not shown.

A click or text entry whose target changed or became covered just before it ran is refused without sending any input. The refusal goes into the run's history, so Jev sees it among its recent actions and looks again.

A run stops as blocked, with the reason, after:

- 60 actions or 120 decisions;
- three steps in a row that were refused or changed nothing on the page, naming the control that was refused (waits do not count toward this);
- five decisions in a row made on a page that moved before they could run, such as a page whose content keeps changing;
- five minutes;
- **Stop** pressed in the chat, or the task or subagent run being cancelled. The run ends at its next step, and the result lists the actions already taken.

Each call into the browser has 30 seconds. A page that stops responding for longer, for example because a script is stuck in a loop, ends the run with an error, and JClaw closes that agent's browser session so the next call starts fresh. If Jev cannot be reached, or answers with something invalid, the run stops without taking that step and says why. A Jev request that times out, fails to connect or is rate-limited is retried twice first. While JEV's circuit breaker in [Settings → Decision Providers](/guide#settings-decision-providers) is open or isolated, nothing is sent and the run ends with an error naming the breaker.

Jev never sees or fills password fields, so logins do not work in Jev mode: switch to Playwright for a site that needs one. Shadow DOM, frames, file uploads and pop-up windows are not supported either.

### Browser network checks

The `browser` tool's Chromium runs behind a proxy inside JClaw, and JClaw puts every TCP connection that Chromium opens through the SSRF guard before it is made. The guard refuses loopback, link-local (including the cloud metadata address) and private-network addresses. Because the check sits below the page rather than in it, it covers the pages the browser loads, their resources and redirects, pop-up windows and new tabs, WebSockets, whatever a worker or a shared worker opens, and requests from cross-site frames. Nothing on the page can reach around a check that is not on the page. WebRTC is UDP, which the proxy cannot carry, so the browser is started with WebRTC's non-proxied UDP switched off. A page can still create a peer connection, and a call will simply never connect: nothing reaches an address (on the internet, on your network, or on this machine) without going through the check.

Pop-ups are closed as soon as they open, because the tool never reads them. A link that opens in a new tab is therefore not followed. The result of the action that opened the tab says so and gives the tab's address, which the agent can `navigate` to (or `run`, in Jev mode). A blank tab, a tab at an address that is not http(s) such as `blob:`, and a tab whose address the guard refused are reported without one. Results also name the hosts of any requests the page made that the guard refused since the previous result. An action that opens a tab can take up to 5 seconds longer while the tool waits for the tab to load, and a tab slower than that is reported on a later result instead.

The browser's own event-log lines name hosts only, never a full address. A browser session logs at most 20 refusal lines and 10 closed-tab lines, then one line for each saying that later ones are not logged. For requests the page makes, JClaw logs a host as a warning the first time a request to it is refused as a blocked address, and as info the first time one is refused for another reason, such as the host not resolving. JClaw logs a closed tab once per host; all blank tabs share one of the 10 lines, and all refused tabs another. A URL passed to `navigate` or `run` that the guard refuses is reported only in the result.

Service workers are kept from running, since a worker's own requests would bypass the page-level URL check the tool applies on top of the proxy.

Every host is pinned to the address that was checked, not just the one in the URL passed to `navigate` or `run`. The guard looks the host up, checks what it got, and the connection is then made to that address, so a name that changes where it points between the check and the connection cannot move it. That covers resources, redirect targets, clicked links, and everything a Jev run reaches.

## MCP Servers

The Model Context Protocol (MCP) is an open standard that lets external programs expose tools to LLM apps such as JClaw. Examples: a server that wraps your team's Jira instance, one that talks to Postgres, one that drives a browser. An MCP server's tools are managed on the [MCP Servers](/mcp-servers) page, not the Tools page, which lists only JClaw's first-party tools.

The [MCP Servers](/mcp-servers) page is where you register and configure those servers. Once registered, an MCP server's tools become available to any agent that has the server switched on in its config.

### Two transport flavors

When you click **Add server**, you pick one:

- **STDIO**: JClaw launches the server as a local subprocess and talks to it over stdin/stdout. Best for servers distributed as command-line tools (npm packages, Python scripts).
- **HTTP (Streamable)**: JClaw connects to a running server over HTTP using MCP's streamable-HTTP transport. Best for hosted servers, or anything you would rather run independently.

### STDIO configuration

| Field                     | What to fill in                                                                    |
|---------------------------|------------------------------------------------------------------------------------|
| **Name**                  | Anything memorable; this is the label across the UI.                                |
| **Command**               | Executable path. Example: `npx`.                                                    |
| **Arguments (one per line)** | One argument per line. Example: `-y` then `@modelcontextprotocol/server-postgres`. |
| **Environment variables** | Key/value pairs added to the subprocess environment (for API keys, DB URLs, etc.).  |

### HTTP configuration

| Field          | What to fill in                                                                   |
|----------------|-----------------------------------------------------------------------------------|
| **Name**       | Memorable label.                                                                  |
| **Endpoint URL** | Full URL to the server's MCP endpoint.                                          |
| **Headers**    | Auth headers, etc. Common keys: `Authorization`, `X-Api-Key`.                     |

Once saved, a header value and an environment variable whose name marks it as a secret (one ending in `KEY`, `KEYS` or `TOKEN`, or containing `SECRET` or `PASSWORD`, such as `GITHUB_TOKEN`) show as `••••••••` with a pencil, like every saved secret. Leaving one untouched keeps it when you save; the pencil takes a whole new value. Renaming a saved secret means entering its value again.

### Testing a server

Each row has a **Test connection** button, and so does a server's edit form. Save a new server first, then test it. JClaw connects, lists the server's tools, and reports back, including which MCP protocol version it connected with and whether the server offers MCP v2. A successful test means the server is reachable and speaks MCP; if it fails, the error message is shown inline so you can fix the config. The test uses a connection of its own and leaves the live one alone.

A successful test does not guarantee a server's tools will work end to end. You still need to attach it to an agent and try one.

### Protocol versions

MCP has two generations of protocol. JClaw calls them **MCP v1** and **MCP v2**. MCP v1 opens every connection with a handshake. MCP v2 drops the handshake. JClaw speaks both. The MCP specification itself names each version by its release date, not by a number: MCP v2 is the 2026-07-28 version, and MCP v1 covers every version before it. The generation a server last connected with shows in the **Version** column as `v1` or `v2`, in different colors. Hover over it to see the exact version. A server that has never connected shows a dash. The server's edit form has a **Protocol** setting:

- **Automatic** (the default) asks the server on every connect whether it speaks MCP v2, and uses it if so. A server that does not is connected with MCP v1, at the cost of one extra round trip.
- **Handshake** always connects with MCP v1 and never asks.

If a server that offered MCP v2 then fails on it while MCP v1 still works, JClaw moves it to Handshake by itself, logs a warning, and sends an [alert](/guide#settings-alerts). The form shows when and why until you change the setting; setting it back to Automatic tries MCP v2 again. A tool call the server refused when that happened fails with a message saying so, and is not retried for you. Use Handshake yourself for a server that answers on MCP v2 without an error but answers wrongly, which JClaw cannot detect.

### Binding a server to an agent

Same flow as tools: open [Agents](/agents), open the agent, scroll to **MCP Servers**, turn on its switch. The agent now sees that server's tools the next time it generates.

### Enabling and disabling

Each server has an **enabled** toggle. A disabled server keeps its config saved, but agents cannot use its tools. This is convenient when you want to temporarily silence a noisy server without losing how you set it up.

### Resources

Some MCP servers also publish **resources**: documents an agent can read, such as the list of parameters one of the server's actions takes. When a server offers resources, the agent sees them, and the server's resource templates, next to the server's actions, and can read one through the server. JClaw shows at most 50 resources and 50 templates, and cuts a read that is longer than 64,000 characters.

Reading a resource needs the same permissions as calling one of the server's tools. The agent must have the server switched on under **MCP Servers**. A subagent can read only from the servers it was given when it started. On a server with no tools, having the server switched on is enough to read its resources. A server marked as needing approval asks you before each read, as it does before a tool call. Each read is recorded in [Logs](/logs) under category `MCP_TOOL_INVOKE`, like a tool call.

### Views

Some MCP tools come with a **view**: a small page, such as a table or a chart, that shows the tool's result. JClaw tells every server that it can show views. In web chat, a call to such a tool shows its text result as usual, and its view at the bottom of the tool calls card. The view stays when the card is collapsed and when you reload the conversation. While it loads, the chat shows `Loading view…`. A view out of sight loads when you scroll to it. The chat shows `The view could not be loaded.`, and the text result stays, if the server is not connected, if the view does not start within 20 seconds of its page arriving, or if it has not started a minute after it came into view.

A view can do three things, and each only right after you click in that view:

- **Run a tool on its own server**, such as a button that loads more results. JClaw runs the tool for the view as the conversation's agent, so the agent must have the server switched on under **MCP Servers**, and the view gets the result. A view runs one tool call at a time. It can run any tool of its server except one the server marks as for the agent only. A tool the server marks nothing on is open to both. Nothing runs on a server marked as needing approval, because a view cannot ask you to approve a call, and nothing runs outside web chat. The agent is not told about the call. JClaw records each call that reaches the agent's permission check in [Logs](/logs) under category `MCP_TOOL_INVOKE`, like the agent's own calls, with `from its view` after the agent's name. A call turned away before that check is not recorded: one to a server that is not connected or needs approval, one to a tool marked for the agent only, or one from a conversation that is not web chat.
- **Put a message in the message box.** The text waits there for you to read, change and send; a view never sends a message. If you have already typed something, the view's text goes below it, after one blank line.
- **Open a link** in a new browser tab. Only `http` and `https` links open.

A click elsewhere on the page, in another view, or a key you press in the message box does not count. A request that does not come right after a click in that view is refused, and the view is told `This view can act only right after a click in it.` A tool call that comes while the view's last one is still running is refused with `This view is still running its last tool call.` So a view cannot run tools over and over on its own. A view cannot change what the agent knows. It runs in its own locked-down frame, so it cannot read the chat page, your login or anything JClaw stores, and it cannot use your login to call JClaw. It can load files only from the sites its server names. A view's page opens only inside the chat, never in a tab of its own.

Views have limits worth knowing:

- A view from a server on another machine cannot reach your computer or your local network by an address that plainly names them, such as `localhost`, `192.168.1.10` or `nas.local`. JClaw judges an address by how it is written and does not look names up, so a public name that leads into your network is not caught. Only a server running on your own computer or network can give its view that reach on purpose.
- A view can still send its own frame to another address. JClaw closes the view when it notices, and the chat shows `The view could not be loaded.` in its place, but it cannot stop that first request.
- The agent never sees a server's view pages among its resources and cannot read one. It cannot call a tool that the server marks as for its view only, though it may still see that tool's name.
- Views need a browser that sends Fetch Metadata headers: current Chrome, Edge and Firefox, or Safari 16.4 or later. In an older browser every view shows `The view could not be loaded.`

Views show in web chat only, so a tool that comes with a view is offered only to the agent's own turns in web chat. On Telegram, Slack, WhatsApp, voice and Apps, in a task and in a subagent, the agent does not see the tool, and a call to it is refused as an unknown action. Many such tools exist only to draw their view, and there they would return data that nothing shows.

Some servers' view tools also return useful data, such as n8n-mcp's workflow tools. For a server like that, open its edit form on the [MCP Servers](/mcp-servers) page and turn on **Offer view tools on every channel**. Its view tools are then offered everywhere. Outside web chat the agent gets their result as text, as described below, and no view is drawn. A server you add starts with the setting off. A server that already existed when JClaw gained views starts with it on, so no channel lost a tool; turn it off for a server whose view tools are only for showing.

If a tool returns its result only in the form a view reads, with no text, the agent gets that result as JSON text instead, on every channel. A tool that comes with a view often sends only a short placeholder as text; SerpAPI sends `[Rendered Prefab UI]`. When a view tool's text is 500 characters or less, the agent also gets the data the view draws, as JSON after the text, up to 16,000 characters. A longer result is cut there, with a note saying how much was shown. A tool whose text is longer, or is that same data, gets nothing added.

### Questions from a server

Some MCP servers stop partway through a tool call to ask a person something, such as which account to use. In web chat, JClaw puts the question to you. A form appears above the message box with the heading `Question from` and the server's name, the server's message, and the fields it asks for. The message is shown as plain text, so a link in it cannot be clicked. A field the server gives a value for starts with that value. Check and change the values, then choose:

- **Submit** sends your answers to the server, and the tool call goes on. JClaw first checks each field, for example a required field left empty, a number out of range or an email address that is not one, and shows what to fix under that field. The agent gets what the tool returns after your answer.
- **Decline** tells the server you will not answer.
- **Cancel** tells the server you closed the question without choosing.

A server can ask more than one question in one tool call. JClaw shows them one after another and sends all the answers together. You have 5 minutes for all the questions of one tool call, counted from the first; the form shows the time. When the time runs out, JClaw cancels the request, and a later question in the same call is cancelled without being shown. After **Decline**, **Cancel** or no answer, the tool's result ends with a line from JClaw saying what you did, so the agent does not ask again.

Pressing **Stop** cancels the request at once. So does closing or reloading the page you sent the message from, because that stops the turn. If the conversation is also open in another tab, that tab shows the same form, and you can answer it there; reloading that other tab shows the form again.

The agent never sees what you enter, only what the tool returns. JClaw records each question and what you did in [Logs](/logs) under category `MCP_ELICITATION`. The values you enter are never recorded.

JClaw asks you only in web chat, in the agent's own tool calls. On Telegram, Slack, WhatsApp, voice and Apps, in a task and in a subagent, JClaw does not tell the server it can ask, so the server answers as it would anywhere. If a server asks anyway, JClaw sends it nothing back, and the agent gets the question instead: the server's message and each field's name, type and whether it is required. The agent can then fill the values in and call the tool again, ask you in chat, or use another tool. The same happens in web chat when JClaw cannot show a question: one that asks you to open a link, or a form with a field that is not text, a number, yes or no, or a choice. If any question in a call cannot be shown, none is shown, and the agent gets them all. A question asked while the agent reads a resource goes to the agent too, and so does one asked during a tool call a view makes; the view gets the question as text. A server that asks a question while JClaw lists its resources gets no answer, and the agent sees the server's actions without its resources.

A server that speaks MCP v1 (see [Protocol versions](#skills-tools-mcp-protocol-versions)) cannot ask you anything: JClaw answers its question with `Method not found`.

### When a server stops answering

Two independent guards keep a misbehaving server from taking a whole turn down with it:

- **The connection watchdog** reacts to a transport that died. JClaw reconnects a STDIO process that exits, or an HTTP server that closes the connection, with a backoff, and the server's row shows the disconnect and the last error.
- **The circuit breaker** reacts to a server that is still connected but not answering, the case the watchdog cannot see. Every tool call has a 30 s timeout; only the connection handshake, which has to wait for a cold server to install and start, gets two minutes. Three timeouts (or other server-side failures) in a row, or half of the last ten calls, open the server's breaker; for the next 30 s every call to it fails in microseconds with an error the agent can read, `MCP server 'name' is failing fast: too many recent tool-call failures`, instead of costing another 30 s each. Two probe calls then decide whether it closes again. Each server has its own breaker, so one hung server never slows a healthy one.

A resource read counts the same as a tool call: it has the same 30 s timeout and counts toward the same breaker. Listing a server's resources does not count. If the list fails, or the breaker is open, the agent still gets the server's actions, with a note that the resources could not be listed.

A tool that runs and reports its own error (a file that does not exist, a query that fails) does not count against the breaker: the server answered, and that is tool-level semantics. A successful reconnect clears a breaker that opened on its own, since the dead client's failures say nothing about the fresh one.

An MCP tool error names the server and says which of three things failed: the connection (the server never answered; its row on this page shows the connection state), the protocol (it answered with a JSON-RPC error, broke the contract, or timed out; its own log is the place to look), or the tool itself (it ran and rejected the arguments). Like the built-in tools' errors, each comes as what broke, what to check, and how to retry.

Once a server has been called, a bolt in the **Actions** column of its row on the [MCP Servers](/mcp-servers) page shows its breaker's state: grey while it serves, amber while it probes, red when open. A breaker that is not serving opens a row beneath its server by itself; click the bolt to open a serving one, where you can **Isolate** it by hand. That is useful to take a flaky server out of an agent's reach for a cooldown without disabling it. An isolated server's tool calls fail with `isolated by the operator`, so neither the agent nor the log reads your decision as the server having broken, and a reconnect does not undo it. **Restore** lifts it early, there or from the [Dashboard](/)'s **Circuit Breakers** panel, which lists every breaker that is not serving.

With [Alerts](/guide#settings-alerts) on, JClaw sends you a message when a server's breaker opens and again when it recovers; isolating one yourself sends nothing.

:::gotcha STDIO servers run with your user's permissions
A STDIO server is just a subprocess. It inherits the JClaw server's environment and process privileges. Register only servers you trust to run code on your behalf, the same care you would take installing a CLI from npm. For the same reason, only you and the `main` agent can add a STDIO server or change its command, arguments or environment; any other agent calling the API is refused and can configure HTTP servers only.
:::

:::tip Start with the official servers
The MCP project's GitHub org publishes a growing list of well-tested servers (filesystem, git, postgres, sqlite, fetch, time). Start there before reaching for community servers; they will surface most of the productive workflows with the least setup overhead.
:::

---

## Where to go next

You have covered the full layered model: base chat, the agents behind it, where conversations live, three flavors of "outside-the-turn" work, and three ways to extend what agents can do. The last two sections of the guide are about operating the platform:

- [Settings](/guide#settings): the platform control panel where API keys and per-feature knobs live.
- [Logs & Dashboard](/guide#logs-and-dashboard): operator visibility into what JClaw is doing.