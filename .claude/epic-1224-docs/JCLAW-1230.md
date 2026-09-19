# JCLAW-1230 — compression: parser level, one code detector, observable fallback

## What was measured (ran, not inferred)

Against the pinned `javaparser-core-3.28.2` jar, on 2026-09-20:

- `new ParserConfiguration().getLanguageLevel()` is `JAVA_11`. `LanguageLevel.CURRENT` and
  `LanguageLevel.BLEEDING_EDGE` both resolve to `JAVA_26`; the enum carries `JAVA_25` and `JAVA_26`.
- A record whose method body is a switch expression throws `ParseProblemException` at the default
  level and parses clean at `BLEEDING_EDGE`.
- Sweeping `app/`: **425 of 750 files (56.7%)** fail to parse at the default level, **0** at
  `BLEEDING_EDGE`. The ticket's "56%" is exact.

Both detector claims reproduced as red tests before the fix: `use the --force flag` classified
`CODE`, and a six-line prose document whose one line begins `export ` classified `CODE`.

## Decisions

**`BLEEDING_EDGE`, not a named release.** The alias tracks whatever the pinned javaparser's newest
level is, so a dependency bump does not silently leave the parser a release behind. No dependency
change was needed — 3.28.2 already knows Java 26.

**A fresh `JavaParser` per call, from a shared `ParserConfiguration`.** A `JavaParser` instance is
not safe to share across threads; the configuration is. This is the same shape `StaticJavaParser`
uses internally. `JavaParserAdapter.of(parser).parse(String)` was chosen over
`JavaParser.parse(String)` because it throws `ParseProblemException` rather than returning a
`ParseResult`, which keeps the existing catch-and-fall-through control flow — and gives AC3 a real
exception to log.

**The minimum signal count is two, with a short-snippet exemption.** AC2 asks for
`detectLanguage != UNKNOWN` "with a minimum signal count". `detectLanguage` returns on the first
hint that matches, so it is already a count of one; a count of one is exactly the defect (one stray
line flipping a whole document). The rule implemented is: at least two non-blank lines carrying a
language hint, **or** at most five non-blank lines in total.

The exemption is not cosmetic. `PYTHON_HINT` does not recognise a bare `import os` — only
`from X import`, `def` and `class`. So the existing green case
`import os\ndef main():\n    print('hi')` carries exactly one signal and is genuinely code. Widening
`PYTHON_HINT` to accept bare `import` would misroute JS (`import foo from 'bar'`) and was rejected as
the riskier change; the size arm costs one constant and touches no language hint.

A density rule (signals as a fraction of lines) was measured and rejected: the existing
`CompressionPipelineTest.compressesCodeToolContent` fixture is ~100 lines with three hint-matching
lines, so any density threshold above 3% would misroute it to TEXT.

**Bias direction.** Misrouting code to TEXT costs compression quality; misrouting prose to CODE cost
a measured 99.94% of the content. The rule is deliberately conservative in that direction.

## Residual, not fixed here

`JS_HINT`'s `export\s+(?:default\s+)?` alternative matches any line beginning `export `, so
`detectLanguage` still answers JAVASCRIPT for a prose line like "export the results to CSV". The
signal count is what keeps that out of CODE, not the hint. Tightening the hint is a separate
change with routing consequences for the compressor, and was left alone.
