// node --test bin/candidate-builds.test.mjs
// The CLI reads build records from a scratch JENKINS_HOME laid out as Jenkins lays out its own.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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

test("the kept candidates are the twelve-character commits their kept builds tested, whatever their names", () => {
  const builds = [{ job: "JClaw-candidate", displayName: "v1.0.22-rc.2", description: `tested ${REL}`, keepLog: true },
    { job: "JClaw-candidate", displayName: "v1.0.22-rc.1", description: `tested ${OLD}`, keepLog: false },
    { job: "JClaw", displayName: "#1427", description: `tested ${OLD}`, keepLog: true },
    { job: "JClaw", displayName: `#1426 candidate ${OLD.slice(0, 12)}`, description: `tested ${OLD}`, keepLog: true }];
  deepStrictEqual(keptCandidates(builds), [REL.slice(0, 12), OLD.slice(0, 12)]);
  deepStrictEqual(keptCandidates(builds, REL), [OLD.slice(0, 12)], "a release build leaves out the candidate it published");
  deepStrictEqual(keptCandidates(builds, "c".repeat(40)), [REL.slice(0, 12), OLD.slice(0, 12)]);
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

test("green names the candidate job's build of the commit by its tested commit, not its name, and never a push build", () => {
  withHome({
    "JClaw-candidate": { 3: record({ description: `tested ${REL}`, displayName: "v1.0.22-rc.2" }), 2: candidate(2, OLD) },
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
    6: candidate(6, OLD),
    7: record({ description: `tested ${REL.slice(0, 12)}`, displayName: "v1.0.22-rc.3" }),
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
    deepStrictEqual(cli(["kept", "--except", REL], home),
      { code: 0, stdout: `${"b".repeat(12)},${OLD.slice(0, 12)}\n`, stderr: "" });
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
  for (const args of [[], ["green"], ["green", REL.slice(0, 12)], ["kept", "extra"], ["kept", "--except"],
    ["kept", "--except", REL.slice(0, 12)], ["kept", "--keep", REL], ["both", REL], ["next-rc", "1.0.22"]]) {
    deepStrictEqual(cli(args, "/nonexistent"),
      { code: 2, stdout: "", stderr: "give green <full sha> or kept [--except <full sha>]\n" }, args.join(" "));
  }
});

test("the Jenkinsfile finds candidate builds through this script, never by walking its own job's builds", () => {
  const jenkinsfile = readFileSync(join(ROOT, "Jenkinsfile"), "utf8");
  strictEqual(jenkinsfile.match(/node bin\/candidate-builds\.mjs green /g)?.length, 2, "Setup's skip and the Release stage");
  strictEqual(jenkinsfile.match(/node bin\/candidate-builds\.mjs kept/g)?.length, 1, "Cleanup's kept images");
  strictEqual(jenkinsfile.includes("node bin/candidate-builds.mjs kept --except ${env.BUILD_COMMIT}"), true,
    "the release build prunes the image it has just copied to GHCR");
  strictEqual(/\.previousBuild\b/.test(jenkinsfile), false, "a walk of previousBuild sees only the job it runs in");
  strictEqual(jenkinsfile.includes('src="$JENKINS_HOME/jobs/$CANDIDATE_JOB/builds/$CANDIDATE_BUILD/archive/dist"'), true);
});

test("a candidate takes its name from its branch, a vouched push build skips Package, and the open-issue check opens no Sonar env", () => {
  const jenkinsfile = readFileSync(join(ROOT, "Jenkinsfile"), "utf8");
  strictEqual(jenkinsfile.includes("node bin/candidate-builds.mjs next-rc"), false, "the branch is the label's one source");
  strictEqual(jenkinsfile.includes("def label = params.CANDIDATE_BRANCH.substring('candidate/'.length())"), true);
  strictEqual(jenkinsfile.includes("currentBuild.displayName = label"), true);
  strictEqual(jenkinsfile.includes("if (!label.startsWith(\"v${env.APP_VERSION}-rc.\".toString())) {"), true);
  strictEqual(jenkinsfile.includes("if (!branch.matches('candidate/v\\\\d+\\\\.\\\\d+\\\\.\\\\d+-rc\\\\.[1-9]\\\\d*')) {"), true);
  strictEqual(/currentBuild\.displayName = "#/.test(jenkinsfile), false, "no candidate is named for its commit");
  const pack = jenkinsfile.slice(jenkinsfile.indexOf("stage('Package')"), jenkinsfile.indexOf("stage('Upgrade Rehearsal')"));
  strictEqual(pack.includes("expression { !params.RELEASE && !env.TESTED_CANDIDATE }"), true);
  const check = jenkinsfile.slice(jenkinsfile.indexOf("def sonarOpenIssues()"), jenkinsfile.indexOf("pipeline {"));
  strictEqual(check.includes("withSonarQubeEnv"), false);
  strictEqual(check.includes("withCredentials([string(credentialsId: 'Sonar', variable: 'SONAR_TOKEN')])"), true);
});

// The Cleanup stage's block, run as Jenkins runs it (sh -xe), against a stub gh that lists the release's assets.
const zipCleanup = (assets, ghExit = 0) => {
  const jenkinsfile = readFileSync(join(ROOT, "Jenkinsfile"), "utf8");
  const at = jenkinsfile.indexOf("for f in jclaw.zip jclaw-bundle.zip; do");
  const block = jenkinsfile.slice(jenkinsfile.lastIndexOf("sh '''", at) + 6, jenkinsfile.indexOf("'''", at));
  const home = mkdtempSync(join(tmpdir(), "candidate-zips-"));
  try {
    const dist = join(home, "jobs", "JClaw-candidate", "builds", "3", "archive", "dist");
    mkdirSync(dist, { recursive: true });
    for (const f of ["jclaw.zip", "jclaw-bundle.zip", "PACKAGE_SUMS", "CANDIDATE_IMAGE"]) writeFileSync(join(dist, f), f);
    const bin = join(home, "stub-bin");
    mkdirSync(bin);
    writeFileSync(join(bin, "gh"), `#!/bin/sh\necho "$*" >> "${join(home, "gh-calls")}"\nprintf '${assets.join("\\n")}\\n'\nexit ${ghExit}\n`);
    chmodSync(join(bin, "gh"), 0o755);
    const run = spawnSync("/bin/sh", ["-e", "-c", block], { encoding: "utf8", env: { PATH: `${bin}:/usr/bin:/bin`,
      JENKINS_HOME: home, APP_VERSION: "1.0.22", CANDIDATE_JOB: "JClaw-candidate", CANDIDATE_BUILD: "3" } });
    return { code: run.status, stdout: run.stdout, stderr: run.stderr, left: readdirSync(dist).sort(),
      calls: readFileSync(join(home, "gh-calls"), "utf8").trim().split("\n") };
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
};

test("once the release lists both zips, the candidate build's copies go and its sums and image record stay", () => {
  const { code, stdout, stderr, left, calls } = zipCleanup(["jclaw.zip", "jclaw-bundle.zip", "SHA256SUMS", "SHA256SUMS.sig"]);
  deepStrictEqual({ code, stderr, left }, { code: 0, stderr: "", left: ["CANDIDATE_IMAGE", "PACKAGE_SUMS"] });
  strictEqual(stdout, "Deleted jclaw.zip from candidate build JClaw-candidate #3: v1.0.22 holds it now.\n"
    + "Deleted jclaw-bundle.zip from candidate build JClaw-candidate #3: v1.0.22 holds it now.\n");
  deepStrictEqual(calls, Array(2).fill("release view v1.0.22 --repo tsukhani/jclaw --json assets --jq .assets[].name"));
});

test("a zip the release does not list, or lists only as part of a longer name, keeps the candidate's copy", () => {
  const { code, stdout, stderr, left } = zipCleanup(["jclaw.zip", "jclaw-bundle.zip.sig"]);
  deepStrictEqual({ code, left }, { code: 0, left: ["CANDIDATE_IMAGE", "PACKAGE_SUMS", "jclaw-bundle.zip"] });
  strictEqual(stdout, "Deleted jclaw.zip from candidate build JClaw-candidate #3: v1.0.22 holds it now.\n");
  strictEqual(stderr, "v1.0.22 lists no jclaw-bundle.zip, so candidate build JClaw-candidate #3 keeps its copy.\n");
});

test("when GitHub cannot be asked, both zips stay", () => {
  const { code, stdout, left } = zipCleanup([], 1);
  deepStrictEqual({ code, stdout, left },
    { code: 0, stdout: "", left: ["CANDIDATE_IMAGE", "PACKAGE_SUMS", "jclaw-bundle.zip", "jclaw.zip"] });
});
