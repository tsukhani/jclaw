# Contributing to JClaw

JClaw is open source under the [MIT License](LICENSE), and contributions are welcome.

## Reporting bugs and asking for features

- **Bug reports:** a minimal reproduction, your platform, your JClaw version and the relevant lines from `logs/` make a
  bug quick to fix.
- **Feature requests and design discussion:** describe the problem and the use case rather than an implementation.
- **Documentation feedback:** unclear installation steps, missing prerequisites, broken links.
- **Security reports:** do **not** open a public issue. Email
  [security@abundent.com](mailto:security@abundent.com) and we will respond promptly.

File issues at <https://github.com/tsukhani/jclaw/issues>, or reach the team at
[support@abundent.com](mailto:support@abundent.com).

## Contributing code

- For anything beyond a small fix, open an issue first, so the approach is agreed before you write it.
- Fork <https://github.com/tsukhani/jclaw> on GitHub and open a pull request against `main`. A maintainer reviews it
  there and merges it when it is ready.
- Keep each pull request to one change, with tests. `./jclaw.sh test` must pass; [DEVELOPMENT.md](DEVELOPMENT.md)
  explains the setup.
- Discuss a new dependency in an issue before adding it.
- Code taken from another project needs a license compatible with MIT, such as MIT, BSD or Apache 2.0, and the pull
  request must say where it came from. Never submit code under the GPL, the AGPL or an unknown license.

## Sign your work

Every commit in a pull request must end with a `Signed-off-by` line giving your real name and email address:

```text
Signed-off-by: Your Name <you@example.com>
```

`git commit -s` adds it. Signing off certifies the Developer Certificate of Origin below: that you wrote the change,
or otherwise have the right to submit it under JClaw's license. A pull request with a commit that is not signed off
cannot be accepted. Your contribution is released under the MIT License, like the rest of JClaw.

```text
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## For the Abundent team

### Workflow

JClaw follows **domain-driven design**: domain modeling precedes coding. For any non-trivial change, agree the
aggregate, entity and service boundaries in the Jira ticket before writing implementation code, and keep the
ubiquitous language: names in code match the names in the ticket and in `app/models/`.

### Setup

Use the dev container: see [Dev Container (Recommended)](DEVELOPMENT.md#dev-container-recommended) in DEVELOPMENT.md.
Clone from the canonical origin (`bitbucket.abundent.com/scm/jclaw/jclaw.git`); GitHub is the public mirror and release
channel.

### Standards

- Pure Java on the server: no Python or Node.js server-side runtimes, no Spring, no framework bloat. JPA entities in
  `models/`, business logic in `services/`, agents in `agents/`.
- `./jclaw.sh test` must pass before push (the `.githooks/pre-push` hook enforces this; enable it with
  `git config core.hooksPath .githooks`).
- No new dependencies without prior discussion.

### Third-party content

Check a project's license with the team lead before copying anything from it, and never copy code under the GPL, the
AGPL or an unknown license. Anything from another author that ships in JClaw, whether code, data, a skill or a
document, must have its license recorded. `THIRD-PARTY-NOTICES.md` is generated from what the bundle ships; record what
a component cannot say for itself in `gradle/third-party-licenses.json`. The release build fails until it is
recorded.
