#!/usr/bin/env python3
"""Build the scrape corpus for ./jclaw.sh scrapetest (JCLAW-1081).

Samples domains across Tranco rank bands, probes each one, and classifies it on
three independent axes — edge vendor, protection outcome, rendering mode — then
emits an equal-allocation corpus plus the population prevalence needed to
post-stratify the harness results.

The axes are independent and conflating them is what broke the first corpus:
CloudFront in front does not mean CloudFront is blocking, and a page with no
server-rendered text may be a JS gate or an ordinary client-rendered app.

Stdlib only, on purpose: this is one-time data generation, not app code.

    python3 evals/scrape/build_corpus.py --sample 40000 --per-stratum 25
"""

import argparse, csv, io, json, os, random, re, ssl, sys, time, urllib.request, zipfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
TRANCO_LATEST = "https://tranco-list.eu/api/lists/date/latest"

# Domains a probe classifies but a render shows are not what their stratum claims. A rebuild
# skips them; --replace swaps an existing entry for the next pick.
EXCLUDED = {
    "forms.gle": "its root is Google's Invalid Dynamic Link page, which settles on HTTP 400 "
                 "after its client-side redirect: an error page, not an SPA (JCLAW-1303)",
}

# Tech-detection probes (Wappalyzer, BuiltWith) present as a browser for the same
# reason we do: a bot UA trips crude UA-based blocking and misreports the tier.
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# Large enough that a real page is not truncated mid-document. At 64 KiB the cap
# landed inside <head> for big sites, so visible_text() saw only scripts and meta
# and reported ~40 characters — nasa.gov, taobao.com, squarespace.com and cnet.com
# all classified as client-rendered while WebFetchTool extracted thousands of
# characters from them.
BODY_CAP = 1_048_576

# Below this many characters of visible text a 200 carrying a gate marker is a gate,
# not a page. A JS-rendered Turnstile wall returns 200 with an empty body
# (hxuakdlb.com: 0 chars); a real page that merely embeds the widget returns
# hundreds (wiley.com: 597).
SERVED_MIN_TEXT = 200

# A served 200 with markup but no text is a client-rendered app
# (abundent.academy: 68 chars of text behind 5,515 bytes of HTML).
SPA_MAX_TEXT = 200
SPA_MIN_HTML = 2_000

# The floor is derived from raw visible text but scored against Readability's
# extraction, which strips nav and chrome — on a portal page that is nearly all of
# it. At a 500 ceiling, wikipedia.org (475 extracted) and mozilla.org (363) failed
# while genuine shells returned 7-15. Any threshold in that 25x gap separates them;
# 300 sits inside it with margin.
SERVED_FLOOR_MAX = 300
GATED_FLOOR = 500

STRATA = ["unprotected-ssr", "unprotected-spa", "edge-served",
          "denied", "challenge", "interactive"]

# Edge vendors that announce themselves in the response. Detection is
# fingerprint-based, so "none" means "no vendor I can identify" — an upper bound on
# unprotected, never a guarantee of it.
# The blocker before the CDN it sits behind: DataDome serves its refusals through
# Cloudflare and CloudFront, whose headers would otherwise claim the site (JCLAW-1320).
VENDORS = [
    ("datadome",   lambda h, b: "x-datadome" in h or "datadome" in h.get("set-cookie", "")
                                or "captcha-delivery.com" in b),
    ("cloudflare", lambda h, b: "cf-ray" in h),
    ("cloudfront", lambda h, b: "cloudfront" in h.get("server", "") or "x-amz-cf-id" in h),
    ("akamai",     lambda h, b: "akamai" in h.get("server", "")
                                or any(k.startswith("x-akamai") for k in h)),
    ("imperva",    lambda h, b: "x-iinfo" in h or "incap_ses" in h.get("set-cookie", "")),
    ("sucuri",     lambda h, b: "x-sucuri-id" in h),
    ("fastly",     lambda h, b: "fastly" in h.get("server", "")),
]

CHALLENGE_MARKERS = ("/cdn-cgi/challenge-platform/", "just a moment", "cf_chl_opt",
                     "cf-please-wait", "_incapsula_resource", "checking your browser")
INTERACTIVE_MARKERS = ("challenges.cloudflare.com/turnstile", "cf-turnstile",
                       "captcha-delivery.com", "geo.captcha-delivery",
                       "g-recaptcha", "hcaptcha.com/1/api.js")

