# Documentation in this repository

Everything that explains the code is documentation: the Markdown, the comments and Javadoc in source files, and the text the product shows or a model reads. All of it drifts when the code moves under it. This page catalogs each kind: where it lives, who reads it, what it must agree with, and how a docs sweep (`/docs` in Claude Code) treats it. The sweep takes its scope from these tables, so a new kind of documentation gets a row here first.

**Handling**

- **fix**: correct the drifted words in place.
- **fix + gate**: correct in place, then run the gate for that file type (see [Gates](#gates)), because the text lives in a source file.
- **facts only**: correct a wrong fact; an omission is not drift, so never add coverage.
- **report**: list it with evidence, and edit only an item the operator approves, running the tests that pin that text. Either the text is behaviour (the product shows it or a model reads it), or it is a policy, and code that breaks a policy is not a docs problem.
- **skip**: never edited by a sweep.

## Reference prose

| Kind | Where | Read by | Must agree with | Handling |
|---|---|---|---|---|
| Product README | `README.md` | the public, newcomers | the installers, the UI strings it quotes, what ships; plain English (`skills/writing-standards`) | fix |
| Install guide | `INSTALL.md` | operators | the installers, `jclaw.sh`, the Docker files, what ships | fix |
| Development guide | `DEVELOPMENT.md` | the internal team | `.devcontainer/`, `jclaw.sh`, `.githooks/`, the build, the tree | fix |
| Agent conventions | `AGENTS.md` (`CLAUDE.md` imports it) | coding agents | the code it names, though it is fallible itself | fix |
| Documentation catalog | `docs/README.md` (this page) | maintainers, docs sweeps | the tree: every tracked doc file matches a row | fix |
| Contribution guide | `CONTRIBUTING.md` | contributors | the workflow and hooks; its policy lines are rules, not claims | fix facts, report policy |
| User guide | `docs/user-guide/*.md` | operators in the app, agents through `jclaw_docs` | the `.vue` source for every on-screen string, `app/` for behaviour | fix |
| Component READMEs | `sidecar/*/README.md`, `bin/README.md`, `evals/README.md`, `evals/*/README.md`, `evals/graph/GUIDE.md`, `frontend/tests/e2e/README.md`, `.sandcastle/README.md` | maintainers | the component's code; `bin/README.md` and `evals/README.md` are format contracts | fix |
| Architecture snapshot | `docs/architecture/*.md`, except plan documents such as `jclaw-agentic-refactor.md` | maintainers | the code; nothing regenerates it | facts only |

## In-code documentation

| Kind | Where | Read by | Must agree with | Handling |
|---|---|---|---|---|
| Javadoc | `/** */` in `app/` and `test/`, including `package-info.java` | maintainers, IDE hovers | the declaration it documents; every `{@link}` resolves | fix + gate |
| Code comments | `//` and `/* */` in Java, TypeScript, Vue, CSS; `<!-- -->` in templates | maintainers | the code beside them, and any twin they say they mirror | fix + gate |
| Python comments and docstrings | `sidecar/**/*.py` | maintainers | the endpoints, flags and defaults in that sidecar | fix + gate |
| Script comments | `jclaw.sh`, `install.sh`, `install.ps1`, `bin/*.sh`, `docker-entrypoint.sh`, `.githooks/*` | maintainers | the script | fix + gate |
| Config and build comments | `conf/application.conf` (the record for every unseeded key), `conf/routes`, `build.gradle.kts`, `Dockerfile`, `docker-compose.yml`, `Jenkinsfile`, `Jenkinsfile.renovate`, `renovate.json5`, `frontend/nuxt.config.ts`, `.gitignore`, `.distignore` | maintainers, operators editing `conf/` | the code that reads the key, its default, the build | fix + gate |

## Text that ships as behaviour

| Kind | Where | Read by | Must agree with | Handling |
|---|---|---|---|---|
| UI copy | hints, tooltips, labels and empty states in `frontend/**/*.vue` | operators | the behaviour behind the control | report |
| CLI help | `usage()` and the messages in `jclaw.sh`, installer output | operators | the subcommands and flags | report |
| Tool descriptions | `description()` and parameter schema text in `app/tools` | models | what the tool does | report |
| API summaries | `@Operation` and `@RequestBody` text in `app/controllers` | models through `jclaw_api` discover, the OpenAPI spec | the route | report |
| Prompt text | prompt builders such as `SystemPromptAssembler`, slash-command help in `app/slash` | models, chat users | runtime behaviour | report |
| Local skills | `skills/*/SKILL.md` and their references, except the seven upstream imports | models | the tools and paths a skill names | report |
| Factory prompts | `.sandcastle/prompts/*.md` | factory agents | the factory's phases | report |
| Eval prose | `description` and `rubric` in `evals/suites/*.json` | people scoring a run | the cases; both are outside the suite fingerprint | report |
| Test-enforced rationale | `@AgentAccess(reason = …)`, ArchUnit `because(…)` | maintainers | what bounds the route; the rule | report; never edit a `because(…)`, which keys the frozen store |

## Not documentation of the code

| Kind | Where | Why a sweep skips it |
|---|---|---|
| Upstream skills | `skills/{claude-designer,powerpoint-pptx,humanizer,youtube-transcript,git,frontend-design,ffmpeg-video-processing}` | a re-import overwrites them |
| Agent workspaces | `workspace/` (gitignored) | runtime data the agents own, such as `SOUL.md` and `AGENT.md` |
| Tooling | `.claude/commands/*.md`, `.claude/workflows/*.js` | changed on purpose, never by a sweep |
| Planning and history | `_bmad-output/`, `docs/spikes/`, `docs/analysis/` (all gitignored), plan documents, commit messages, tags, release notes | records of decisions; published history does not change |
| Legal text | `LICENSE.md`, `COMMERCIAL-LICENSE.md`, `frontend/public/third-party/*` | legal text |
| Data that reads as prose | `conf/common-passwords.txt`, `loadtest/prompts.txt`, `.sandcastle/gateway/egress-allowlist.txt`, `archunit_store/*` | inputs to code; `archunit_store` changes only as AGENTS.md describes |
| Directives in comment syntax | `NOSONAR`, `eslint-disable…`, `@ts-expect-error`, `spotless:off`, `shellcheck`, `noqa`, `#!`, PEP 723 `# /// script` blocks | read by tools, not people; a PEP 723 block is a dependency list |

## Gates

Documentation inside a source file is part of that file, so an edit to it runs the file's checks.

- **Java** (`app/`, `test/`): `./gradlew spotlessApply`, then `./gradlew compileJava compileTestJava`. Then, in one `./gradlew playAutotest -Ptests=<A>,<B>,…` start, run the tests that read source text, which are the only tests a comment can reach. Find them each time rather than keeping a list:
  ```bash
  git grep -lE 'Path\.of\("app"|Paths\.get\("app"|"app/|\.java"\)|endsWith\("\.java"\)|"conf/routes"|"conf/application\.conf"|Path\.of\("conf|Path\.of\("frontend|"jclaw\.sh"|"build\.gradle\.kts"' -- 'test/*.java'
  ```
  The `archunit_store/` entries carry line numbers, but the frozen rules do not match on them, so a comment that adds or removes lines leaves them passing.
- **Frontend**: `cd frontend && pnpm lint && pnpm typecheck && pnpm test`.
- **Python**: `python3 -m py_compile <file>`.
- **Shell**: `bash -n <script>`. `install.ps1` has no local gate.
- **Config and build files**: covered by the Java gate, which configures the build and boots the test server on `conf/`.

The full suite still runs in `pre-push` before anything ships.

## Checking Javadoc references

`compileJava` and `compileTestJava` run javac's doclint (`-Xdoclint:reference/private` in `build.gradle.kts`), so a `{@link}`, `@see`, `@param` or `@throws` in `app/` or `test/` that names something that moved, or never existed, fails the build with `reference not found`. Link a class the file does not import by its fully qualified name; `git log -S'<name>'` finds where a moved member went.
