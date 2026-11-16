// node --test bin/select-tests.test.mjs
// The jdeps fixture is the verbatim -verbose:class shape, so a change in that format fails here rather than
// silently selecting nothing.

import { deepStrictEqual, strictEqual } from "node:assert";
import { test } from "node:test";

import { classOfPath, dependents, isRuleCheck, outerClass, parseJdeps, readTestSources, routesOf, select } from "./select-tests.mjs";

const JDEPS = `build/classes/java/test -> build/classes/java/main
   MemoryToolTest                                     -> tools.MemoryTool                                   main
   MemoryToolTest                                     -> tools.MemoryTool$Action                            main
   PlainTest                                          -> utils.Strings                                      main
   tools.MemoryTool                                   -> memory.MemoryAutoCapture                           main
   tools.MemoryTool$Action                            -> tools.MemoryTool                                   main
   memory.MemoryAutoCapture                           -> java.lang.String                                   java.base
`;

const ROUTES = `GET     /api/memories                           ApiMemoryController.list
DELETE  /api/memories/{memoryId}                ApiMemoryController.delete
GET     /api/status                             ApiController.status
`;

const graph = (sources = {}) => ({
  edges: parseJdeps(JDEPS),
  tests: ["MemoryToolTest", "PlainTest", "ApiMemoryControllerTest", "ArchitectureTest", "SpaBuildInputsConformanceTest"],
  testSources: new Map(Object.entries({
    MemoryToolTest: "", PlainTest: "", ApiMemoryControllerTest: 'GET("/api/memories?agent=main")', ArchitectureTest: "",
    SpaBuildInputsConformanceTest: "", ...sources,
  })),
  routes: ROUTES,
});

test("an inner or anonymous class counts as its outer class", () => {
  strictEqual(outerClass("tools.MemoryTool$Action"), "tools.MemoryTool");
  strictEqual(outerClass("tools.MemoryTool$1"), "tools.MemoryTool");
  strictEqual(outerClass("tools.MemoryTool"), "tools.MemoryTool");
});

test("jdeps edges keep outer classes and drop a class's edges to itself", () => {
  const edges = parseJdeps(JDEPS);
  deepStrictEqual([...edges.get("MemoryToolTest")], ["tools.MemoryTool"]);
  deepStrictEqual([...edges.get("tools.MemoryTool")], ["memory.MemoryAutoCapture"]);
  strictEqual(edges.has("build/classes/java/test"), false);
});

test("dependents follow the graph upward through every hop, and include the changed class", () => {
  deepStrictEqual([...dependents(parseJdeps(JDEPS), ["memory.MemoryAutoCapture"])].sort(),
    ["MemoryToolTest", "memory.MemoryAutoCapture", "tools.MemoryTool"]);
  deepStrictEqual([...dependents(parseJdeps(JDEPS), ["utils.Unused"])], ["utils.Unused"]);
});

test("a Java path under app/ or test/ names its class; anything else names none", () => {
  strictEqual(classOfPath("app/memory/MemoryAutoCapture.java"), "memory.MemoryAutoCapture");
  strictEqual(classOfPath("test/Fixtures.java"), "Fixtures");
  strictEqual(classOfPath("app/views/main.html"), undefined);
  strictEqual(classOfPath("jclaw.sh"), undefined);
});

test("routes bind a controller to its paths, cut before the first parameter", () => {
  deepStrictEqual(routesOf(ROUTES, "ApiMemoryController"), ["/api/memories"]);
  deepStrictEqual(routesOf(ROUTES, "ApiController"), ["/api/status"]);
  deepStrictEqual(routesOf(ROUTES, "NoSuchController"), []);
});

test("a changed class selects the tests that reach it, plus the rule checks", () => {
  const r = select([{ status: "M", path: "app/memory/MemoryAutoCapture.java" }], graph(), false);
  strictEqual(r.mode, "some");
  deepStrictEqual(r.tests, ["ArchitectureTest", "MemoryToolTest", "SpaBuildInputsConformanceTest"]);
  strictEqual(r.reasons.MemoryToolTest, "depends on a changed class");
});

test("a changed controller also selects the tests that call its routes over HTTP", () => {
  const g = graph();
  g.edges.set("controllers.ApiMemoryController", new Set(["memory.MemoryAutoCapture"]));
  const r = select([{ status: "M", path: "app/controllers/ApiMemoryController.java" }], g, false);
  strictEqual(r.reasons.ApiMemoryControllerTest, "calls /api/memories");
});

test("a shared file runs the whole suite", () => {
  for (const path of ["conf/routes", "build.gradle.kts", "gradle.lockfile", ".play-version", "app/views/main.html"]) {
    deepStrictEqual(select([{ status: "M", path }], graph(), false), { mode: "all", tests: [], fullBecause: [path], reasons: {} }, path);
  }
});

