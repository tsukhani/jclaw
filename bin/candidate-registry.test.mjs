// node --test bin/candidate-registry.test.mjs
// The CLI runs against a local HTTP stub of the registry's API, so no test reaches the Jenkins host's.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { doomedTags } from "./candidate-registry.mjs";

const SCRIPT = join(resolve(dirname(fileURLToPath(import.meta.url))), "candidate-registry.mjs");
const A = "aaaaaaaaaaaa";
const B = "bbbbbbbbbbbb";
const C = "cccccccccccc";
const digestOf = (tag) => `sha256:${tag.slice(0, 1).repeat(64)}`;

test("every candidate tag but the kept builds' is doomed", () => {
  deepStrictEqual(doomedTags([A, B, C], [B]), [A, C]);
  deepStrictEqual(doomedTags([A, B], []), [A, B]);
  deepStrictEqual(doomedTags([A], [A, B]), []);
  deepStrictEqual(doomedTags(null, [A]), []);
});

// The registry as the CLI sees it: a tag list, a HEAD per tag that names its digest, a DELETE per digest.
const withRegistry = async ({ tags = [A, B, C], listStatus = 200, headDigest = true, deleteStatus = 202 } = {}, body) => {
  const seen = [];
  const server = createServer((req, res) => {
    seen.push({ method: req.method, url: req.url, accept: req.headers.accept });
    if (req.url === "/v2/jclaw-candidate/tags/list") {
      res.writeHead(listStatus, { "Content-Type": "application/json" });
      res.end(listStatus === 200 ? JSON.stringify({ name: "jclaw-candidate", tags }) : "{}");
    } else if (req.method === "HEAD") {
      const tag = req.url.split("/").at(-1);
      res.writeHead(200, headDigest ? { "Docker-Content-Digest": digestOf(tag) } : {});
      res.end();
    } else {
      res.writeHead(deleteStatus);
      res.end();
    }
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  try {
    await body({ registry: `http://127.0.0.1:${server.address().port}`, seen });
  } finally {
    await new Promise((closed) => server.close(closed));
  }
};

const cli = (args, registry) => new Promise((settle) => {
  const child = spawn(process.execPath, [SCRIPT, ...args], { env: { ...process.env, JCLAW_CANDIDATE_REGISTRY: registry } });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (code) => settle({ code, stdout, stderr }));
});

test("prune deletes each unkept tag's manifest by the digest the registry names, and keeps the kept", async () => {
  await withRegistry({}, async ({ registry, seen }) => {
    deepStrictEqual(await cli(["prune", "--keep", B], registry), { code: 0, stderr: "",
      stdout: `Deleted ${A} ${digestOf(A)}\nDeleted ${C} ${digestOf(C)}\n` });
    deepStrictEqual(seen.map((s) => `${s.method} ${s.url}`), [
      "GET /v2/jclaw-candidate/tags/list",
      `HEAD /v2/jclaw-candidate/manifests/${A}`,
      `DELETE /v2/jclaw-candidate/manifests/${digestOf(A)}`,
      `HEAD /v2/jclaw-candidate/manifests/${C}`,
      `DELETE /v2/jclaw-candidate/manifests/${digestOf(C)}`,
    ]);
    strictEqual(seen[1].accept.startsWith("application/vnd.oci.image.index.v1+json, "), true, seen[1].accept);
  });
});

test("an empty keep list prunes every candidate, and several kept builds are all kept", async () => {
  await withRegistry({ tags: [A, B] }, async ({ registry }) => {
    strictEqual((await cli(["prune", "--keep", ""], registry)).stdout, `Deleted ${A} ${digestOf(A)}\nDeleted ${B} ${digestOf(B)}\n`);
  });
  await withRegistry({}, async ({ registry, seen }) => {
    deepStrictEqual(await cli(["prune", "--keep", `${A},${C}`], registry), { code: 0, stderr: "",
      stdout: `Deleted ${B} ${digestOf(B)}\n` });
    strictEqual(seen.filter((s) => s.method === "DELETE").length, 1);
  });
});

test("a registry with nothing to prune, or that never took a candidate, deletes nothing and exits 0", async () => {
  await withRegistry({ tags: [A] }, async ({ registry, seen }) => {
    deepStrictEqual(await cli(["prune", "--keep", A], registry), { code: 0, stdout: "No candidate image to prune.\n", stderr: "" });
    strictEqual(seen.some((s) => s.method !== "GET"), false);
  });
  await withRegistry({ listStatus: 404 }, async ({ registry }) => {
    deepStrictEqual(await cli(["prune", "--keep", A], registry), { code: 0, stdout: "No candidate image to prune.\n", stderr: "" });
  });
});

test("a refused delete, a tag with no digest, or a refused listing stops the run with exit 1", async () => {
  await withRegistry({ deleteStatus: 405 }, async ({ registry, seen }) => {
    deepStrictEqual(await cli(["prune", "--keep", B], registry), { code: 1, stdout: "",
      stderr: `The candidate registry answered 405 to deleting ${A} (${digestOf(A)})\n` });
    strictEqual(seen.filter((s) => s.method === "DELETE").length, 1);
  });
  await withRegistry({ headDigest: false }, async ({ registry, seen }) => {
    deepStrictEqual(await cli(["prune", "--keep", B], registry), { code: 1, stdout: "",
      stderr: `The candidate registry answered 200 for ${A}, with no digest to delete\n` });
    strictEqual(seen.some((s) => s.method === "DELETE"), false);
  });
  await withRegistry({ listStatus: 500 }, async ({ registry }) => {
    deepStrictEqual(await cli(["prune", "--keep", B], registry), { code: 1, stdout: "",
      stderr: "The candidate registry answered 500 for its tags\n" });
  });
});

test("a registry that is not there exits 1, and bad arguments are refused before any request", async () => {
  const away = await cli(["prune", "--keep", A], "http://127.0.0.1:9");
  strictEqual(away.code, 1);
  strictEqual(away.stderr.startsWith("The candidate registry could not be asked: "), true, away.stderr);
  await withRegistry({}, async ({ registry, seen }) => {
    const usage = "Usage: node bin/candidate-registry.mjs prune --keep <short sha>[,<short sha>…]\n";
    for (const args of [[], ["prune"], ["prune", "--keep"], ["prune", "--keep", "abc"], ["prune", "--keep", `${A},`],
      ["delete", "--keep", A], ["prune", "--keep", A, "extra"]]) {
      deepStrictEqual(await cli(args, registry), { code: 1, stdout: "", stderr: usage }, args.join(" "));
    }
    deepStrictEqual(seen, []);
  });
});

test("a GitHub Release's cleanup restarts the registry after its garbage-collect, and waits until it answers again", () => {
  const jenkinsfile = readFileSync(join(dirname(SCRIPT), "..", "Jenkinsfile"), "utf8");
  const gc = jenkinsfile.indexOf("docker exec jclaw-candidate-registry registry garbage-collect --delete-untagged");
  strictEqual(gc > 0, true, "the cleanup garbage-collects the candidate registry");
  const step = jenkinsfile.slice(gc, jenkinsfile.indexOf("'''", gc));
  const restart = step.indexOf("docker restart jclaw-candidate-registry > /dev/null");
  strictEqual(restart > 0, true, "the cleanup restarts the registry after its garbage-collect: " + step);
  strictEqual(step.indexOf("curl -fsS -o /dev/null http://127.0.0.1:5000/v2/") > restart, true, step);
});
