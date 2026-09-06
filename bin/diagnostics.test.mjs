// node --test bin/diagnostics.test.mjs
// Fixtures are verbatim captures of javac-through-Gradle output and play1's
// results-xunit.xml, so a change in either format fails here rather than silently
// emptying the feed.

import { deepStrictEqual, strictEqual } from "node:assert";
import { test } from "node:test";

import { parseArchViolations, parseCompileDiagnostics, parseTestSuiteXml } from "./diagnostics.mjs";

const COMPILE_OUTPUT = `> Task :compileJava FAILED
/repo/app/utils/ScratchProbe.java:7: error: cannot find symbol
        return missingSymbol();
               ^
  symbol:   method missingSymbol()
  location: class ScratchProbe
1 error

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':compileJava'.
> Compilation failed; see the compiler output below.
  /repo/app/utils/ScratchProbe.java:7: error: cannot find symbol
          return missingSymbol();
                 ^
    symbol:   method missingSymbol()
    location: class ScratchProbe
  1 error
`;

test("a javac error is reported once, with its symbol/location detail", () => {
  deepStrictEqual(parseCompileDiagnostics(COMPILE_OUTPUT), [{
    kind: "compile",
    file: "/repo/app/utils/ScratchProbe.java",
    line: 7,
    message: "cannot find symbol — symbol: method missingSymbol() — location: class ScratchProbe",
  }]);
});

test("a suggested fix becomes the fix field", () => {
  const output = "/repo/app/A.java:3: error: [NullAway] dereferenced expression is @Nullable\n"
    + "        return name.length();\n"
    + "  Did you mean 'return name == null ? 0 : name.length();'?\n";
  const [record] = parseCompileDiagnostics(output);
  strictEqual(record.fix, "'return name == null ? 0 : name.length();'");
});

test("warnings are not diagnostics", () => {
  deepStrictEqual(parseCompileDiagnostics("/repo/app/A.java:3: warning: unchecked cast\n"), []);
});

test("a clean compile yields no records", () => {
  deepStrictEqual(parseCompileDiagnostics("BUILD SUCCESSFUL in 6s\n"), []);
});

const FAILING_TEST_XML = `<?xml version="1.0" encoding="UTF-8" ?>

<testsuite failures="1" time="0.1" errors="0" skipped="0" tests="1" name="ScratchProbeTest">
  <testcase classname="ScratchProbeTest" name="addsUp()" time="0.01">
                      <failure type="" message="Failure, expected: &lt;3&gt; but was: &lt;4&gt;">
        <![CDATA[
          In /test/ScratchProbeTest.java, line 12

                      assertEquals(3, 2 + 2); :

        ]]>
        </failure>
                  </testcase>
</testsuite>`;

test("a failing assertion points at the assertion, not the suite", () => {
  deepStrictEqual(parseTestSuiteXml(FAILING_TEST_XML, "ScratchProbeTest"), [{
    kind: "test",
    file: "test/ScratchProbeTest.java",
    line: 12,
    message: "ScratchProbeTest.addsUp(): Failure, expected: <3> but was: <4>",
  }]);
});

test("a passing suite yields no records", () => {
  const xml = `<testsuite failures="0" tests="1" name="AcpWorkdirTest">
  <testcase classname="AcpWorkdirTest" name="resolves()" time="0.1">
          </testcase>
</testsuite>`;
  deepStrictEqual(parseTestSuiteXml(xml, "AcpWorkdirTest"), []);
});

test("a skipped test is not a failure", () => {
  const xml = `<testsuite name="S"><testcase name="t()" time="0">
        <skipped message="Skipped: no provider"/>
  </testcase></testsuite>`;
  deepStrictEqual(parseTestSuiteXml(xml, "S"), []);
});

const ARCH_MESSAGE = "Architecture Violation [Priority: MEDIUM] - Rule 'no classes should depend on"
  + " classes that reside in any package ['java.net.http..'], because outbound HTTP is OkHttp-only"
  + " in app/' was violated (2 times):\n"
  + "Method <utils.ScratchProbe.probe()> calls method <java.net.http.HttpClient.newHttpClient()>"
  + " in (ScratchProbe.java:9)\n"
  + "Method <utils.ScratchProbe.probe()> has return type <java.net.http.HttpClient>"
  + " in (ScratchProbe.java:0)";

test("each ArchUnit site becomes its own record, resolved to a path", () => {
  const locate = (name) => (name === "ScratchProbe.java" ? "app/utils/ScratchProbe.java" : undefined);
  const records = parseArchViolations(ARCH_MESSAGE, { file: "test/ArchitectureTest.java", line: 122 }, locate);
  strictEqual(records.length, 2);
  strictEqual(records[0].kind, "arch");
  strictEqual(records[0].file, "app/utils/ScratchProbe.java");
  strictEqual(records[0].line, 9);
  // ArchUnit's :0 means it had no line for the site.
  strictEqual(records[1].line, null);
  strictEqual(records[0].message.startsWith("Rule 'no classes should depend on"), true);
  strictEqual(records[0].message.endsWith("in (ScratchProbe.java:9)"), true);
});

test("an unresolvable site keeps ArchUnit's bare file name", () => {
  const [record] = parseArchViolations("Rule 'r' was violated (1 times):\nX in (Gone.java:4)", {});
  strictEqual(record.file, "Gone.java");
});

test("a rule-shaped failure with no site lines is still one test record", () => {
  const xml = `<testsuite name="CapabilityRulesTest">
  <testcase classname="CapabilityRulesTest" name="frozenStoreIsReadable()" time="0.1">
    <failure type="" message="Failure, expected message to contain Rule &apos;x&apos; was violated but it did not"><![CDATA[
          In /test/CapabilityRulesTest.java, line 9
        ]]></failure>
  </testcase>
</testsuite>`;
  const records = parseTestSuiteXml(xml, "CapabilityRulesTest");
  strictEqual(records.length, 1);
  strictEqual(records[0].kind, "test");
  strictEqual(records[0].line, 9);
  strictEqual(records[0].message.startsWith("CapabilityRulesTest.frozenStoreIsReadable()"), true);
});

test("an ArchUnit failure reports sites, not one test failure", () => {
  const xml = `<testsuite name="ArchitectureTest">
  <testcase classname="ArchitectureTest" name="noJdkHttpClientInApp()" time="0.3">
                      <failure type="" message="Failure, ${ARCH_MESSAGE.replace(/'/g, "&apos;").replace(/</g, "&lt;").replace(/>/g, "&gt;")}">
        <![CDATA[
          In /test/ArchitectureTest.java, line 122
        ]]>
        </failure>
                  </testcase>
</testsuite>`;
  const records = parseTestSuiteXml(xml, "ArchitectureTest");
  strictEqual(records.length, 2);
  strictEqual(records.every((r) => r.kind === "arch"), true);
  strictEqual(records[0].file, "ScratchProbe.java");
});
