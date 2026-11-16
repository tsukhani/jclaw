// node --test bin/candidate-job.test.mjs
// The CLI runs against a local HTTP stub of Jenkins, so no test reaches the real one.

import { deepStrictEqual, strictEqual, throws } from "node:assert";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { candidateJobConfig } from "./candidate-job.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SCRIPT = join(ROOT, "bin", "candidate-job.mjs");
const AUTH = "Basic dXNlcjp0b2tlbg==";

// The JClaw job's config.xml as Jenkins serves it, trimmed, with stand-in ids.
const TRIGGERS = `
    <org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty>
      <triggers>
        <com.atlassian.bitbucket.jenkins.internal.trigger.BitbucketWebhookTriggerImpl plugin="atlassian-bitbucket-server-integration@6.0.2">
          <spec></spec>
          <refTrigger>true</refTrigger>
        </com.atlassian.bitbucket.jenkins.internal.trigger.BitbucketWebhookTriggerImpl>
      </triggers>
    </org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty>`;
const source = ({ triggers = TRIGGERS, branch = "refs/heads/main", description = "  <description>JClaw</description>\n",
  parameters = "" } = {}) => `<?xml version="1.1" encoding="UTF-8"?>
<flow-definition plugin="workflow-job@1624.ve973eb_34e031">
  <actions/>
${description}  <keepDependencies>false</keepDependencies>
  <properties>${triggers}
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>${parameters}
        <hudson.model.StringParameterDefinition>
          <name>CANDIDATE</name>
          <description>A release commit</description>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition">
    <scm class="com.atlassian.bitbucket.jenkins.internal.scm.BitbucketSCM">
      <gitSCM>
        <branches>
          <hudson.plugins.git.BranchSpec>
            <name>${branch}</name>
          </hudson.plugins.git.BranchSpec>
        </branches>
      </gitSCM>
      <branches reference="../gitSCM/branches"/>
      <id>00000000-0000-0000-0000-000000000000</id>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
    <lightweight>false</lightweight>
  </definition>
  <disabled>false</disabled>
</flow-definition>
`;
const BRANCH_PARAMETER = `
        <hudson.model.StringParameterDefinition>
          <name>CANDIDATE_BRANCH</name>
          <description>candidate/v&lt;version&gt;-rc.&lt;N&gt;: the branch this job checks out and names its build after, which /release-check sets with CANDIDATE (JF-453, JF-471)</description>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>`;
const DESCRIPTION = "  <description>Release candidates (JF-453). /release-check starts each build with CANDIDATE and "
  + "CANDIDATE_BRANCH; it checks out that branch and runs the release commit's own Jenkinsfile, and its Bitbucket "
  + "status lands on that commit. No webhook starts it, so the JClaw job's polling never sees a candidate. Made from "
  + "the JClaw job by bin/candidate-job.mjs.</description>\n";
const expected = source({ triggers: "", branch: "refs/heads/${CANDIDATE_BRANCH}", parameters: BRANCH_PARAMETER })
  .replace("  <description>JClaw</description>\n", DESCRIPTION);

test("the candidate job is JClaw's pipeline with no trigger, a checkout of the build's branch and its parameter", () => {
  strictEqual(candidateJobConfig(source()), expected);
});

test("a config that already declares CANDIDATE_BRANCH gets no second one", () => {
  strictEqual(candidateJobConfig(source({ parameters: BRANCH_PARAMETER })), expected);
});

test("a config of another shape is refused rather than guessed at", () => {
  throws(() => candidateJobConfig(source({ branch: "refs/heads/develop" })), { message: "JClaw's config does not check out refs/heads/main once" });
  throws(() => candidateJobConfig(source().replace("</branches>", "<hudson.plugins.git.BranchSpec><name>refs/heads/main</name></hudson.plugins.git.BranchSpec></branches>")),
    { message: "JClaw's config does not check out refs/heads/main once" });
  throws(() => candidateJobConfig(source({ description: "" })), { message: "JClaw's config has no description" });
  throws(() => candidateJobConfig(source().replace(/<parameterDefinitions>[\s\S]*<\/parameterDefinitions>/, "")),
    { message: "JClaw's config declares no parameters" });
});

