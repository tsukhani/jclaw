// The factory's sandbox: agent containers on an internal network whose only exit is the gateway,
// which allowlists egress and holds the model credential. See gateway/gateway.mjs.
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { createBindMountSandboxProvider, type BindMountCreateOptions, type BindMountSandboxProvider, type BindMountSandboxProviderConfig } from "@ai-hero/sandcastle";
import { docker } from "@ai-hero/sandcastle/sandboxes/docker";
import { CLONE, ENV_FILE, FACTORY_HOME, HERE, REPO_ROOT } from "./paths.ts";

// Sandcastle and every docker call here inherit this: the local images are native, and a shell that exports another
// platform makes Docker emulate them or fail to find them.
process.env.DOCKER_DEFAULT_PLATFORM = `linux/${process.arch === "x64" ? "amd64" : process.arch}`;

// Git on the Mac, the harness's and Sandcastle's alike, runs no hooks and no fsmonitor whatever a repository's config
// says: sandboxes write into the clone's .git, and its hooks would otherwise run here, outside Docker.
const HOST_GIT = [["core.hooksPath", "/dev/null"], ["core.fsmonitor", "false"]];
const gitConfigBase = Number(process.env.GIT_CONFIG_COUNT ?? 0);
HOST_GIT.forEach(([key, value], i) => {
  process.env[`GIT_CONFIG_KEY_${gitConfigBase + i}`] = key;
  process.env[`GIT_CONFIG_VALUE_${gitConfigBase + i}`] = value;
});
process.env.GIT_CONFIG_COUNT = String(gitConfigBase + HOST_GIT.length);

export const IMAGE = "jclaw-devcontainer:local";
const NETWORK = "jclaw-factory";
// The gateway's own way out. On Docker's default bridge any other container could reach its ports and have the
// credential attached to its model calls; no other container ever joins this network.
const EGRESS_NETWORK = "jclaw-factory-egress";
const GATEWAY = "jclaw-factory-gateway";
// The one container that reaches the internet and holds the credential runs Node and nothing else: no shell, no package
// manager, non-root. Pinned by digest so a re-tag upstream cannot change it; Renovate moves the digest (renovate.json5).
const GATEWAY_IMAGE = "gcr.io/distroless/nodejs24-debian13:nonroot@sha256:bb6b03d81066993293a10feda7250e8e1cc034035fe9b61cfceededa7c8bf04d";
const GATEWAY_DIR = `${HERE}/gateway`;
const GATEWAY_RUN = [
  "--name", GATEWAY, "--network", EGRESS_NETWORK, "--restart", "unless-stopped",
  "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "256m", "--pids-limit", "64",
  "-v", `${GATEWAY_DIR}:/gateway:ro`, "-v", `${ENV_FILE}:/run/factory/credential.env:ro`,
  GATEWAY_IMAGE, "/gateway/gateway.mjs",
];
// A running gateway created any other way, or from other code or allowlist, is replaced when idle.
const GATEWAY_CONFIG = createHash("sha256")
  .update(JSON.stringify(GATEWAY_RUN))
  .update(fs.readdirSync(GATEWAY_DIR).sort().map((f) => fs.readFileSync(path.join(GATEWAY_DIR, f), "utf8")).join("\0"))
  .digest("hex")
  .slice(0, 12);
const PROXY = `http://${GATEWAY}:3128`;
const NO_PROXY = `localhost,127.0.0.1,::1,${GATEWAY}`;

const dockerCli = (...args: string[]) =>
  execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();

