// node --test bin/candidate-builds.test.mjs
// The CLI reads build records from a scratch JENKINS_HOME laid out as Jenkins lays out its own.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { buildRecord, keptCandidates } from "./candidate-builds.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SCRIPT = join(ROOT, "bin", "candidate-builds.mjs");
const REL = "a".repeat(40);
const OLD = "d".repeat(40);

// The shape of a real WorkflowRun build.xml: the parameters it ran with, each with a description of its own, ahead of
// the run's own fields.
const record = ({ result = "SUCCESS", description, displayName, keepLog = false } = {}) => `<?xml version='1.1' encoding='UTF-8'?>
<flow-build plugin="workflow-job@1624.ve973eb_34e031">
  <actions>
    <hudson.model.ParametersAction>
      <safeParameters class="sorted-set"/>
      <parameters>
        <hudson.model.StringParameterValue>
          <name>CANDIDATE</name>
          <description>A release commit /release-check pushed to candidate/&lt;sha&gt;</description>
          <value>${REL}</value>
        </hudson.model.StringParameterValue>
      </parameters>
    </hudson.model.ParametersAction>
    <hudson.model.CauseAction>
      <causeBag class="linked-hash-map"/>
    </hudson.model.CauseAction>
  </actions>
  <queueId>301</queueId>
  <timestamp>1794775817603</timestamp>
${result === null ? "" : `  <result>${result}</result>\n`}${description === undefined ? "" : `  <description>${description}</description>\n`}  <duration>1935557</duration>
  <keepLog>${keepLog}</keepLog>
${displayName === undefined ? "" : `  <displayName>${displayName}</displayName>\n`}  <execution class="org.jenkinsci.plugins.workflow.cps.CpsFlowExecution">
    <result>SUCCESS</result>
  </execution>
</flow-build>
`;

const candidate = (number, sha, extra = {}) => record({ description: `tested ${sha}`,
  displayName: `#${number} candidate ${sha.slice(0, 12)}`, ...extra });

test("a record's fields are the run's own, not the descriptions its parameters nest", () => {
  deepStrictEqual(buildRecord(candidate(3, REL, { keepLog: true })), { result: "SUCCESS", description: `tested ${REL}`,
    displayName: `#3 candidate ${REL.slice(0, 12)}`, keepLog: true, building: false });
});

test("a build still running has no result yet, so it reads as building", () => {
  deepStrictEqual(buildRecord(record({ result: null })),
    { result: null, description: null, displayName: null, keepLog: false, building: true });
});

test("entities in a name or a description are decoded", () => {
  strictEqual(buildRecord(record({ displayName: "#5 &lt;b&gt; &amp; &quot;c&quot;" })).displayName, "#5 <b> & \"c\"");
});

test("the kept candidates are the twelve-character commits their kept builds are named for", () => {
  const builds = [{ displayName: `#3 candidate ${REL.slice(0, 12)}`, keepLog: true },
    { displayName: `#2 candidate ${OLD.slice(0, 12)}`, keepLog: false }, { displayName: "#1427", keepLog: true }];
  deepStrictEqual(keptCandidates(builds), [REL.slice(0, 12)]);
});