const withJenkins = async ({ exists = false, sourceStatus = 200, write = 200 }, body) => {
  const seen = [];
  const server = createServer((req, res) => {
    let data = "";
    req.on("data", (d) => { data += d; });
    req.on("end", () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, body: data });
      if (req.url === "/job/JClaw/config.xml") {
        res.writeHead(sourceStatus, { "Content-Type": "application/xml" });
        res.end(sourceStatus === 200 ? source() : "");
      } else if (req.url === "/job/JClaw-candidate/api/json?tree=name") {
        res.writeHead(exists ? 200 : 404);
        res.end(exists ? "{}" : "");
      } else if (req.url === "/crumbIssuer/api/json") {
        res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "JSESSIONID.x=abc; Path=/" });
        res.end(JSON.stringify({ crumbRequestField: "Jenkins-Crumb", crumb: "c-123" }));
      } else {
        res.writeHead(write);
        res.end();
      }
    });
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  const dir = mkdtempSync(join(tmpdir(), "candidate-job-"));
  try {
    const config = join(dir, "claude.json");
    writeFileSync(config, JSON.stringify({ mcpServers: { jenkins: { args: ["-y", "mcp-remote",
      `http://127.0.0.1:${server.address().port}/mcp-server/mcp`, "--header", `Authorization:${AUTH}`] } } }));
    await body({ config, seen });
  } finally {
    await new Promise((closed) => server.close(closed));
    rmSync(dir, { recursive: true, force: true });
  }
};

const cli = (args, config) => new Promise((settle) => {
  const child = spawn(process.execPath, [SCRIPT, ...args], { env: { ...process.env, JCLAW_CLAUDE_JSON: config } });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (code) => settle({ code, stdout, stderr }));
});

test("without --apply the CLI says what it would do and writes nothing", async () => {
  await withJenkins({}, async ({ config, seen }) => {
    deepStrictEqual(await cli([], config), { code: 0, stderr: "", stdout: "Would create JClaw-candidate from JClaw: no "
      + "trigger, refs/heads/${CANDIDATE_BRANCH} checked out, the CANDIDATE_BRANCH parameter. --apply makes it.\n" });
    strictEqual(seen.some((s) => s.method === "POST"), false);
  });
});

test("--apply creates the job when it is missing, with the crumb, its session and the candidate config", async () => {
  await withJenkins({}, async ({ config, seen }) => {
    deepStrictEqual(await cli(["--apply"], config), { code: 0, stdout: "Created JClaw-candidate from JClaw.\n", stderr: "" });
    const post = seen.find((s) => s.method === "POST");
    strictEqual(post.url, "/createItem?name=JClaw-candidate");
    strictEqual(post.headers["content-type"], "application/xml");
    strictEqual(post.headers["jenkins-crumb"], "c-123");
    strictEqual(post.headers.cookie, "JSESSIONID.x=abc");
    strictEqual(post.body, expected);
  });
});

test("--apply updates the job's config when it exists", async () => {
  await withJenkins({ exists: true }, async ({ config, seen }) => {
    deepStrictEqual(await cli(["--apply"], config), { code: 0, stdout: "Updated JClaw-candidate from JClaw.\n", stderr: "" });
    strictEqual(seen.find((s) => s.method === "POST").url, "/job/JClaw-candidate/config.xml");
  });
});

test("the CLI exits 2 when Jenkins refuses the source config or the write, or the argument is wrong", async () => {
  await withJenkins({ sourceStatus: 403 }, async ({ config, seen }) => {
    deepStrictEqual(await cli(["--apply"], config), { code: 2, stdout: "", stderr: "Jenkins answered 403 for JClaw's config\n" });
    strictEqual(seen.some((s) => s.method === "POST"), false);
  });
  await withJenkins({ write: 400 }, async ({ config }) => {
    deepStrictEqual(await cli(["--apply"], config), { code: 2, stdout: "", stderr: "Jenkins answered 400 to the create of JClaw-candidate\n" });
  });
  await withJenkins({}, async ({ config, seen }) => {
    for (const args of [["--force"], ["--apply", "extra"]]) {
      deepStrictEqual(await cli(args, config), { code: 2, stdout: "", stderr: "give no argument to see the change, or --apply to make it\n" });
    }
    strictEqual(seen.length, 0);
  });
});

test("the Jenkinsfile builds a candidate only in JClaw-candidate, on the commit that job's own checkout holds", () => {
  const jenkinsfile = readFileSync(join(ROOT, "Jenkinsfile"), "utf8");
  strictEqual(jenkinsfile.includes("if (env.JOB_NAME != 'JClaw-candidate') {"), true, "a candidate elsewhere is refused");
  strictEqual(jenkinsfile.includes("} else if (env.JOB_NAME == 'JClaw-candidate') {"), true, "the job builds candidates only");
  strictEqual(jenkinsfile.includes("string(name: 'CANDIDATE_BRANCH'"), true);
  strictEqual(jenkinsfile.includes("lock(resource: 'jclaw-build')"), true, "both jobs share one lock");
  strictEqual(/git checkout [^\n]*params\.CANDIDATE/.test(jenkinsfile), false,
    "a checkout after the job's own leaves the Bitbucket status on the job's commit");
});