SCRIPT_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S)
TAG_RE = re.compile(r"<[^>]+>")
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.S)


# A truncated read can cut mid-<script>, leaving it unclosed. SCRIPT_RE cannot match
# an unclosed tag, so its JavaScript is then counted as prose: pinterest.com reported
# 467,893 characters of "visible text" against 75 in the untruncated page, which put a
# pure SPA into the server-rendered stratum.
UNCLOSED_SCRIPT_RE = re.compile(r"<(script|style)[^>]*>(?:(?!</\1>).)*$", re.S)


def visible_text(body):
    body = UNCLOSED_SCRIPT_RE.sub("", body)
    return " ".join(TAG_RE.sub(" ", SCRIPT_RE.sub("", body)).split())


def read_body(r):
    """Read up to BODY_CAP, reporting truncation in BYTES.

    The previous guard compared the decoded character count against a byte cap. UTF-8
    multi-byte content decodes to fewer characters than bytes, so `len(body) >=
    BODY_CAP` was false even on a truncated read — pinterest.com truncated at 1,048,576
    bytes and decoded to 1,048,508 characters, and the guard silently never fired.
    """
    raw = r.read(BODY_CAP + 1)
    truncated = len(raw) > BODY_CAP
    return raw[:BODY_CAP].decode("utf-8", "replace").lower(), truncated


def fetch(url, timeout=12):
    """GET url, returning (status, lowercased-headers, body-prefix). Never raises."""
    # Verify certificates, because WebFetchTool does. Skipping verification here put
    # googlevideo.com, windows.net and ezviz7.com into the corpus as "served" when our
    # own stack cannot reach them at all — a cert mismatch is not a scraping-capability
    # question, and those entries only depress the score with noise.
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            h = {k.lower(): v.lower() for k, v in r.headers.items()}
            body, truncated = read_body(r)
            return r.status, h, body, truncated
    except urllib.error.HTTPError as ex:
        h = {k.lower(): v.lower() for k, v in ex.headers.items()} if ex.headers else {}
        try:
            body, truncated = read_body(ex)
        except Exception:
            body, truncated = "", False
        return ex.code, h, body, truncated
    except Exception:
        return 0, {}, "", False


def probe(domain):
    """Probe an origin, retrying once on www. — 14% of apex failures answer there
    (fanfiction.net, huawei.com, sss.gov.ph), and those skew to the stable,
    well-known sites a durable corpus wants."""
    url = "https://" + domain
    status, headers, body, truncated = fetch(url)
    if status == 0:
        alt = "https://www." + domain
        s2, h2, b2, t2 = fetch(alt)
        if s2 != 0:
            return alt, s2, h2, b2, t2
    return url, status, headers, body, truncated


def detect_vendor(headers, body):
    for name, test in VENDORS:
        if test(headers, body):
            return name
    return "none"


def classify(status, headers, body, text=None, truncated=False):
    """Return (vendor, outcome, rendering).

    Outcome records what the origin *did*, never whether we failed — a 403 alone is
    not a tier, and a marker alone is not a gate. wiley.com and onetrust.com embed a
    Turnstile widget and serve real content; labelling them by marker presence put
    open sites in the hardest tier and inverted the whole benchmark.
    """
    if text is None:
        text = visible_text(body)
    vendor = detect_vendor(headers, body)
    if status == 0:
        return vendor, "unreachable", None

    gated = ("cf-mitigated" in headers
             or any(m in body for m in CHALLENGE_MARKERS)
             or any(m in body for m in INTERACTIVE_MARKERS))

    # A 200 with no gate marker is a served page at any size — example.com is 142
    # characters of complete content. It takes a marker *and* a body too thin to be
    # a page before this counts as a gate.
    if status == 200 and (not gated or len(text) >= SERVED_MIN_TEXT):
        # A body that hit the read cap was not seen whole, so its text/markup ratio
        # says nothing about rendering. Report it as unknown rather than guess — the
        # rendering-defined strata exclude it instead of taking a coin flip.
        if truncated:
            return vendor, "served", "unknown"
        rendering = ("spa" if (len(text) < SPA_MAX_TEXT and len(body) > SPA_MIN_HTML)
                     else "ssr")
        return vendor, "served", rendering

    if any(m in body for m in INTERACTIVE_MARKERS):
        return vendor, "interactive", None
    if "cf-mitigated" in headers or any(m in body for m in CHALLENGE_MARKERS):
        return vendor, "challenge", None
    if status in (401, 403, 406, 429, 451, 503):
        return vendor, "denied", None
    return vendor, "unreachable", None