test("application.conf runs the whole suite unless only its version line changed", () => {
  const change = [{ status: "M", path: "conf/application.conf" }];
  strictEqual(select(change, graph(), false).mode, "all");
  strictEqual(select(change, graph(), true).mode, "none");
});

const READS_DECISION_MODELS = 'Files.readString(Path.of(root, "frontend/utils/decision-models.ts"))';

test("a frontend file selects the backend tests that name it, plus the rule checks", () => {
  const g = graph({ BasetenDecisionTest: READS_DECISION_MODELS, LayaDecisionTest: READS_DECISION_MODELS });
  deepStrictEqual(select([{ status: "M", path: "frontend/utils/decision-models.ts" }], g, false), {
    mode: "some",
    tests: ["ArchitectureTest", "BasetenDecisionTest", "LayaDecisionTest", "SpaBuildInputsConformanceTest"],
    fullBecause: [],
    reasons: {
      ArchitectureTest: "rule check",
      BasetenDecisionTest: "names frontend/utils/decision-models.ts",
      LayaDecisionTest: "names frontend/utils/decision-models.ts",
      SpaBuildInputsConformanceTest: "rule check",
    },
  });
});

test("frontend, docs and harness files no test names need no backend test", () => {
  const r = select([{ status: "M", path: "frontend/pages/chat.vue" }, { status: "M", path: "docs/user-guide/memory.md" },
    { status: "M", path: ".sandcastle/main.ts" }, { status: "M", path: "README.md" }],
  graph({ BasetenDecisionTest: READS_DECISION_MODELS }), false);
  deepStrictEqual(r, { mode: "none", tests: [], fullBecause: [], reasons: {} });
});

test("a docs Markdown file selects the tests that name its path, not those naming only its file name", () => {
  const g = graph({ PlainTest: 'Path.of(root, "docs/user-guide/agents.md")', MemoryToolTest: 'workspace.resolve("agents.md")' });
  deepStrictEqual(select([{ status: "M", path: "docs/user-guide/agents.md" }], g, false).reasons, {
    ArchitectureTest: "rule check",
    PlainTest: "names docs/user-guide/agents.md",
    SpaBuildInputsConformanceTest: "rule check",
  });
});

test("a .sandcastle script selects the tests that name its path or its distinctive file name", () => {
  const g = graph({ PlainTest: 'new File(root, ".sandcastle/install-agent.sh")', MemoryToolTest: 'command.endsWith("install-agent.sh")' });
  deepStrictEqual(select([{ status: "M", path: ".sandcastle/install-agent.sh" }], g, false).reasons, {
    ArchitectureTest: "rule check",
    MemoryToolTest: "names .sandcastle/install-agent.sh",
    PlainTest: "names .sandcastle/install-agent.sh",
    SpaBuildInputsConformanceTest: "rule check",
  });
});

test("in this tree, a change to a frontend, docs or .sandcastle file selects the backend tests that read it", () => {
  const sources = readTestSources();
  const g = { edges: new Map(), tests: [...sources.keys()], testSources: sources, routes: "" };
  for (const [path, reader] of [
    ["frontend/utils/decision-models.ts", "BasetenDecisionTest"],
    ["frontend/utils/decision-models.ts", "LayaDecisionTest"],
    ["docs/user-guide/agents.md", "JclawApiPermissionMatrixTest"],
    [".sandcastle/install-agent.sh", "ApiFactoryControllerTest"],
  ]) {
    strictEqual(select([{ status: "M", path }], g, false).reasons[reader], `names ${path}`, `${path} selects ${reader}`);
  }
});

test("another file selects the tests that name it", () => {
  const r = select([{ status: "M", path: "jclaw.sh" }], graph({ PlainTest: 'run("./jclaw.sh", "restart")' }), false);
  strictEqual(r.reasons.PlainTest, "names jclaw.sh");
});

test("a Markdown file selects only the tests that name its path, not every test naming a README.md", () => {
  const g = graph({ PlainTest: 'read("skills/demo/README.md")', MemoryToolTest: 'read("bin/README.md")' });
  const reasons = select([{ status: "M", path: "bin/README.md" }], g, false).reasons;
  strictEqual(reasons.MemoryToolTest, "names bin/README.md");
  strictEqual(reasons.PlainTest, undefined);
});

test("a deleted class selects nothing by itself: the compile catches what still referred to it", () => {
  strictEqual(select([{ status: "D", path: "app/utils/Gone.java" }], graph(), false).mode, "none");
});

test("the rule checks are the three named classes and every conformance class", () => {
  const tests = ["ArchitectureTest", "CapabilityRulesTest", "WallClockDisciplineTest", "SpaBuildInputsConformanceTest",
    "MemoryToolTest", "ConformanceHelperTest"];
  deepStrictEqual(tests.filter(isRuleCheck),
    ["ArchitectureTest", "CapabilityRulesTest", "WallClockDisciplineTest", "SpaBuildInputsConformanceTest"]);
});
