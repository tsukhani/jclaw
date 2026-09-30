# Scrapes

A **scrape** reads a website: a starting page, the pages it links to, and so on, to a depth you choose. A small scrape runs inside a chat turn and comes back as the agent's answer. A large one, dozens or hundreds of pages, runs as a **background scrape**: it keeps going after the turn ends and writes each page to the agent's workspace as it reads it. The **Scrapes** page in the sidebar is where you start one, watch it, and read what it found.

A background scrape starts one of two ways:

- **An agent starts it.** Ask for a large scrape and the agent passes `background: true` to `web_scrape`. The chat shows a card for the job that updates as it runs, and links to its page here. When the job ends, a message in the chat says so and the agent replies to it.
- **You start it**, with **New scrape** on this page.

## The Scrapes page

Every background scrape, newest first: the site, the agent whose workspace receives the pages, its state, how many pages it has read of those it can reach, and how long it has been running. A scrape that went through a proxy is marked **proxy**; hover over the mark to see which. Filter by agent or by state. While any listed scrape is waiting or running, the list refreshes itself every few seconds; it stops once none is.

| State | Meaning |
|---|---|
| Waiting | Queued. At most two scrapes run at once unless Settings says otherwise; the rest wait their turn. |
| Running | Reading pages. |
| Paused | Stopped on request, keeping its place. |
| Interrupted | JClaw restarted while it ran three times over, so it waits for you instead of continuing on its own. |
| Finished | Read at least one page and ended. |
| Failed | Read no page at all, or the crawl itself failed; the scrape says why. |
| Stopped | Ended on request before it finished. |

Each row offers what its state allows:

- **Pause** a waiting or running scrape. The pages being fetched finish, no new one starts, and the scrape keeps everything it has read.
- **Resume** a paused or interrupted scrape. It continues from the pages it already has; none of them is read again.
- **Stop** ends a scrape for good. It keeps its pages but cannot be resumed. It asks first.
- **Delete** removes a scrape that is not waiting or running, with its pages and its workspace folder. It asks first.

A scrape that was running when JClaw stopped continues on its own once JClaw is back, from where it left off. Its time limit counts only the time it spends running, not time paused or while JClaw was down.

## A scrape's page

Open a scrape to see its state and why it stopped, the limits and options it runs with, the proxy it went through, and every page it has tried in the order it read them: the address, whether it was read or blocked (and by what), which fetcher read it, and how much text it kept. New pages appear as the scrape reads them.

A scrape reads the proxy setting once, when it starts, and keeps it to the end: switching the proxy off or changing it in Settings applies to the next scrape. **Proxy** names the proxy (DataImpulse, when it recognizes the address, and the plan when one was used, as in *DataImpulse Mobile plan, gw.dataimpulse.com:823*), says *none, connected directly*, or says *not recorded* for a scrape that ran before JClaw kept track. A scrape resumed after a restart reads the setting again, so one that went out two ways lists both, in order.

**Choosing a DataImpulse plan from chat.** You can ask the agent to run a background scrape through one of your DataImpulse plans, for example "scrape this site in the background through the mobile plan". The agent passes `proxy: {"provider": "dataimpulse", "plan": "mobile"}` to `web_scrape`, and that job goes through the plan for its whole run, a resumed run included, even while the scrape proxy is switched off in Settings. The Settings switch and the active plan are never changed. Only you, in the web chat, or a binding's owner can choose a plan; a guest's request is refused and no scrape starts. The plan needs a saved login and password in [Settings → Proxy Providers](/guide#settings-proxy-providers); if you name one without them, the agent is told which plans have credentials. A plan whose credentials are removed before the job runs fails the job rather than letting it connect directly. The choice applies only to background scrapes, not to a scrape inside a chat turn, `web_fetch`, or **New scrape**.

Select a page to read what the scrape kept of it: Markdown rendered, plain text as it is, or a JSON record laid out for reading. **The viewer loads nothing from the site.** Images and embedded media in a page appear as links, so reading a scraped page never contacts that site from your browser, which does not go through a scraping proxy.

**Download all pages** gives you the file that combines every page, in the scrape's format. It is also in the agent's workspace, beside one file per page, under `scrapes/` followed by the scrape's number.

## New scrape

Give a starting URL and the agent whose workspace receives the pages. Everything else starts from your current Settings and applies to this scrape only; the form never changes Settings.

- **Pages**, **Links deep** and **Time limit** show the most an agent may ask for. Those limits bound agents, not you, so you can go higher.
- **Crawl** options: stay on the starting URL's host, honour the site's `robots.txt`, add pages from its sitemaps, and the preferred language on sites that publish translations.
- **Output**: Markdown, plain text, or JSON with one record per page, optionally with each page's metadata. **Fields to extract** collects named values from every page with CSS selectors (`h1`, `.price`, or `a.next@href` for an attribute), which makes the output JSON.

The number of pages fetched at once is machine-wide and stays in [Settings → Web Scraping](/guide#settings-web-scraping), and the proxy in [Settings → Proxy Providers](/guide#settings-proxy-providers).

If the server refuses a value, such as a selector that does not parse, the reason appears beside that field and no scrape starts. Otherwise the new scrape's page opens.
