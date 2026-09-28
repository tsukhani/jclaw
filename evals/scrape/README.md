# Scrape corpus

The measuring stick for [JCLAW-1080](https://jira.abundent.com/browse/JCLAW-1080). `corpus.json`
is what `./jclaw.sh scrapetest` runs against; `prevalence.json` turns its per-stratum results into
a real-world estimate.

The goal it measures is *scrape most websites* — get past WAFs of every vendor, and render
JavaScript to reach the actual content. Not *get past Cloudflare*, which is a strict subset.

This is **not** an eval suite. `evals/suites/` is deliberately offline — no backend, no network,
no model call, validated inside `play autotest` by `EvalSuiteConformanceTest`. A corpus of live
URLs can never be offline, so it lives beside them rather than among them, and the harness follows
the `loadtest` precedent (live backend, loopback + `X-Loadtest-Auth`) instead.

## Regenerating

```bash
python3 evals/scrape/build_corpus.py --sample 40000 --per-stratum 25
```

Stdlib only — one-time data generation, not app code. Downloads the current Tranco list into
`.cache/` (gitignored, ~22 MB) and probes concurrently. Roughly 20 minutes at `--workers 64`.

A probe sees one response, not what a render settles on, so it can place a site in the wrong
stratum. `EXCLUDED` in `build_corpus.py` lists each such domain with the reason a render gave;
a rebuild never selects one, and `--replace` swaps an existing entry without touching the rest:

```bash
python3 evals/scrape/build_corpus.py --replace forms.gle --sample 40000
```

It takes the best-ranked domain after that stratum's last pick, in the corpus's own seeded
sample (so `--sample` must reproduce the recorded `sample_size`), that probes into the same
stratum, and records the swap under `replaced`. Every other entry stays, so a baseline scored
before the swap differs from one scored after by that one site only.

## Three axes, not one difficulty ladder

The first corpus stratified on a single "protection tier" that silently mixed three independent
properties. That is why its Turnstile tier filled with unprotected sites and scored *better* than
managed-challenge. Every entry now carries all three:

| Axis | Values | Determined by |
|---|---|---|
| **vendor** | `none`, `cloudflare`, `cloudfront`, `akamai`, `imperva`, `datadome`, `sucuri`, `fastly` | response fingerprints |
| **outcome** | `served`, `denied`, `challenge`, `interactive`, `unreachable` | what the origin did |
| **rendering** | `ssr`, `spa` (null unless served) | text vs. markup ratio |

They are genuinely independent. CloudFront in front does **not** mean CloudFront is blocking —
`cloudflare/served` is 20% of the web on its own. `abundent.academy` is `none/served/spa`;
`twitter.com` is `cloudflare/served/spa`; `nih.gov` is `cloudflare/challenge`.

`vendor: none` means *no vendor this probe can fingerprint*. Plenty of WAFs stay quiet, so treat it
as an upper bound on unprotected, never a guarantee.

`vendor` names the blocker, not the CDN in front of it: DataDome behind Cloudflare or CloudFront is
`datadome`. Nine entries were relabelled on that rule on 2026-09-28, so vendor rows from gates before
then are not comparable with later ones; the strata, which the gate checks, did not change.

## Strata

Six, 25 each. Stratified on what changes the **fix**, with vendor carried as metadata so the
per-vendor breakdown falls out of the same run.

| Stratum | Meaning | Rung that addresses it |
|---|---|---|
| `unprotected-ssr` | no detected WAF, server-rendered | 1 — should be near 100% |
| `unprotected-spa` | no detected WAF, client-rendered | 3 (rendering) |
| `edge-served` | behind an edge, serving fine | 1, with fingerprint variance |
| `denied` | flat 403/406/429 | 2, 4 |
| `challenge` | interstitial | 2, 3, 4 |
| `interactive` | Turnstile / DataDome / captcha | 4 |

Within a blocked stratum, selection **round-robins across vendors**. CloudFront is 25× more common
than DataDome, so rank-order alone would fill `denied` with one vendor and the per-vendor breakdown
would have nothing to say about the rare ones. Within a vendor, best-ranked first — long-tail
domains churn, and a corpus that rots between gate runs stops being comparable.

## Equal allocation, and why the gate depends on it

25 per stratum **regardless of natural prevalence**. Measured on 5,500 domains: `interactive` is
0.3% of the web and `unprotected-ssr` is a large plurality. Proportional sampling would give one or
two interactive entries — one dashboard change would swing that stratum by 50%.

It also makes the aggregate meaningless. Proportionally weighted, rung 1 scores well before any WAF
capability exists at all, purely off unprotected sites.

**So: gate on the equal-allocation score, and enforce a per-stratum floor.** An aggregate alone can
be passed by acing the easy half.

## Ground truth

```jsonc
{
  "url": "https://example.com",
  "stratum": "edge-served",
  "vendor": "cloudflare",
  "outcome": "served",
  "rendering": "ssr",
  "rank": 4213,
  "ground_truth": {
    "min_chars": 50,
    "observed_text": 142,
    "observed_html": 559,
    "reject_markers": ["/cdn-cgi/challenge-platform/", "just a moment", "..."],
    "expect_title": "example domain"
  }
}
```

**`reject_markers` is the load-bearing field.** A Cloudflare interstitial is valid HTML with a title
and body text that extracts to a few hundred characters of clean markdown. Without these the
harness scores "checking your browser" as a success and reports a healthy number while agents
receive nothing. This is what the known-zero check verifies: a URL that always challenges must
score 0%, not 60%.

**`min_chars` is derived only when the origin served us** — a quarter of observed visible text,
floor 50, ceiling 500. A blanket 600 scored `example.com` (198 extracted characters, the canonical
known-one) as blocked. But for a *gated* entry the observation **is the gate**, so deriving a floor
from it let a 75-character gate page pass — `outschool.com` did exactly that. Those get a fixed
bar of 500 instead.

**`observed_text` / `observed_html`** separate a client-rendered app (68 text / 5,515 HTML) from a
genuinely small page (142 / 559).

**`expect_title`** is captured only for served origins and is deliberately **not** gated —
Readability and the markdown conversion do not reliably preserve it, so gating would manufacture
failures that say nothing about access. Reported as a secondary signal.

## What the runtime classifier cannot see

Readability strips scripts, so by the time a page reaches `BlockClassifier` the technical markers
are gone. A pure-JS gate and a client-rendered SPA both extract to nothing and both land as
`THIN_CONTENT`.

The corpus axes are the disambiguator — read reason **× stratum**, never reason alone. JCLAW-1086
gives the classifier the raw response and removes the ambiguity at source.

## The tier is relative to the probe client

Python urllib, curl and OkHttp get different answers from the same origins:

```
actcklb6.com    python-urllib 200   curl 200   OkHttp → empty
openai.com      python-urllib 200   curl 403   OkHttp → 403
```

So `served` means *served to a standard HTTP client with a Chrome UA*. Rung 1 falling short on
`edge-served` is not a labelling error — it is the fingerprint penalty, and precisely the quantity
rung 2 exists to recover. Classifying the corpus with the Java stack would make the numbers
self-consistent and hide the thing worth measuring.
