# Comments: length and Javadoc

Read this before writing a comment longer than a line, or a Javadoc block. AGENTS.md holds the rule, the delete test and the examples.

**Length follows the code around it.** Match the density of the comments beside yours: a new entry in a list of one-line comments gets one line. A longer comment is right when each line carries something a reader would otherwise get wrong; one that needs a paragraph to justify a workaround usually means the code should change instead. In the Settings banner's prefix list (`managed-prefixes.ts`), `playwright.`, `jtokkit.` and `memory.` run several lines because one is a deliberately-retained retired namespace and the other two cover keys written autonomously by a job — all genuinely surprising. Every ordinary entry beside them is a single line, and a new one that isn't will be sent back.

**Javadoc is held to the same bar.** Stating a public contract — parameters, return, thrown conditions, threading — earns its length. Prose rationale *inside* a Javadoc block does not: it faces the same delete test as any other comment, and a private helper usually needs no Javadoc at all. A ten-line block explaining why a two-line method exists is the same defect as a ten-line inline comment.