const withHome = (jobs, body) => {
  const home = mkdtempSync(join(tmpdir(), "candidate-builds-"));
  try {
    for (const [job, builds] of Object.entries(jobs)) {
      mkdirSync(join(home, "jobs", job, "builds"), { recursive: true });
      for (const [number, xml] of Object.entries(builds)) {
        mkdirSync(join(home, "jobs", job, "builds", number));
        writeFileSync(join(home, "jobs", job, "builds", number, "build.xml"), xml);
      }
    }
    body(home);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
};

const cli = (args, home) => {
  const env = { ...process.env };
  delete env.JENKINS_HOME;
  if (home !== undefined) env.JENKINS_HOME = home;
  const run = spawnSync(process.execPath, [SCRIPT, ...args], { env, encoding: "utf8" });
  return { code: run.status, stdout: run.stdout, stderr: run.stderr };
};

test("green names the candidate job's build of the commit, not a push build of it", () => {
  withHome({
    "JClaw-candidate": { 3: candidate(3, REL), 2: candidate(2, OLD) },
    JClaw: { 1427: record({ description: `tested ${REL}`, displayName: "#1427" }) },
  }, (home) => {
    deepStrictEqual(cli(["green", REL], home), { code: 0, stdout: "JClaw-candidate 3\n", stderr: "" });
  });
});

test("green finds a candidate the JClaw job built before the candidate job existed", () => {
  withHome({ JClaw: { 1427: record({ description: `tested ${OLD}`, displayName: "#1427" }), 1426: candidate(1426, OLD) } },
    (home) => {
      deepStrictEqual(cli(["green", OLD], home), { code: 0, stdout: "JClaw 1426\n", stderr: "" });
    });
});

test("green answers 1 when only a failed, a running or another commit's candidate is there", () => {
  withHome({ "JClaw-candidate": {
    4: candidate(4, REL, { result: "FAILURE" }),
    5: candidate(5, REL, { result: null }),
    6: record({ description: `tested ${REL}`, displayName: `#6 candidate ${OLD.slice(0, 12)}` }),
  } }, (home) => {
    deepStrictEqual(cli(["green", REL], home), { code: 1, stdout: "", stderr: `no green candidate build tested ${REL}\n` });
  });
});

test("kept lists both jobs' kept candidates, the candidate job's first and newest first", () => {
  withHome({
    "JClaw-candidate": { 2: candidate(2, "b".repeat(40), { keepLog: true }), 9: candidate(9, REL, { keepLog: true }),
      8: candidate(8, "c".repeat(40)) },
    JClaw: { 1426: candidate(1426, OLD, { keepLog: true }), 1427: record({ description: `tested ${OLD}`, keepLog: true }) },
  }, (home) => {
    deepStrictEqual(cli(["kept"], home),
      { code: 0, stdout: `${REL.slice(0, 12)},${"b".repeat(12)},${OLD.slice(0, 12)}\n`, stderr: "" });
  });
});

test("kept answers 2 rather than an empty list when no job's builds can be read, so Cleanup prunes nothing", () => {
  withHome({}, (home) => {
    deepStrictEqual(cli(["kept"], home), { code: 2, stdout: "",
      stderr: `no builds of JClaw-candidate or JClaw under ${home}/jobs\n` });
  });
  deepStrictEqual(cli(["kept"]), { code: 2, stdout: "", stderr: "JENKINS_HOME is not set, so there are no build records to read\n" });
});

test("a bad argument answers 2 without reading anything", () => {
  for (const args of [[], ["green"], ["green", REL.slice(0, 12)], ["kept", "extra"], ["both", REL]]) {
    deepStrictEqual(cli(args, "/nonexistent"), { code: 2, stdout: "", stderr: "give green <full sha> or kept\n" }, args.join(" "));
  }
});

test("the Jenkinsfile finds candidate builds through this script, never by walking its own job's builds", () => {
  const jenkinsfile = readFileSync(join(ROOT, "Jenkinsfile"), "utf8");
  strictEqual(jenkinsfile.match(/node bin\/candidate-builds\.mjs green /g)?.length, 2, "Setup's skip and the Release stage");
  strictEqual(jenkinsfile.match(/node bin\/candidate-builds\.mjs kept/g)?.length, 1, "Cleanup's kept images");
  strictEqual(/\.previousBuild\b/.test(jenkinsfile), false, "a walk of previousBuild sees only the job it runs in");
  strictEqual(jenkinsfile.includes('src="$JENKINS_HOME/jobs/$CANDIDATE_JOB/builds/$CANDIDATE_BUILD/archive/dist"'), true);
});
