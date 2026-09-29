// Live check of the lockdown: the gateway's own hardening, then egress and an agent turn from a sandbox behind it.
// Spends one small model call. `npx tsx gateway-check.ts`
import { execFileSync } from "node:child_process";
import * as sandcastle from "@ai-hero/sandcastle";
import { IMAGE, ensureGateway, ensureGradleSeed, factorySandbox, planHooks } from "./factory.ts";
import { CLONE, LOGS } from "./paths.ts";

const docker = (...a: string[]) => {
  try { return execFileSync("docker", a, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim(); }
  catch (e: any) { return `FAILED: ${String(e.stderr ?? e.message).trim().split("\n").at(-1)}`; }
};
ensureGateway();
ensureGradleSeed();
console.log("== gateway container");
console.log(docker("inspect", "jclaw-factory-gateway", "--format",
  "image={{.Config.Image}}\nuser={{.Config.User}} readOnlyRootfs={{.HostConfig.ReadonlyRootfs}} capDrop={{.HostConfig.CapDrop}} secOpt={{.HostConfig.SecurityOpt}} mem={{.HostConfig.Memory}} pids={{.HostConfig.PidsLimit}}\nnetworks={{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}"));
console.log("shell in gateway: " + docker("exec", "jclaw-factory-gateway", "sh", "-c", "true"));
const configEnv = docker("inspect", "-f", "{{range .Config.Env}}{{println .}}{{end}}", "jclaw-factory-gateway").split("\n");
console.log("credentials in the container's config (docker inspect): " + configEnv.filter((l) => /sk-ant|TOKEN=|_KEY=/.test(l)).length);

// A container outside the factory, on Docker's default bridge, must reach none of the gateway's addresses.
const ips = docker("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}", "jclaw-factory-gateway").split(" ").filter(Boolean);
const probes = ips.flatMap((ip) => [`http://${ip}:8080/v1/models`, `http://${ip}:3128/`]);
console.log("== from a container on the default bridge (000 = unreachable)");
console.log(docker("run", "--rm", "--entrypoint", "sh", IMAGE, "-c",
  probes.map((u) => `printf '%-40s %s\\n' ${u} $(curl -s -m 5 -o /dev/null -w '%{http_code}' ${u})`).join("; ")));

{
  await using sandbox = await sandcastle.createSandbox({ cwd: CLONE, branch: "factory/gateway-check", sandbox: factorySandbox(), hooks: planHooks });
  const r = await sandbox.exec(
    "echo sk-ant-in-env=$(env | grep -c sk-ant); " +
    "for u in https://registry.npmjs.org/-/ping https://repo.maven.apache.org/maven2/ https://example.com https://api.anthropic.com/v1/models http://host.docker.internal:9000/api/status; do " +
    "printf '%-48s %s\\n' $u \"$(curl -sS -m 10 -o /dev/null -w '%{http_code}' $u 2>&1 | tail -1)\"; done; " +
    "printf '%-48s %s\\n' 'direct, bypassing the proxy' \"$(curl -sS -m 10 --noproxy '*' -o /dev/null -w '%{http_code}' https://registry.npmjs.org/-/ping 2>&1 | tail -1)\"; " +
    "printf '%-48s %s\\n' 'model route, GET /v1/models (403 = refused)' \"$(curl -s -m 10 -o /dev/null -w '%{http_code}' http://jclaw-factory-gateway:8080/v1/models)\"",
  );
  console.log("== from a sandbox\n" + r.stdout);
  const turn = await sandbox.run({ name: "gateway turn", agent: sandcastle.claudeCode("claude-sonnet-5"), prompt: "Reply with the single word ok, then output <promise>COMPLETE</promise>.", logging: { type: "file", path: `${LOGS}/gateway-check.log` } });
  console.log("== agent turn through the model route: " + (/\bok\b/i.test(turn.stdout) ? "answered" : `NO ANSWER: ${turn.stdout.slice(-300)}`));
  console.log("== gateway log\n" + docker("logs", "jclaw-factory-gateway").split("\n").slice(-8).join("\n"));
}
execFileSync("/usr/bin/git", ["-C", CLONE, "branch", "-D", "factory/gateway-check"], { stdio: "ignore" });
