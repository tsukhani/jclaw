// node --test bin/mutation.test.mjs
// Fixtures are trimmed from real PIT 1.30.1 and Stryker 10.0.0 reports, the two that measured nothing included.

import { deepStrictEqual, strictEqual } from "node:assert";
import { mkdtempSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  backendTargets, frontendTargets, harnessFaults, score, staleTestClasses, summarizePit, summarizeStryker,
  testsImporting, testsNaming,
} from "./mutation.mjs";

const PIT_XML = `<?xml version="1.0" encoding="UTF-8"?>
<mutations partial="true">
<mutation detected='true' status='KILLED' numberOfTestsRun='1'><sourceFile>SecretRedactor.java</sourceFile><mutatedClass>utils.SecretRedactor</mutatedClass><mutatedMethod>closesAt</mutatedMethod><lineNumber>287</lineNumber><killingTest>SecretRedactorTest.[engine:junit-jupiter]/[class:SecretRedactorTest]/[method:aSecretIsMaskedInJsonNestedFiveLevelsDeep()]</killingTest><description>Changed increment from 1 to -1</description></mutation>
<mutation detected='true' status='TIMED_OUT' numberOfTestsRun='0'><sourceFile>SecretRedactor.java</sourceFile><mutatedClass>utils.SecretRedactor</mutatedClass><mutatedMethod>endOfParameter</mutatedMethod><lineNumber>229</lineNumber><killingTest/><description>changed conditional boundary</description></mutation>
<mutation detected='false' status='SURVIVED' numberOfTestsRun='22'><sourceFile>SecretRedactor.java</sourceFile><mutatedClass>utils.SecretRedactor</mutatedClass><mutatedMethod>closesAt</mutatedMethod><lineNumber>285</lineNumber><killingTest/><description>changed conditional boundary</description></mutation>
<mutation detected='false' status='NO_COVERAGE' numberOfTestsRun='0'><sourceFile>SecretRedactor.java</sourceFile><mutatedClass>utils.SecretRedactor</mutatedClass><mutatedMethod>maskAfter</mutatedMethod><lineNumber>131</lineNumber><killingTest/><description>Replaced integer addition with subtraction</description></mutation>
</mutations>
`;

const PIT_NOTHING_DETECTED = `<?xml version="1.0" encoding="UTF-8"?>
<mutations partial="true">
<mutation detected='false' status='NO_COVERAGE' numberOfTestsRun='0'><mutatedClass>services.ConversationService</mutatedClass><lineNumber>40</lineNumber><killingTest/></mutation>
<mutation detected='false' status='NO_COVERAGE' numberOfTestsRun='0'><mutatedClass>services.ConversationService</mutatedClass><lineNumber>41</lineNumber><killingTest/></mutation>
</mutations>
`;

const strykerReport = (mutants) => ({ schemaVersion: "1.0", files: { "composables/useBulkSelect.ts": { language: "typescript", mutants } } });

const STRYKER_COMMAND_RUNNER = strykerReport([
  { id: "3", mutatorName: "BlockStatement", replacement: "{}", status: "Killed", testsCompleted: 1 },
  { id: "16", mutatorName: "BlockStatement", replacement: "{}", status: "Timeout", testsCompleted: 0 },
  { id: "19", mutatorName: "ConditionalExpression", replacement: "false", status: "Survived", testsCompleted: 1 },
  { id: "40", mutatorName: "StringLiteral", replacement: "\"\"", status: "CompileError" },
]);

const STRYKER_VITEST_RUNNER_ON_VITEST_5 = strykerReport([
  { id: "6", mutatorName: "MethodExpression", replacement: "all", status: "Survived", testsCompleted: 0, coveredBy: ["0", "1"] },
  { id: "7", mutatorName: "ConditionalExpression", replacement: "true", status: "Survived", testsCompleted: 0, coveredBy: ["0"] },
  { id: "8", mutatorName: "BooleanLiteral", replacement: "true", status: "NoCoverage" },
]);

test("a PIT report counts KILLED and TIMED_OUT as detected, as PIT's own score does", () => {
  const summary = summarizePit(PIT_XML);
  deepStrictEqual(summary, { tool: "PIT", total: 4, detected: 2, statuses: { KILLED: 1, TIMED_OUT: 1, SURVIVED: 1, NO_COVERAGE: 1 } });
  strictEqual(score(summary), "50.0% (2 of 4 detected)");
  deepStrictEqual(harnessFaults(summary), []);
});

