Invoke the `bmad-build-auto` skill on `{{SPEC}}`, the ready-for-development spec for ticket {{KEY}}.

When the spec asks you to verify something beyond the tests you commit (a check that a test fails against broken code, a measurement, a command's output), write each check and its result in the commit body: the reviewer and the brief see only the diff and the commit messages, so a check recorded nowhere else counts as not done.
