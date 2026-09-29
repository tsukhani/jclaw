// The factory's sandbox: agent containers on an internal network whose only exit is the gateway,
// which allowlists egress and holds the model credential. See gateway/gateway.mjs.
import * as fs from "node:fs";
import * as os from "node:os";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { docker } from "@ai-hero/sandcastle/sandboxes/docker";
import { ENV_FILE, FACTORY_HOME, HERE } from "./paths.ts";

// Sandcastle and every docker call here inherit this: the local images are native, and a shell that exports another
// platform makes Docker emulate them or fail to find them.
process.env.DOCKER_DEFAULT_PLATFORM = `linux/${process.arch === "x64" ? "amd64" : process.arch}`;

export const IMAGE = "jclaw-devcontainer:local";
const NETWORK = "jclaw-factory";
// The gateway's own way out. On Docker's default bridge any other container could reach its ports and have the
// credential attached to its model calls; no other container ever joins this network.
const EGRESS_NETWORK = "jclaw-factory-egress";
const GATEWAY = "jclaw-factory-gateway";
// The one container that reaches the internet and holds the credential runs Node and nothing else: no shell, no package
// manager, non-root. Pinned by digest so a re-tag upstream cannot change it.
const GATEWAY_IMAGE = "gcr.io/distroless/nodejs24-debian13@sha256:bb6b03d81066993293a10feda7250e8e1cc034035fe9b61cfceededa7c8bf04d";
const GATEWAY_DIR = `${HERE}/gateway`;
const GATEWAY_RUN = [
  "--name", GATEWAY, "--network", EGRESS_NETWORK, "--restart", "unless-stopped",
  "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "256m", "--pids-limit", "64",
  "-v", `${GATEWAY_DIR}:/gateway:ro`, "-v", `${ENV_FILE}:/run/factory/credential.env:ro`,
  GATEWAY_IMAGE, "/gateway/gateway.mjs",
];
// A running gateway created any other way is replaced when idle.
const GATEWAY_CONFIG = createHash("sha256").update(JSON.stringify(GATEWAY_RUN)).digest("hex").slice(0, 12);
const PROXY = `http://${GATEWAY}:3128`;
const NO_PROXY = `localhost,127.0.0.1,::1,${GATEWAY}`;

const dockerCli = (...args: string[]) =>
  execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();

const createGateway = () => {
  try {
    dockerCli("rm", "-f", GATEWAY);
  } catch {
    // Nothing to remove.
  }
  dockerCli("run", "-d", "--label", `factory.config=${GATEWAY_CONFIG}`, ...GATEWAY_RUN);
  dockerCli("network", "connect", NETWORK, GATEWAY);
};

// Whether the factory may start stories. The gateway is created when missing and outlives the harness (`unless-stopped`
// brings it back after a crash or a Docker restart); one the operator stopped is never started here, which is how the
// operator pauses the factory. A stale one is replaced only while `idle`, since running agents talk through it.
export const gatewayUp = (idle: boolean): boolean => {
  for (const [network, internal] of [[NETWORK, true], [EGRESS_NETWORK, false]] as const) {
    try {
      dockerCli("network", "inspect", network);
    } catch {
      dockerCli("network", "create", ...(internal ? ["--internal"] : []), network);
    }
  }
  let state: string;
  try {
    state = dockerCli("inspect", "-f", '{{.State.Status}} {{.HostConfig.RestartPolicy.Name}} {{index .Config.Labels "factory.config"}}', GATEWAY);
  } catch {
    createGateway();
    return true;
  }
  const [status, restart, config] = state.split(" ");
  if (status !== "running") return false;
  if (idle && config !== GATEWAY_CONFIG) createGateway();
  else if (restart !== "unless-stopped") dockerCli("update", "--restart", "unless-stopped", GATEWAY);
  return true;
};

// For the probe scripts: they never replace a gateway that running stories may be using.
export const ensureGateway = () => {
  if (!gatewayUp(false)) throw new Error(`${GATEWAY} is stopped; start it with: docker start ${GATEWAY}`);
};

