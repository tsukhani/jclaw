# graphify

Read this before running a `graphify` command.

Optional codebase knowledge graph, installed per-machine with `uv tool install graphifyy` (the CLI is `graphify`). Build it with `/graphify .`; `graphify-out/` is gitignored, so the graph is local to each clone and never committed.

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- graphify answers structural questions about a symbol you can already name: what uses it, what it touches, how two things connect. It matches your words against node labels and file paths — class, method and file names, markdown headings — never against code bodies or comments, so it cannot find a symbol from a description: get the name with one grep first, then expand it here. It is the preferred first step for those questions, not a gate. The Claude Code hook's "MANDATORY" reminder never blocks a read or a search, and an edit to a file you already know needs no query.
- Use the narrowest command. `graphify affected "name()"` lists what calls, imports or extends a symbol, with `file:line` — about 500 characters for `load3Dmol()`, where `graphify query` returns 17,000; name a method with its `()`. `graphify explain "Name"` is one node and its direct connections, and the way to look up a file name. `graphify path "A" "B"` links two symbols; add `--undirected` when it reports no directed path. `graphify query` returns the two-hop neighbourhood: reach for it last.
- Give `graphify query` exact identifiers and nothing else: `graphify query "load3Dmol acquireViewer"`, never a sentence. Every word that is not a stopword seeds its own traversal, so a question starts from a dozen unrelated hubs — on three of four test questions here a sentence returned 0–8% on-topic nodes and the symbol name 72–98%. Identifiers are not split on camelCase, so type them whole; a punctuated file name splits into several terms. "calls", "imports", "returns", "fields" and "parameters" each silently restrict the walk to that edge type (`who calls RetentionDays` returns one node), so leave them out or say `--context call`. `--budget N` lifts the 2,000-token cap when the output starts `TRUNCATED`.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `GRAPHIFY_NO_BACKUP=1 graphify update .` to keep the graph current (AST-only, no API cost). Without the variable every run that changes the graph leaves a dated ~70 MB copy of the previous one in `graphify-out/`.

- `graphify update .` re-clusters whenever the code graph changed and names each new or changed community after its highest-degree node, and an unchanged one keeps its saved name, so the `community=` in query output is a hub (`.get`), not a description. Nothing is curated, so nothing is lost by running it.