test("a PIT run that detected no mutant measured nothing", () => {
  deepStrictEqual(harnessFaults(summarizePit(PIT_NOTHING_DETECTED)),
    ["PIT detected none of 2 mutants: the tests never reached them"]);
});

test("a PIT run with no mutants measured nothing", () => {
  deepStrictEqual(harnessFaults(summarizePit("<mutations partial=\"true\">\n</mutations>\n")), ["PIT generated no mutants"]);
});

test("a Stryker report scores killed and timed-out mutants and leaves compile errors out", () => {
  const summary = summarizeStryker(STRYKER_COMMAND_RUNNER);
  strictEqual(score(summary), "66.7% (2 of 3 detected)");
  deepStrictEqual(harnessFaults(summary), []);
});

test("survivors that ran no test are a harness that measured nothing, not a score of 0%", () => {
  deepStrictEqual(harnessFaults(summarizeStryker(STRYKER_VITEST_RUNNER_ON_VITEST_5)), [
    "Stryker detected none of 3 mutants: the tests never reached them",
    "2 mutants reported as Survived ran no test (testsCompleted 0)",
  ]);
});

test("changed files map to PIT classes and Stryker files; tests, configs and type files are left out", () => {
  const changed = ["app/utils/Urls.java", "app/utils/package-info.java", "conf/routes", "test/UrlsTest.java",
    "frontend/composables/useBulkSelect.ts", "frontend/components/ChatPane.vue", "frontend/test/use-bulk-select.test.ts",
    "frontend/types/api.d.ts", "frontend/nuxt.config.ts", "frontend/tests/chat.spec.ts"];
  deepStrictEqual(backendTargets(changed), ["utils.Urls"]);
  deepStrictEqual(frontendTargets(changed), ["composables/useBulkSelect.ts", "components/ChatPane.vue"]);
});

test("a class is measured by the unit tests that name it, never by a FunctionalTest", () => {
  const tests = new Map([
    ["UrlsTest", "class UrlsTest extends UnitTest { void t() { Urls.parse(\"x\"); } }"],
    ["SsrfGuardTest", "class SsrfGuardTest extends UnitTest { /* reads the host through Urls */ }"],
    ["ApiFetchTest", "class ApiFetchTest extends FunctionalTest { void t() { Urls.parse(\"x\"); } }"],
    ["UrlsHelperTest", "class UrlsHelperTest extends UnitTest { UrlsHelper h; }"],
  ]);
  deepStrictEqual(testsNaming(["utils.Urls"], tests), ["SsrfGuardTest", "UrlsTest"]);
});

test("a frontend file is measured by the test files that import or use it", () => {
  const tests = new Map([
    ["test/use-bulk-select.test.ts", "import { useBulkSelect } from '~/composables/useBulkSelect'"],
    ["test/tasks.page.test.ts", "const api = useBulkSelect({ rows })"],
    ["test/chat.test.ts", "import ChatPane from '~/components/ChatPane.vue'"],
  ]);
  deepStrictEqual(testsImporting("composables/useBulkSelect.ts", tests), ["test/tasks.page.test.ts", "test/use-bulk-select.test.ts"]);
  deepStrictEqual(testsImporting("components/ChatPane.vue", tests), ["test/chat.test.ts"]);
});

test("a test class compiled before its source last changed is stale", () => {
  const dir = mkdtempSync(join(tmpdir(), "mutation-test-"));
  for (const name of ["Fresh", "Stale"]) {
    writeFileSync(join(dir, `${name}.java`), "");
    writeFileSync(join(dir, `${name}.class`), "");
  }
  utimesSync(join(dir, "Fresh.java"), 1000, 1000);
  utimesSync(join(dir, "Fresh.class"), 2000, 2000);
  utimesSync(join(dir, "Stale.java"), 3000, 3000);
  utimesSync(join(dir, "Stale.class"), 2000, 2000);
  writeFileSync(join(dir, "Missing.java"), "");
  deepStrictEqual(staleTestClasses(["Fresh", "Stale", "Missing"], dir, dir), ["Stale", "Missing"]);
});