// The sandbox image is built from main's .devcontainer/Dockerfile, which reads .play-version and frontend/package.json,
// so rebuilding whenever main moves keeps the toolchain on the versions the repo pins. The layer cache makes a move
// that touches none of those inputs a matter of seconds. On failure the stale image stays, and the caller waits.
export const ensureImage = (clone: string, main: string, log: string): boolean => {
  let built = "";
  try {
    built = dockerCli("image", "inspect", "-f", '{{index .Config.Labels "factory.main"}}', IMAGE);
  } catch {
    // Not built yet.
  }
  if (built === main) return true;
  console.log(`[factory] building the sandbox image from main at ${main.slice(0, 8)}`);
  const out = fs.openSync(log, "w");
  try {
    execFileSync("docker", ["build", "-f", `${clone}/.devcontainer/Dockerfile`, "--label", `factory.main=${main}`, "-t", IMAGE, clone], { stdio: ["ignore", out, out] });
    return true;
  } catch {
    console.log(`[factory] the sandbox image did not build; stories wait for main to fix it (${log})`);
    return false;
  } finally {
    fs.closeSync(out);
  }
};

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

// BMAD is gitignored, so neither the clone nor a worktree carries it: a `bmad` story gets the operator's own install,
// copied here from the checkout with the factory's overrides and mounted read-only. Replaced only while idle, since a
// starting sandbox copies from it.
const BMAD_SEED = `${FACTORY_HOME}/bmad-seed`;
const BMAD_SEED_MOUNT = "/opt/bmad-seed";
const BMAD_MANIFEST = "_bmad/_config/manifest.yaml";
const BMAD_OVERRIDES = `${HERE}/bmad/bmad-build-auto.toml`;
export const ensureBmadSeed = (idle: boolean) => {
  const read = (file: string) => (fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "");
  const installed = read(path.join(REPO_ROOT, BMAD_MANIFEST));
  const stamp = installed && `${installed}\0${read(BMAD_OVERRIDES)}`;
  if (!idle || stamp === read(path.join(BMAD_SEED, "stamp"))) return;
  fs.rmSync(BMAD_SEED, { recursive: true, force: true });
  fs.mkdirSync(BMAD_SEED);
  const skills = path.join(REPO_ROOT, ".claude/skills");
  if (!installed || !fs.existsSync(skills)) return;
  // render/ holds snapshots keyed to the checkout's path; custom/ holds the tracked team config, which the worktree
  // already has, and the operator's personal overrides.
  const bmad = path.join(REPO_ROOT, "_bmad");
  fs.cpSync(bmad, path.join(BMAD_SEED, "_bmad"), {
    recursive: true,
    filter: (src) => !["render", "custom"].includes(path.relative(bmad, src).split(path.sep)[0]),
  });
  fs.cpSync(BMAD_OVERRIDES, path.join(BMAD_SEED, "_bmad/custom/bmad-build-auto.toml"));
  for (const name of fs.readdirSync(skills).filter((n) => n.startsWith("bmad-"))) {
    fs.cpSync(path.join(skills, name), path.join(BMAD_SEED, "skills", name), { recursive: true });
  }
  fs.writeFileSync(path.join(BMAD_SEED, "stamp"), stamp);
  console.log("[factory] BMAD seeded from the checkout's install");
};

// Everything this writes is gitignored, so the tree stays clean for bmad-build-auto's own check. Returns the version.
export const installBmad = async (sandbox: { exec: (cmd: string) => Promise<{ exitCode: number; stdout: string }> }): Promise<string> => {
  const r = await sandbox.exec(
    [
      `test -f ${BMAD_SEED_MOUNT}/${BMAD_MANIFEST}`,
      `cp -R ${BMAD_SEED_MOUNT}/_bmad/. _bmad/`,
      `mkdir -p .claude/skills && cp -R ${BMAD_SEED_MOUNT}/skills/. .claude/skills/`,
      `sed -n 's/^  version: //p' ${BMAD_MANIFEST}`,
    ].join(" && "),
  );
  if (r.exitCode !== 0) throw new Error(`BMAD is not installed in ${REPO_ROOT}, so a bmad story cannot run: run ./jclaw.sh setup there`);
  return r.stdout.trim();
};

