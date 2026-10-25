---
name: oreilly-books
description: >-
  Download a book from O'Reilly Learning as an EPUB using the operator's own subscription cookies. Use when asked to "download an O'Reilly book", "get this book as EPUB", "grab a book from learning.oreilly.com", or "download from Safari Books".
metadata:
  version: "1.0.0"
  author: Tarun Sukhani
  jclaw-icon: "📚"
  jclaw-tools: exec filesystem
  jclaw-commands: safaribooks.py
---

# O'Reilly Book Downloader

Downloads a book the operator has O'Reilly Learning subscription access to and writes it out as an EPUB.

**Scope:** this fetches books for the operator's own offline reading using their own paid subscription. Download the specific book asked for. Do not bulk-enumerate the catalogue, and do not redistribute the output.

**Output Location:** Books are written to `books/` in the agent's workspace — `workspace/<agent>/books/`,
so `workspace/main/books/` for the main agent. Deliberately outside the skill folder: anything written inside it
is hashed into the skill's version and enumerated as a skill binary at promotion, so downloads there would bump
the version on every book and drag each EPUB through the malware scan.

## Prerequisites

Check these before the first run of a session:

1. **Cookies exist.** Use `filesystem` to confirm `skills/oreilly-books/credentials/cookies.json` is present. If only `cookies.example.json` exists, stop and run the [cookie refresh](#when-auth-fails) flow — there is no password fallback.
2. **Python deps installed.** `lxml` and `requests`. Use `exec` to run `pip install -r skills/oreilly-books/tools/requirements.txt` if an import error appears.

## Downloading a book

### 1. Get the book ID

The ID is the digit string in the book's URL:

```
https://learning.oreilly.com/library/view/book-name/9781234567890/
                                                    ^^^^^^^^^^^^^
```

If the user gives a URL, extract the digits. If they give only a title, ask for the URL or ID — this skill does not search the catalogue.

### 2. Run the downloader

Use `exec` with its default working directory, which is the agent workspace — do not set `workdir`.
The script path is therefore workspace-relative, and `SAFARIBOOKS_OUTPUT` is set explicitly because the
script's own default writes beside its own code:

```bash
SAFARIBOOKS_OUTPUT="books" python3 skills/oreilly-books/tools/safaribooks.py <BOOK_ID>
```

Cookies are found regardless of working directory — the script resolves them from its own location, not the cwd.

Add `--kindle` when the user mentions a Kindle or e-reader — it adds CSS that prevents `table` and `pre` elements from overflowing the page.

A run takes anywhere from under a minute to several minutes depending on book size. It prints progress as it fetches chapters.

### 3. Report the result

On success the EPUB is at:

```
books/<Book Title> (<BOOK_ID>)/<BOOK_ID>.epub
```

Use `filesystem` to confirm the file exists, then give the user a markdown link to it:

```
[9781234567890.epub](books/Book Title (9781234567890)/9781234567890.epub)
```

## When auth fails

`orm-jwt` expires after roughly **30 minutes**, so expired cookies are by far the most common failure. Any 401, 403, or auth error means refresh, not retry — re-running with the same stale cookies fails identically.

To refresh: ask the operator to export cookies from a logged-in `learning.oreilly.com` browser session (DevTools → Application → Cookies, or a cookie-export extension), then write them to `skills/oreilly-books/credentials/cookies.json` using `filesystem`, matching the shape of `cookies.example.json` beside it.

`orm-jwt` and `orm-rt` are required. The `bm_*` and `_abck` Akamai cookies are optional but reduce the chance of being flagged as bot traffic — include them when the operator provides them.

The script rewrites `cookies.json` after a successful run to persist the refreshed session, so keep that file writable.

## Error handling

| Symptom | Action |
|---|---|
| 401 / 403 / auth error | Cookies expired — refresh, then retry once |
| 404 on every book | `tools/safaribooks.py` was overwritten with upstream code; see `tools/README.md` |
| `Book directory already exists` | A previous run died partway. Delete that directory under `books/` and retry |
| `ModuleNotFoundError` | Run `pip install -r skills/oreilly-books/tools/requirements.txt` |
| Blank author/publisher in the EPUB | Known limitation of the v2 API mapping — content is fine, don't retry |

If a run fails, `info_<BOOK_ID>.log` in the output directory has the detail. Read it with `filesystem` before deciding what went wrong.

## Important: do not re-clone upstream

`tools/safaribooks.py` carries local modifications that upstream lacks — it has been ported to O'Reilly's v2 API after v1 was retired. Replacing it with a fresh clone of `lorenzodifuccia/safaribooks` breaks every download with a 404. Patch it in place if it needs fixing. Details in `tools/README.md`.