def stratum_of(vendor, outcome, rendering):
    if outcome == "served":
        if rendering == "unknown":
            # Rendering could not be judged, so the rendering-defined strata cannot
            # take it. edge-served is rendering-agnostic and still can.
            return None if vendor == "none" else "edge-served"
        if vendor == "none":
            return "unprotected-spa" if rendering == "spa" else "unprotected-ssr"
        return "edge-served"
    return outcome if outcome in ("denied", "challenge", "interactive") else None


def ground_truth(status, body, outcome, text=None):
    """Assertions the harness scores against.

    {reject_markers} is the load-bearing half: a Cloudflare interstitial is valid HTML
    that extracts to clean markdown, so without it the harness scores "checking your
    browser" as a success.

    {min_chars} is derived from observed visible text — a blanket 600 scored
    example.com, 198 extracted characters and the canonical known-one, as blocked.
    But it is only derived when the origin actually served us: for a gated entry the
    observation IS the gate, so deriving a floor from it lets a 75-character gate page
    pass (outschool.com did exactly that). Those get a fixed bar instead.
    """
    if text is None:
        text = visible_text(body)
    if outcome == "served":
        floor = max(50, min(SERVED_FLOOR_MAX, len(text) // 4))
    else:
        # For a gated entry the observation IS the gate, so no floor can be derived
        # from it. A fixed bar above what an interstitial extracts to instead.
        floor = GATED_FLOOR
    gt = {"min_chars": floor,
          "observed_text": len(text),
          "observed_html": len(body),
          "reject_markers": list(CHALLENGE_MARKERS + INTERACTIVE_MARKERS)}
    if outcome == "served" and status == 200:
        m = TITLE_RE.search(body)
        if m:
            title = " ".join(m.group(1).split())[:120]
            if len(title) >= 8:
                gt["expect_title"] = title
    return gt


def tranco_domains(sample_n, seed):
    os.makedirs(CACHE, exist_ok=True)
    meta = json.load(urllib.request.urlopen(TRANCO_LATEST, timeout=30))
    list_id = meta["list_id"]
    path = os.path.join(CACHE, "tranco-%s.data" % list_id)
    if not os.path.exists(path):
        print("==> downloading Tranco %s" % list_id, file=sys.stderr)
        urllib.request.urlretrieve(meta["download"], path)
    return list_id, sample_ranked(read_ranked(path), sample_n, seed)


def read_ranked(path):
    # The download endpoint has served both a zip and bare text/csv; sniff rather
    # than trust either.
    with open(path, "rb") as fh:
        head = fh.read(2)
    if head == b"PK":
        with zipfile.ZipFile(path) as z:
            raw = z.read(z.namelist()[0]).decode()
    else:
        raw = open(path, encoding="utf-8").read()
    return [row[1] for row in csv.reader(io.StringIO(raw)) if len(row) == 2]


def sample_ranked(ranked, sample_n, seed):
    # Log-spaced bands, not the head. The top of any popularity list is big-tech
    # own-infrastructure and carries almost no third-party edge.
    bands, lo = [], 0
    for hi in (1_000, 10_000, 100_000, len(ranked)):
        bands.append(list(enumerate(ranked[lo:hi], start=lo + 1)))
        lo = hi
    rng = random.Random(seed)
    per_band = sample_n // len(bands)
    picked = []
    for b in bands:
        picked += rng.sample(b, min(per_band, len(b)))
    rng.shuffle(picked)
    return picked


def select(pool, want):
    """Best-ranked first, round-robin across vendors.

    Rank order because long-tail domains churn and a corpus that rots between gate
    runs stops being comparable. Round-robin because a single vendor would otherwise
    fill a blocked stratum — CloudFront is 25x more common than DataDome — and the
    per-vendor breakdown would have nothing to say about the rare ones.
    """
    by_vendor = {}
    for r in sorted(pool, key=lambda r: r["rank"]):
        by_vendor.setdefault(r["vendor"], []).append(r)
    out, queues = [], list(by_vendor.values())
    while len(out) < want and any(queues):
        for q in queues:
            if q and len(out) < want:
                out.append(q.pop(0))
        queues = [q for q in queues if q]
    return out


def bare(url):
    host = url.split("//", 1)[-1].split("/", 1)[0]
    return host[4:] if host.startswith("www.") else host


def replace(path, domain, sample_n):
    """Swap one entry for the next domain its stratum would have taken: the best-ranked domain
    after that stratum's last pick, in the corpus's own seeded sample, that probes into the
    same stratum. Every other entry stays, so a baseline scored before the swap stays
    comparable apart from the one site."""
    if domain not in EXCLUDED:
        sys.exit("add %s to EXCLUDED with its reason first, or a rebuild picks it again" % domain)
    doc = json.load(open(path))
    idx = next((i for i, e in enumerate(doc["entries"]) if bare(e["url"]) == domain), None)
    if idx is None:
        sys.exit("no corpus entry for %s" % domain)
    old = doc["entries"][idx]
    cache = os.path.join(CACHE, "tranco-%s.data" % doc["tranco_list_id"])
    if not os.path.exists(cache):
        sys.exit("the corpus's Tranco list %s is not cached at %s" % (doc["tranco_list_id"], cache))
    sample = sample_ranked(read_ranked(cache), sample_n, doc["seed"])
    if len(sample) != doc["sample_size"]:
        sys.exit("--sample %d draws %d domains, not the corpus's %d: pass the build's --sample"
                 % (sample_n, len(sample), doc["sample_size"]))
    taken = {bare(e["url"]) for e in doc["entries"]}
    last = max(e["rank"] for e in doc["entries"] if e["stratum"] == old["stratum"])
    for rank, d in sorted(sample):
        if rank <= last or d in taken or d in EXCLUDED:
            continue
        url, status, headers, body, truncated = probe(d)
        text = visible_text(body)
        vendor, outcome, rendering = classify(status, headers, body, text, truncated)
        if stratum_of(vendor, outcome, rendering) != old["stratum"]:
            continue
        doc["entries"][idx] = {"url": url, "stratum": old["stratum"], "vendor": vendor,
                               "outcome": outcome, "rendering": rendering, "rank": rank,
                               "ground_truth": ground_truth(status, body, outcome, text)}
        doc.setdefault("replaced", []).append({
            "domain": domain, "by": d, "on": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "reason": EXCLUDED[domain]})
        write_json(path, doc)
        print("==> %s (rank %d) replaced by %s (rank %d) in %s"
              % (domain, old["rank"], d, rank, old["stratum"]), file=sys.stderr)
        return
    sys.exit("no domain after rank %d probes into %s" % (last, old["stratum"]))


def write_json(path, doc):
    """Serialise through a sibling temp file: open(path, "w") truncates before the dump
    runs, so a raise mid-serialisation would destroy the corpus it is rewriting."""
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
    os.replace(tmp, path)


def reclassify(path, workers):
    """Re-probe the corpus's own entries and refresh their labels in place (JCLAW-1091).

    Protection tiers drift — the epic carries this as risk R3 — so a gate scored against
    labels captured weeks earlier is scoring a corpus that no longer exists. This differs
    from a rebuild on purpose: the URL set is held fixed, because changing which sites are
    measured would make the baseline-versus-final delta meaningless. A www. fallback that
    answers is therefore classified but not written back; {probe_url_mismatch} counts how
    many entries were labelled from a URL other than the one the harness will fetch.

    Refreshing a label also re-derives that entry's {min_chars} from the freshly probed
    body, so a pass moves PASS THRESHOLDS and not only labels — a page that grew since the
    last probe is scored against a higher floor afterwards. {min_chars_moved} records how
    many moved and by how much.

    Equal allocation is a property of the labels, not of the file, so drift can break it.
    That is reported rather than repaired: silently rebalancing would hide exactly the
    drift this pass exists to surface. Drifting past the band is not free, though:
    ScrapeCorpus.isEqualAllocation scores the REALISED counts, not the declared
    {allocation} field this pass leaves alone, and the harness answers 400 and runs nothing
    unless every stratum sits within ALLOCATION_TOLERANCE (20%) of the mean.
    """
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    entries = doc["entries"]
    if not entries:
        sys.exit("corpus at %s has no entries — nothing to re-classify" % path)
    print("==> re-probing %d entries with %d workers" % (len(entries), workers), file=sys.stderr)

    def one(e):
        # Degrade, never raise: a raise propagates out of f.result() and aborts the sweep,
        # discarding every label already refreshed.
        try:
            host = e["url"].split("//", 1)[-1].split("/", 1)[0]
            url, status, headers, body, truncated = probe(host)
            text = visible_text(body)
            vendor, outcome, rendering = classify(status, headers, body, text, truncated)
            return e, (url, status, body, text, vendor, outcome, rendering)
        except Exception as err:
            return e, err

    changes, floor_moves, mismatched = [], [], 0
    before = Counter(e["stratum"] for e in entries)
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for f in as_completed([ex.submit(one, e) for e in entries]):
            e, res = f.result()
            if isinstance(res, Exception):
                changes.append((e["url"], e["stratum"],
                                "PROBE FAILED (%s) — label kept" % type(res).__name__))
                continue
            url, status, body, text, vendor, outcome, rendering = res
            if url != e["url"]:
                mismatched += 1
            new_stratum = stratum_of(vendor, outcome, rendering)
            if new_stratum is None:
                # Unclassifiable now (usually an origin that stopped answering). Keep the
                # stored label rather than drop the entry: losing a row would change the
                # denominator and quietly flatter every rate computed against it.
                changes.append((e["url"], e["stratum"], "UNCLASSIFIABLE — label kept"))
                continue
            if (new_stratum, vendor, outcome) != (e["stratum"], e["vendor"], e["outcome"]):
                changes.append((e["url"], "%s/%s" % (e["stratum"], e["vendor"]),
                                "%s/%s" % (new_stratum, vendor)))
            e["stratum"], e["vendor"], e["outcome"], e["rendering"] = \
                new_stratum, vendor, outcome, rendering
            was_floor = e["ground_truth"]["min_chars"]
            e["ground_truth"] = ground_truth(status, body, outcome, text)
            if e["ground_truth"]["min_chars"] != was_floor:
                floor_moves.append(e["ground_truth"]["min_chars"] - was_floor)

    # Seeded from the before-keys so a stratum that lost every entry reports as 0 rather
    # than vanishing — absent from the counter, min() never sees it and the worst possible
    # drift would score as the smallest.
    after = Counter(dict.fromkeys(before, 0))
    after.update(e["stratum"] for e in entries)
    doc["reclassified_on"] = time.strftime("%Y-%m-%d")
    # "allocation" describes the SAMPLING DESIGN and stays "equal": the corpus was drawn
    # 25-per-stratum and the URL set is unchanged. Exact equality of the resulting counts
    # cannot survive re-classification — a site that stops challenging genuinely belongs
    # in a different stratum — so requiring it would make AC1 (re-classify before the run)
    # and the equal-allocation check mutually exclusive. The realised spread is recorded
    # instead, and every floor is scored against its own stratum's n.
    doc["realised_strata"] = dict(sorted(after.items()))
    spread = max(after.values()) - min(after.values())
    doc["allocation_spread"] = spread
    doc["min_chars_moved"] = {"entries": len(floor_moves),
                              "min_delta": min(floor_moves, default=0),
                              "max_delta": max(floor_moves, default=0)}
    doc["probe_url_mismatch"] = mismatched
    if spread > 0:
        print("!!  strata no longer exactly equal (spread %d) — design stays 'equal', "
              "realised counts recorded" % spread, file=sys.stderr)
    write_json(path, doc)

    print("==> %d of %d entries changed label" % (len(changes), len(entries)), file=sys.stderr)
    for url, was, now in sorted(changes):
        print("    %-38s %-28s -> %s" % (url, was, now), file=sys.stderr)
    print("==> %d entries had min_chars moved (delta %d..%d) — pass thresholds, not labels"
          % (len(floor_moves), min(floor_moves, default=0), max(floor_moves, default=0)),
          file=sys.stderr)
    if mismatched:
        print("!!  %d entries answered on a URL other than the stored one — labelled from "
              "it, stored URL unchanged" % mismatched, file=sys.stderr)
    print("!!  prevalence.json is not re-probed by this pass; its probed_on now lags "
          "corpus.json", file=sys.stderr)
    print("==> strata before: %s" % dict(sorted(before.items())), file=sys.stderr)
    print("==> strata after : %s" % dict(sorted(after.items())), file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=40_000)
    ap.add_argument("--per-stratum", type=int, default=25)
    ap.add_argument("--workers", type=int, default=64)
    ap.add_argument("--seed", type=int, default=1081)
    ap.add_argument("--out", default=HERE)
    ap.add_argument("--recompute", action="store_true",
                    help="rewrite ground-truth floors from stored observations, no re-probe")
    ap.add_argument("--replace", metavar="DOMAIN",
                    help="swap one EXCLUDED entry for the next domain its stratum would take, "
                         "keeping every other entry (needs the build's --sample)")
    ap.add_argument("--reclassify", action="store_true",
                    help="re-probe the EXISTING entries and refresh their labels, keeping "
                         "the same URL set so the baseline stays comparable (JCLAW-1091)")
    args = ap.parse_args()

    if args.replace:
        replace(os.path.join(args.out, "corpus.json"), args.replace, args.sample)
        return

    if args.reclassify:
        reclassify(os.path.join(args.out, "corpus.json"), args.workers)
        return

    if args.recompute:
        path = os.path.join(args.out, "corpus.json")
        doc = json.load(open(path))
        for e in doc["entries"]:
            gt = e["ground_truth"]
            gt["min_chars"] = (max(50, min(SERVED_FLOOR_MAX, gt["observed_text"] // 4))
                               if e["outcome"] == "served" else GATED_FLOOR)
        json.dump(doc, open(path, "w"), indent=2)
        print("==> recomputed floors for %d entries" % len(doc["entries"]), file=sys.stderr)
        return

    list_id, domains = tranco_domains(args.sample, args.seed)
    print("==> Tranco %s, probing %d domains with %d workers"
          % (list_id, len(domains), args.workers), file=sys.stderr)

    results, done, t0 = [], 0, time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(probe, d): (rank, d) for rank, d in domains}
        for f in as_completed(futs):
            rank, d = futs[f]
            url, status, headers, body, truncated = f.result()
            text = visible_text(body)
            vendor, outcome, rendering = classify(status, headers, body, text, truncated)
            results.append({
                "domain": d, "url": url, "rank": rank, "status": status,
                "vendor": vendor, "outcome": outcome, "rendering": rendering,
                "stratum": stratum_of(vendor, outcome, rendering),
                "ground_truth": ground_truth(status, body, outcome, text),
            })
            done += 1
            if done % 1000 == 0:
                print("    %d/%d (%.0fs)" % (done, len(domains), time.time() - t0),
                      file=sys.stderr)

    outcomes, vendors, pairs = {}, {}, {}
    for r in results:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
        vendors[r["vendor"]] = vendors.get(r["vendor"], 0) + 1
        key = "%s/%s" % (r["vendor"], r["outcome"])
        pairs[key] = pairs.get(key, 0) + 1

    corpus = []
    for stratum in STRATA:
        pool = [r for r in results if r["stratum"] == stratum and r["domain"] not in EXCLUDED]
        take = select(pool, args.per_stratum)
        if len(take) < args.per_stratum:
            print("!!  stratum %-18s only %d/%d — widen --sample"
                  % (stratum, len(take), args.per_stratum), file=sys.stderr)
        for r in take:
            corpus.append({k: r[k] for k in
                           ("url", "stratum", "vendor", "outcome", "rendering",
                            "rank", "ground_truth")})

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    json.dump({
        "tranco_list_id": list_id, "probed_on": stamp, "sample_size": len(domains),
        "seed": args.seed, "allocation": "equal", "per_stratum": args.per_stratum,
        "strata": STRATA, "entries": corpus,
    }, open(os.path.join(args.out, "corpus.json"), "w"), indent=2)

    json.dump({
        "tranco_list_id": list_id, "probed_on": stamp, "sample_size": len(domains),
        "by_outcome": outcomes, "by_vendor": vendors, "by_vendor_outcome": pairs,
    }, open(os.path.join(args.out, "prevalence.json"), "w"), indent=2)

    print("\n==> probed %d in %.0fs" % (len(results), time.time() - t0), file=sys.stderr)
    print("    outcomes:", dict(sorted(outcomes.items(), key=lambda kv: -kv[1])), file=sys.stderr)
    print("    vendors :", dict(sorted(vendors.items(), key=lambda kv: -kv[1])), file=sys.stderr)
    got = {}
    for e in corpus:
        got[e["stratum"]] = got.get(e["stratum"], 0) + 1
    print("==> corpus: %d entries %s -> corpus.json" % (len(corpus), got), file=sys.stderr)


if __name__ == "__main__":
    main()