// A read-only snapshot of the dependency cache and the wrapper, shared by every sandbox. Gradle arbitrates its user-home
// locks by messaging the owner over loopback, which cannot reach another container, so a shared writable ~/.gradle times
// out; each sandbox keeps a private one and reads dependencies from here through GRADLE_RO_DEP_CACHE.
const GRADLE_SEED = `${FACTORY_HOME}/gradle-seed`;
export const ensureGradleSeed = () => {
  if (!fs.existsSync(`${GRADLE_SEED}/caches`)) {
    fs.mkdirSync(`${GRADLE_SEED}/caches`, { recursive: true });
    fs.mkdirSync(`${GRADLE_SEED}/wrapper`, { recursive: true });
    // A developer who builds JClaw already holds its dependencies; without them each sandbox downloads its own.
    const source = [`${FACTORY_HOME}/gradle-home`, `${os.homedir()}/.gradle`].find((d) => fs.existsSync(`${d}/caches/modules-2`));
    if (source) {
      execFileSync("cp", ["-a", `${source}/caches/modules-2`, `${GRADLE_SEED}/caches/`]);
      if (fs.existsSync(`${source}/wrapper/dists`)) execFileSync("cp", ["-a", `${source}/wrapper/dists`, `${GRADLE_SEED}/wrapper/`]);
    }
    // Gradle refuses a read-only cache that still carries lock or GC state.
    execFileSync("find", [GRADLE_SEED, "(", "-name", "*.lock", "-o", "-name", "gc.properties", ")", "-delete"]);
  }
  // Gradle's JVMs ignore HTTPS_PROXY; they read proxy settings from the user home the hook copies this into.
  fs.writeFileSync(
    `${GRADLE_SEED}/gradle.properties`,
    [
      `systemProp.http.proxyHost=${GATEWAY}`,
      "systemProp.http.proxyPort=3128",
      `systemProp.https.proxyHost=${GATEWAY}`,
      "systemProp.https.proxyPort=3128",
      `systemProp.http.nonProxyHosts=localhost|127.*|[::1]|${GATEWAY}`,
      "",
    ].join("\n"),
  );
};

export const factorySandbox = () =>
  docker({
    imageName: IMAGE,
    // The image's own user; macOS file sharing lets it write the host-owned worktree.
    containerUid: 1000,
    containerGid: 1000,
    cpus: 4,
    network: NETWORK,
    mounts: [{ hostPath: GRADLE_SEED, sandboxPath: "/opt/gradle-seed", readonly: true }],
    env: {
      HTTPS_PROXY: PROXY,
      HTTP_PROXY: PROXY,
      https_proxy: PROXY,
      http_proxy: PROXY,
      NO_PROXY,
      no_proxy: NO_PROXY,
      ANTHROPIC_BASE_URL: `http://${GATEWAY}:8080`,
      // A placeholder the gateway discards; the real credential never enters the container.
      ANTHROPIC_AUTH_TOKEN: "factory-gateway-injects",
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1",
      DISABLE_AUTOUPDATER: "1",
      GRADLE_RO_DEP_CACHE: "/opt/gradle-seed/caches",
    },
  });

// WebFetch's preflight goes straight to api.anthropic.com, which the allowlist refuses by design.
const CLAUDE_SETTINGS = `mkdir -p ~/.claude && printf '{"skipWebFetchPreflight":true}' > ~/.claude/settings.json`;

// One chained command: Sandcastle runs hooks in parallel, and init-worktree's `play secret` is a Gradle build that needs
// the proxy settings copied first.
export const factoryHooks = {
  sandbox: {
    onSandboxReady: [
      {
        command: [
          "mkdir -p ~/.gradle",
          "cp -a /opt/gradle-seed/wrapper /opt/gradle-seed/gradle.properties ~/.gradle/",
          CLAUDE_SETTINGS,
          "./jclaw.sh init-worktree > /tmp/setup.log 2>&1",
        ].join(" && "),
      },
    ],
  },
};

// The planner only reads code, so it skips the Gradle and test-secret setup.
export const planHooks = { sandbox: { onSandboxReady: [{ command: CLAUDE_SETTINGS }] } };

// createSandbox ignores a hook's exit code, so the harness checks the outcome itself.
export const assertReady = async (sandbox: { exec: (cmd: string) => Promise<{ exitCode: number; stdout: string }> }) => {
  const r = await sandbox.exec("grep -q '^PLAY_SECRET=' certs/.env && grep -q '^PLAY_TEST_PORT=' certs/.env");
  if (r.exitCode !== 0) {
    throw new Error(`sandbox setup failed:\n${(await sandbox.exec("tail -20 /tmp/setup.log")).stdout}`);
  }
};
