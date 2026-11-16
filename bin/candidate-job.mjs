#!/usr/bin/env node
// Creates or updates the JClaw-candidate Jenkins job from the JClaw job's own config (JF-453). Contract and exit
// codes: bin/README.md.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { jenkinsFrom, postHeaders } from "./candidate-vouch.mjs";

export const SOURCE_JOB = "JClaw";
export const CANDIDATE_JOB = "JClaw-candidate";
const TIMEOUT_MS = 30_000;
const DESCRIPTION = "Release candidates (JF-453). /release-check starts each build with CANDIDATE and CANDIDATE_BRANCH; "
  + "it checks out that branch and runs the release commit's own Jenkinsfile, and its Bitbucket status lands on that "
  + "commit. No webhook starts it, so the JClaw job's polling never sees a candidate. Made from the JClaw job by "
  + "bin/candidate-job.mjs.";
const BRANCH_PARAMETER = `
        <hudson.model.StringParameterDefinition>
          <name>CANDIDATE_BRANCH</name>
          <description>candidate/&lt;the first 12 of CANDIDATE&gt;: the branch this job checks out, which /release-check sets with CANDIDATE (JF-453)</description>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>`;

const escape = (text) => text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/**
 * The candidate job's config.xml, made from the JClaw job's: the same pipeline from the same repository, with no
 * trigger, a checkout of the branch the build names instead of main, and the CANDIDATE_BRANCH parameter that branch
 * spec expands, which must exist before the job's first build runs the Jenkinsfile that declares it. Throws when the
 * config is not the shape this was written against, rather than guess.
 */
export function candidateJobConfig(xml) {
  const triggers = /\n\s*<org\.jenkinsci\.plugins\.workflow\.job\.properties\.PipelineTriggersJobProperty>[\s\S]*?<\/org\.jenkinsci\.plugins\.workflow\.job\.properties\.PipelineTriggersJobProperty>/g;
  let config = xml.replace(triggers, "");
  const mainBranch = /<name>refs\/heads\/main<\/name>/g;
  if ((xml.match(mainBranch) ?? []).length !== 1) throw new Error(`${SOURCE_JOB}'s config does not check out refs/heads/main once`);
  config = config.replace(mainBranch, "<name>refs/heads/${CANDIDATE_BRANCH}</name>");
  // The job's own description is the root's child, two spaces in; its parameters' descriptions sit deeper.
  const description = /^ {2}<description>[\s\S]*?<\/description>$/m;
  if (!description.test(config)) throw new Error(`${SOURCE_JOB}'s config has no description`);
  config = config.replace(description, `  <description>${escape(DESCRIPTION)}</description>`);
  if (!/<name>CANDIDATE_BRANCH<\/name>/.test(config)) {
    if (!/<parameterDefinitions>/.test(config)) throw new Error(`${SOURCE_JOB}'s config declares no parameters`);
    config = config.replace(/<parameterDefinitions>/, `<parameterDefinitions>${BRANCH_PARAMETER}`);
  }
  if (/PipelineTriggersJobProperty|BitbucketWebhookTriggerImpl/.test(config)) throw new Error("a trigger is left in the config");
  return config;
}

/** 0 when the job is (or, without --apply, would be) created or updated, 2 when it is not. */
async function run(args, configPath) {
  const apply = args[0] === "--apply";
  if (args.length > 1 || (args.length === 1 && !apply)) return { code: 2, lines: ["give no argument to see the change, or --apply to make it"] };
  let jenkins;
  try {
    jenkins = jenkinsFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jenkins = null;
  }
  if (!jenkins) return { code: 2, lines: ["no jenkins MCP server with an Authorization header in " + configPath] };
  const call = (url, init = {}) => fetch(url, { ...init, redirect: "manual", signal: AbortSignal.timeout(TIMEOUT_MS) });
  const auth = { Authorization: jenkins.authorization };
  try {
    const source = await call(`${jenkins.base}/job/${SOURCE_JOB}/config.xml`, { headers: auth });
    if (!source.ok) return { code: 2, lines: [`Jenkins answered ${source.status} for ${SOURCE_JOB}'s config`] };
    let config;
    try {
      config = candidateJobConfig(await source.text());
    } catch (error) {
      return { code: 2, lines: [error.message] };
    }
    const exists = (await call(`${jenkins.base}/job/${CANDIDATE_JOB}/api/json?tree=name`, { headers: auth })).ok;
    const verb = exists ? "update" : "create";
    if (!apply) return { code: 0, lines: [`Would ${verb} ${CANDIDATE_JOB} from ${SOURCE_JOB}: no trigger, refs/heads/\${CANDIDATE_BRANCH} checked out, the CANDIDATE_BRANCH parameter. --apply makes it.`] };
    const headers = await postHeaders(jenkins, "application/xml");
    const url = exists ? `${jenkins.base}/job/${CANDIDATE_JOB}/config.xml` : `${jenkins.base}/createItem?name=${CANDIDATE_JOB}`;
    const written = await call(url, { method: "POST", headers, body: config });
    if (!written.ok) return { code: 2, lines: [`Jenkins answered ${written.status} to the ${verb} of ${CANDIDATE_JOB}`] };
    return { code: 0, lines: [`${exists ? "Updated" : "Created"} ${CANDIDATE_JOB} from ${SOURCE_JOB}.`] };
  } catch (error) {
    return { code: 2, lines: [`Jenkins could not be asked: ${error.message}`] };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, lines } = await run(process.argv.slice(2), configPath);
  (code === 0 ? process.stdout : process.stderr).write(`${lines.join("\n")}\n`);
  process.exit(code);
}