// Docker refuses a quota above its VM's CPU count (Docker Desktop → Settings → Resources).
export const CPUS = Number(process.env.FACTORY_CPUS || 6);
if (!(CPUS > 0)) throw new Error(`FACTORY_CPUS must be a positive number, got "${process.env.FACTORY_CPUS}"`);

const dockerSandbox = () => {
  // Sandcastle refuses a mount whose host path is missing, and the BMAD seed is empty until one is installed.
  fs.mkdirSync(BMAD_SEED, { recursive: true });
  return docker({
    imageName: IMAGE,
    // The image's own user; macOS file sharing lets it write the host-owned worktree.
    containerUid: 1000,
    containerGid: 1000,
    cpus: CPUS,
    network: NETWORK,
    mounts: [
      { hostPath: GRADLE_SEED, sandboxPath: "/opt/gradle-seed", readonly: true },
      { hostPath: BMAD_SEED, sandboxPath: BMAD_SEED_MOUNT, readonly: true },
    ],
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
      // The clone's .git is read-only here apart from objects and agent refs, so gc could only fail.
      GIT_CONFIG_COUNT: "1",
      GIT_CONFIG_KEY_0: "gc.auto",
      GIT_CONFIG_VALUE_0: "0",
    },
  });
};

// Sandcastle's Docker provider takes no memory or process limit; each sandbox peaks near 5 GB during the full suite.
const SANDBOX_MEMORY = "6g";
const SANDBOX_PIDS = "8192";
const COMMON_GIT = path.join(CLONE, ".git");

// Sandcastle bind-mounts the clone's whole .git, whose hooks, config and info/attributes git on the Mac honours. Here it
// is read-only, except what a commit writes: objects, the agent branches and their reflogs, and this sandbox's own
// worktree directory. Other worktrees and main stay out of reach.
export const factorySandbox = () => {
  // docker() is built by createBindMountSandboxProvider, which keeps `create` on the object without typing it.
  const inner = dockerSandbox() as unknown as BindMountSandboxProvider & Pick<BindMountSandboxProviderConfig, "create">;
  if (typeof inner.create !== "function") throw new Error("Sandcastle's docker() no longer exposes create; the .git lockdown cannot apply");
  return createBindMountSandboxProvider({
    name: inner.name,
    env: inner.env,
    sandboxHomedir: inner.sandboxHomedir,
    create: async (options: BindMountCreateOptions) => {
      const gitdir = fs.readFileSync(path.join(options.worktreePath, ".git"), "utf8").match(/^gitdir:\s*(.+)$/m)?.[1].trim();
      if (!gitdir || path.dirname(path.resolve(gitdir)) !== path.join(COMMON_GIT, "worktrees")) {
        throw new Error(`${options.worktreePath} is not a worktree of ${CLONE}`);
      }
      const writable = [path.join(COMMON_GIT, "objects"), path.join(COMMON_GIT, "refs/heads/agent"), path.join(COMMON_GIT, "logs/refs/heads/agent"), path.resolve(gitdir)];
      for (const dir of writable) fs.mkdirSync(dir, { recursive: true });
      if (!options.mounts.some((m) => path.resolve(m.hostPath) === COMMON_GIT)) throw new Error(`Sandcastle no longer mounts ${COMMON_GIT}; refusing to guess`);
      const mounts = [
        ...options.mounts.map((m) => (path.resolve(m.hostPath) === COMMON_GIT ? { ...m, readonly: true } : m)),
        ...writable.map((dir) => ({ hostPath: dir, sandboxPath: dir, readonly: false })),
      ];
      const handle = await inner.create({ ...options, mounts });
      // Docker names a container's host after its ID.
      const id = (await handle.exec("cat /etc/hostname")).stdout.trim();
      dockerCli("update", "--memory", SANDBOX_MEMORY, "--memory-swap", SANDBOX_MEMORY, "--pids-limit", SANDBOX_PIDS, id);
      return handle;
    },
  });
};

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
