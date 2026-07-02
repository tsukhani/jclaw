# Contributing to JClaw

## External contributions are not accepted

JClaw is developed exclusively by the internal Abundent team. **Pull
requests from outside the team will be closed without review**, regardless of
quality. Please don't take a closed PR personally; it is not a judgment on
your code.

This is a deliberate legal decision, not gatekeeping for its own sake. JClaw
is dual-licensed (noncommercial under
[PolyForm Noncommercial 1.0.0](LICENSE.md), commercial licenses from
[Abundent Sdn Bhd](COMMERCIAL-LICENSE.md)). That model depends on Abundent holding
unambiguous rights to every line in the repository. Merging external code,
even one small patch, would introduce third-party copyright into the codebase
and cloud the chain of title that our commercial licensees and partners rely
on.

For the same reason, please do not submit patches, diffs, or substantial code
in issues. If you paste code into an issue, you agree that Abundent may use
it freely; but we prefer a clear description of the problem over proposed
code.

## What we do welcome

- **Bug reports** — a minimal reproduction, your platform, JClaw version
  (`jclaw status`), and relevant log excerpts from `logs/` make bugs fixable
  fast.
- **Feature requests and design discussion** — describe the problem and the
  use case rather than a proposed implementation.
- **Security reports** — please do **not** open a public issue. Email
  [security@abundent.com](mailto:security@abundent.com) and we will respond
  promptly.
- **Documentation feedback** — unclear installation steps, missing
  prerequisites, broken links.

File issues at <https://github.com/tsukhani/jclaw/issues> or reach the team
at [support@abundent.com](mailto:support@abundent.com).

## For the internal team

Development standards for Abundent engineers working on JClaw:

### Workflow

JClaw follows **domain-driven design**: domain modeling precedes coding. For
any non-trivial change, agree the aggregate/entity/service boundaries in the
Jira ticket before writing implementation code, and keep the ubiquitous
language: names in code match the names in the ticket and in `app/models/`.

### Setup

Use the dev container — see
[Dev Container (Recommended)](README.md#dev-container-recommended) in the
README. Clone from the canonical origin
(`bitbucket.abundent.com/scm/jclaw/jclaw.git`); GitHub is the public mirror
and release channel.

### Standards

- Pure Java on the server: no Python or Node.js server-side runtimes, no
  Spring, no framework bloat. JPA entities in `models/`, business logic in
  `services/`, agents in `agents/`.
- `./jclaw.sh test` must pass before push (the `.githooks/pre-push` hook
  enforces this; enable with `git config core.hooksPath .githooks`).
- No new dependencies without prior discussion.
- Every new source file starts with the license header:

```java
// Copyright Abundent Sdn Bhd (https://abundent.com)
// Dual-licensed: PolyForm Noncommercial 1.0.0 (see LICENSE.md)
// or a commercial license from Abundent (see COMMERCIAL-LICENSE.md).
```

(Adapt the comment syntax for `.ts`, `.vue`, `.rb`, `.py`, and shell files.)

### IP hygiene

All work on JClaw vests in Abundent Sdn Bhd under your employment or
contractor agreement. Do not copy code
from external projects into the repository without checking the license with
the team lead first, and never paste code from projects under GPL, AGPL, or
unknown licenses.
