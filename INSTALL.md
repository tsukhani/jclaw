# Installing and running JClaw

This guide is for you if you install and run JClaw. It shows you how to install JClaw,
keep it running and keep it up to date. It also lists the optional extras you can add.

- New to JClaw? Start with the [README](README.md).
- To learn how to use JClaw, read the user guide inside the app.
  Once JClaw runs, open **Help → User Guide** in the sidebar, or go to <http://localhost:9000/guide>.
- To work on JClaw's code, read [DEVELOPMENT.md](https://github.com/tsukhani/jclaw/blob/main/DEVELOPMENT.md).

## Contents

- [Requirements](#requirements)
- [Quick install (one line)](#quick-install-one-line)
- [Managing JClaw](#managing-jclaw)
- [Updating](#updating)
- [The agent sandbox on Linux](#the-agent-sandbox-on-linux)
- [Running JClaw with Docker](#running-jclaw-with-docker)
- [Optional extras](#optional-extras)
- [HTTPS without browser warnings](#https-without-browser-warnings)
- [Running behind a reverse proxy](#running-behind-a-reverse-proxy)
- [Custom ports](#custom-ports)
- [Telemetry (OpenTelemetry)](#telemetry-opentelemetry)

---

## Requirements

To run JClaw, you need:

- **Java 25.** The one-line installer downloads a Zulu JRE 25 for JClaw, and JClaw keeps it
  patched, so you do not need to install Java yourself. To run JClaw on your own Java 25 or later
  instead, such as [Zulu](https://www.azul.com/downloads/?version=java-25) or Temurin, set
  `JCLAW_NO_JRE=1` ([Installer options](#installer-options)).
- **bash.** macOS and most Linux systems include it. On Alpine Linux, run `apk add bash`.
- **On Windows only: Git Bash or WSL** (the Windows Subsystem for Linux). JClaw's launcher is a
  bash script, so it runs inside one of them.
- **On Linux: bubblewrap (`bwrap`).** Agents run their shell commands inside it, and without it every
  agent's shell command is refused. On Ubuntu 23.10 and later it also needs a small AppArmor profile.
  [The agent sandbox on Linux](#the-agent-sandbox-on-linux) has the steps. The Docker image already
  includes `bwrap`.

You need nothing else. The JClaw bundle holds the
[Abundent Play 1.x fork](https://github.com/tsukhani/play1) and the app's dependencies.
It also holds the precompiled classes and the prebuilt web interface (the SPA).

**The browser tool is the one exception.** It needs Chromium and the Node.js that runs its
Playwright driver. The bundle and the Docker image do not include them. Together they take
roughly 300 MB. JClaw downloads both the first time an agent uses the browser. To download them
earlier, go to **Settings → Agents & Automation → Browser** and click **Download now**.

The download needs access to nodejs.org and to Playwright's download servers. If your machine
cannot reach them, do these steps:

1. Set `PLAYWRIGHT_NODEJS_PATH` to an installed Node.js.
2. Put Chromium under `PLAYWRIGHT_BROWSERS_PATH`.

Some features need more software. [Optional extras](#optional-extras) lists it.
To build JClaw from source, read [DEVELOPMENT.md](https://github.com/tsukhani/jclaw/blob/main/DEVELOPMENT.md).

---

## Quick install (one line)

Install JClaw with one command.

**On macOS or Linux**, open a terminal and run:

```bash
curl -fsSL https://github.com/tsukhani/jclaw/releases/latest/download/install.sh | sh
```

**On Windows**, open PowerShell and run:

```powershell
irm https://github.com/tsukhani/jclaw/releases/latest/download/install.ps1 | iex
```

The installer does these steps:

1. It downloads a self-contained Zulu JRE 25 from Azul into `~/.jclaw/jre`, and checks it against
   the SHA-256 that Azul publishes. JClaw runs on this Java, and
   **Settings → Maintenance → Java runtime** keeps it patched. The installer does this even when
   Java is already installed. It uses your own Java 25 or later, with the `jdk.incubator.vector`
   module, only when you set `JCLAW_NO_JRE=1`, or when Azul has no JRE for your system.
2. It downloads `jclaw-bundle.zip` from the latest GitHub Release. This self-contained bundle
   holds everything JClaw needs.
3. It checks the download. [How the installer checks the download](#how-the-installer-checks-the-download)
   tells you what it checks.
4. It unpacks the bundle into `~/.jclaw`.
5. It starts JClaw on <http://localhost:9000>.

Running the installer again over an existing install upgrades it, and keeps the Java it runs on.
To move an install from your own Java to JClaw's, use **Switch to Java** in
**Settings → Maintenance → Java runtime**.

On Windows, JClaw runs inside **Git Bash** or **WSL**, because its launcher is a bash script.
The installer uses Git Bash if it finds it. If it finds only WSL, it starts JClaw there.
If it finds neither, it still installs JClaw, and then tells you how to run it.

With WSL, JClaw runs on the Java inside your WSL distro, and `install.ps1` downloads no Java for
it. Install Java 25 inside the distro first. For the Java that JClaw keeps patched, run the
`install.sh` one-liner inside the distro instead: it installs JClaw in the distro's home folder,
with its own Zulu JRE.

### How the installer checks the download

Releases after 0.19.25 are signed.

- `install.sh` refuses a bundle if its signature or checksum fails the check. On a machine without
  `openssl`, it warns you, and then installs the bundle without the check.
- `install.ps1` cannot check the signature yet. It tells you so after the download.

### Installer options

You can change what the installer does with these environment variables. All of them are optional.

| Variable | Default | What it does |
| --- | --- | --- |
| `JCLAW_HOME` | `~/.jclaw` | The folder that JClaw installs into. |
| `JCLAW_VERSION` | `latest` | The release to install, for example `v0.14.7`. |
| `JCLAW_PORT` | `9000` | The port the installer reports when it starts JClaw. |
| `JCLAW_NO_START` | — | Set to `1` to install JClaw without starting it. |
| `JCLAW_INSTALL_JRE` | — | Set to `1` to download the Zulu JRE without asking. |
| `JCLAW_NO_JRE` | — | Use my own Java: set to `1` to run JClaw on your Java 25 or later, with `jdk.incubator.vector`, instead of downloading the Zulu JRE. |
| `JCLAW_NO_RC_EDIT` | — | Set to `1` to make the completion scripts without editing your shell's startup (rc) file. |
| `JCLAW_FORCE_REINSTALL` | — | Set to `1` to replace an existing install with a new one. **This deletes its database, workspace and credentials.** Without it, the installer upgrades an existing install in place. |
| `JCLAW_BIN_DIR` | `~/.local/bin` | Where the installer puts the `jclaw` command (`install.sh` only). |
| `JCLAW_BUNDLE_URL` | — | Install from this bundle URL (`file://` works too) instead of GitHub Releases. This skips the release-signature check (`install.sh` only). |
| `JCLAW_ASSUME_YES` | — | Set to `1` to answer yes to every question, such as installing `bubblewrap` and adding the AppArmor profile (`install.sh` only). |

For example, this command installs version v0.14.7 and does not start it:

```bash
# Pin a version and install without auto-starting:
curl -fsSL https://github.com/tsukhani/jclaw/releases/latest/download/install.sh \
  | JCLAW_VERSION=v0.14.7 JCLAW_NO_START=1 sh
```

---

## Managing JClaw

The installer puts a `jclaw` command on your `PATH` (through `~/.local/bin`). It also sets up
`<TAB>` completion for bash and zsh. Open a new terminal, and then use these commands:

| To do this | Run this |
| --- | --- |
| See if JClaw is running | `jclaw status` |
| Stop JClaw | `jclaw stop` |
| Restart JClaw | `jclaw restart` |

> **Caution:** `jclaw uninstall` deletes `~/.jclaw`, which holds your database, workspace and
> credentials.

To remove JClaw completely, run `jclaw uninstall`. It stops JClaw, removes the `PATH` and
completion setup, and deletes `~/.jclaw`.

The `jclaw` command runs the install's `jclaw.sh`. If you unzipped a `jclaw-bundle.zip` yourself,
go to that folder and run `./jclaw.sh` instead. To build a bundle yourself, read
[Building a release bundle](https://github.com/tsukhani/jclaw/blob/main/DEVELOPMENT.md#building-a-release-bundle).

`jclaw.sh` has more commands than the ones above:

```bash
# Start
./jclaw.sh start

# Stop
./jclaw.sh stop

# View logs
./jclaw.sh logs

# Database: backup (--list to show them), restore, H2 recovery, health
./jclaw.sh backup
./jclaw.sh restore <zip | backup id>   # validated first; a running instance restarts
./jclaw.sh repair                      # rebuild a damaged database with H2's Recover tool
./jclaw.sh compact                     # remove the credentials' old copies from the database file
./jclaw.sh db-clean                    # delete what the last successful repair left behind
./jclaw.sh db-status                   # file size, health verdict, last backup

# Admin
./jclaw.sh secret                      # generate or rotate PLAY_SECRET in certs/.env, keeping the old one as PLAY_SECRET_PREVIOUS
./jclaw.sh reset                       # clear the admin password; the next launch asks for a new one
./jclaw.sh completion install          # bash + zsh tab completion
./jclaw.sh shim                        # re-link the `jclaw` command into ~/.local/bin
```

---

## Updating

To update JClaw to the newest release, run `jclaw upgrade`. You can also go to
**Settings → System → Maintenance** in the app.

The upgrade keeps your data:

- It carries across your database, workspace, credentials, installed apps and edited configuration.
- It backs up the database first.
- If the new release fails to start, it goes back to the old release automatically.

JClaw keeps running while the new release downloads. It stops only for the swap itself.

The upgrade refuses a release after 0.19.25 if its signature or checksum fails the check.
It also refuses a release whose version is not the one you asked for.

| Command | What it does |
| --- | --- |
| `jclaw upgrade --check` | Shows what is available, without installing it. |
| `jclaw upgrade --version <tag>` | Installs a specific release, including an earlier one. |
| `jclaw upgrade --yes` | Skips the confirmation question. |

You can also run the one-line installer again. When JClaw is already installed, the installer hands
off to `jclaw upgrade`.

Other kinds of install update in other ways:

- With Docker, run `install-docker.sh` again (see [Verifying the image](#verifying-the-image)). A new
  image does not change your `seccomp-bwrap.json` or the AppArmor line in `docker-compose.yml`, and a
  release can need both; the command updates them. `docker compose pull && docker compose up -d`
  updates the image only.
- With a git clone, use `git pull`.

The [Releases page](https://github.com/tsukhani/jclaw/releases) has the release notes for every
version. The upgrade panel also shows the notes for the newest release.

---

## The agent sandbox on Linux

Every agent's shell command runs inside a sandbox. The Main Agent's commands are fenced off from JClaw's
own files, such as the key in `certs/.env` that opens every stored secret. A custom agent's commands see
only its own workspace. On macOS the sandbox is `sandbox-exec`, which is built in. On Linux it is
`bwrap` (bubblewrap), which you install.

Where no sandbox can start, JClaw refuses every agent's shell command, the Main Agent's included,
whatever `shell.sandbox` says. It never runs one outside the sandbox. **Settings → Shell Execution**
then says "This host has no sandbox mechanism (…)", with the cause in the brackets.

The one-line installer does the steps below for you on Linux, on a new install and when you run it
again over an existing one. It offers to install `bubblewrap`, offers the AppArmor profile on Ubuntu
23.10 and later, and runs the check. It asks before each change, and without a terminal to ask on it
changes nothing and says what is missing.

### Install bwrap

```bash
# Debian / Ubuntu
sudo apt install bubblewrap

# Fedora
sudo dnf install bubblewrap

# Arch
sudo pacman -S bubblewrap
```

### Check the sandbox on Linux

Run this as the account JClaw runs as. It is the check JClaw makes before it runs a command:

```bash
bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp true && echo sandbox ok
```

### Ubuntu 23.10 and later

Ubuntu 23.10 and later, 24.04 LTS included, let a program create the user namespace `bwrap` needs only
when an AppArmor profile allows it. Without one, the check fails with
`bwrap: setting up uid map: Permission denied`, and `sudo dmesg | grep apparmor` shows an
`apparmor="DENIED"` line naming `profile="unprivileged_userns"`.

To allow `bwrap`, and no other program, add a profile for it. It has the same shape as the profiles
Ubuntu ships for other programs that need a user namespace:

```bash
sudo tee /etc/apparmor.d/bwrap >/dev/null <<'EOF'
abi <abi/4.0>,
include <tunables/global>
profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,
  include if exists <local/bwrap>
}
EOF
sudo apparmor_parser -r /etc/apparmor.d/bwrap
```

Run the check again: it prints `sandbox ok`. JClaw needs no restart, since it checks again before its
next command. The profile lets any account on the host start `bwrap` with a user namespace, which
Ubuntu otherwise restricts. To remove it, run
`sudo apparmor_parser -R /etc/apparmor.d/bwrap && sudo rm /etc/apparmor.d/bwrap`.

A Docker host that runs Ubuntu 23.10 or later needs this profile too, as
[Running JClaw with Docker](#running-jclaw-with-docker) says.

---

## Running JClaw with Docker

Docker Compose is the simplest way to run JClaw on a server for everyday use. The
`docker-compose.yml` in this repository does these things:

- It pulls the prebuilt image from GHCR (the GitHub Container Registry).
- It serves JClaw on port **9000** (HTTP) and port **9443** (HTTPS, over TCP and UDP).
- It keeps `data/`, `logs/`, `workspace/`, `skills/` and `certs/` on the host, so your settings and
  conversations survive a restart. `certs/` holds the generated secret and the TLS files.
- It sets `restart: unless-stopped`. **Restart** under **Settings → System → Maintenance** stops the
  container, and this restart policy starts it again. A plain `docker run` needs `--restart unless-stopped`.
- It starts the container with the seccomp profile `seccomp-bwrap.json`. This is Docker's default
  profile with one rule added: it allows the five system calls (`clone`, `unshare`, `mount`,
  `umount2` and `pivot_root`) that the sandbox tool `bwrap` needs to create its namespaces, for the
  container's processes, and nothing else beyond Docker's default. The file has to sit beside
  `docker-compose.yml`; the release download already puts it there. A plain `docker run` needs
  `--security-opt seccomp=seccomp-bwrap.json` as well.
- It starts the container with `apparmor=unconfined`. On a host that uses AppArmor, such as Ubuntu,
  Docker's own AppArmor profile for containers refuses the mounts `bwrap` needs, even with the seccomp
  profile above. The seccomp profile, the dropped capabilities and the container's namespaces still
  apply. Where Docker uses no AppArmor, as Docker Desktop on macOS, the setting changes nothing. A plain
  `docker run` needs `--security-opt apparmor=unconfined` as well.

So agents' shell commands run inside the sandbox: custom agents' confined to their workspace, the main
agent's fenced off from JClaw's own files. On a host that runs Ubuntu 23.10 or later, the host also needs
the `bwrap` AppArmor profile from [Ubuntu 23.10 and later](#ubuntu-2310-and-later), or the sandbox still
cannot start in the container. Docker Desktop on macOS needs nothing more. `install-docker.sh` (see
[Verifying the image](#verifying-the-image)) puts all of these in place and checks the sandbox.

```bash
# Start in the background
docker compose up -d

# Follow logs
docker compose logs -f

# Stop and remove the container
docker compose down

# Run on custom ports (defaults: 9000, and 9443 for HTTPS)
JCLAW_PORT=8080 JCLAW_HTTPS_PORT=8443 docker compose up -d
```

You do not need to set up a `.env` file. The first time the container starts, it makes a
64-character `PLAY_SECRET` and saves it in `./certs/.env`. JClaw signs its session cookies with this
secret. Later starts read the same file. So you stay signed in when you run `docker compose down`
and then `docker compose up`.

The container runs JClaw in production mode. The image already contains the built web interface
(the Nuxt SPA). So the host does not need Node.js, pnpm or the Play tools. When the container is
healthy, open `http://localhost:9000`, or your own port.

### Check the sandbox

Custom agents always run their shell commands inside the sandbox. To check that the sandbox works
in your container, run:

```bash
docker compose exec jclaw sh -c 'bwrap --unshare-user --cap-drop ALL --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp true && echo sandbox ok'
```

The check starts `bwrap` from a shell, as JClaw does. On an Ubuntu host, a `bwrap` that Docker starts
directly stays refused even where the sandbox works for JClaw.

You can also open **Settings → Shell Execution**. When the sandbox cannot start, the panel says
"This host has no sandbox mechanism (…)", with the cause in the brackets.

The container runs as root, and a root `bwrap` makes no user namespace of its own unless asked, so the
command includes `--unshare-user`, and `--cap-drop ALL` leaves the sandboxed command no capabilities; JClaw
adds the same flags whenever it runs as root.

If the check fails, every agent's shell commands are refused, the main agent's included, whatever
`shell.sandbox` says, and the refusal names the cause. JClaw never runs them outside the sandbox.

The first line the check prints names the layer that refused it:

- `No permissions to create a new namespace`: the container runs under Docker's default seccomp
  profile. Start it with `seccomp-bwrap.json`, as `docker-compose.yml` does.
- `Failed to make / slave: Permission denied`: Docker's own AppArmor profile refused the mount. Start
  the container with `apparmor=unconfined`, as `docker-compose.yml` does. This refusal leaves no line in
  the host's kernel log.
- `setting up uid map: Permission denied`: the host restricts user namespaces, as Ubuntu 23.10 and
  later do. Add the host profile from [Ubuntu 23.10 and later](#ubuntu-2310-and-later).
  `sudo dmesg | grep apparmor` on the host then shows `apparmor="DENIED"` with
  `profile="unprivileged_userns"`.

Until the check passes, agents' commands stay refused; JClaw does not fall back to running them outside
the sandbox.

### Keep the secret with your data

JClaw encrypts the third-party credentials it stores, such as provider keys, channel tokens and MCP
secrets. The encryption key comes from `PLAY_SECRET`.

> **Caution:** Keep `./certs/.env` together with `./data`. Without it, JClaw cannot read any stored
> credential, and you must enter each one again.

To change (rotate) the secret:

1. In `./certs/.env`, rename the `PLAY_SECRET` line to `PLAY_SECRET_PREVIOUS`.
2. Restart the container.

The container then makes a new `PLAY_SECRET` beside the old one. JClaw encrypts the credentials
again with the new secret. Its log tells you when you can remove the `PLAY_SECRET_PREVIOUS` line.
Every existing `PLAY_SESSION` cookie stops working, so everyone must sign in again. That is the
purpose of a rotation.

### Setting the secret yourself

You can set the secret yourself instead. Do this when several hosts must share one cookie key, or
when your secret store manages rotation:

1. Put a `.env` file next to `docker-compose.yml`.
2. In it, write the line `PLAY_SECRET=<value>`.

Compose passes the value into the container, and the container uses it instead of making its own.
To rotate it, add the old value as `PLAY_SECRET_PREVIOUS=<value>` beside the new one.

You can also put `JCLAW_PORT` and `JCLAW_HTTPS_PORT` in that `.env` file, instead of on the command
line. Compose reads the file and fills its values into `docker-compose.yml`, which is how the port
settings get them.

### Verifying the image

`docker compose up -d` pulls the image tagged `latest`. A tag is only a pointer, and anyone who can
push to the registry can move it. A digest is the image's fingerprint, and it cannot move. A pull by
digest returns only the image that matches it. So every release after v0.19.28 also publishes an
`IMAGE_DIGEST` file. It holds the digest of the image that the release built. It is signed with the
same key that signs the installer downloads.

One command, `install-docker.sh`, checks that signature, pulls exactly that image and pins it. Then it
puts in place what the agent sandbox needs, checks the sandbox, and starts JClaw. Run it in the folder
that holds `docker-compose.yml`:

```bash
curl -fsSL https://github.com/tsukhani/jclaw/releases/latest/download/install-docker.sh | sh

# A specific release rather than the latest:
curl -fsSL https://github.com/tsukhani/jclaw/releases/latest/download/install-docker.sh | JCLAW_VERSION=vX.Y.Z sh
```

The command writes `JCLAW_IMAGE=ghcr.io/tsukhani/jclaw@sha256:…` to `.env`, where
`docker-compose.yml` reads it. Then it does these things:

- It writes `seccomp-bwrap.json` beside `docker-compose.yml`, copied from the image it just verified.
- If `docker-compose.yml` has the shipped `- seccomp=./seccomp-bwrap.json` line but no
  `- apparmor=unconfined` line, it adds that line beneath it and keeps your old file as
  `docker-compose.yml.bak`. A compose file laid out differently is left alone, and the command says
  which two lines to add.
- On a host that restricts user namespaces, such as Ubuntu 23.10 or later, it offers to add the
  `bwrap` AppArmor profile from [Ubuntu 23.10 and later](#ubuntu-2310-and-later). It asks first,
  because the profile changes the host's security settings. Without a terminal to ask on, it adds
  nothing and says so.
- It runs the sandbox check in a throwaway container. When the check passes, it asks whether to start
  JClaw, or to restart it if it is running, and then runs `docker compose up -d`. When the check
  fails, it starts nothing and names what to fix.

Set `JCLAW_NO_START=1` to do everything but start JClaw, and `JCLAW_ASSUME_YES=1` to answer yes to
every question. Releases also publish the command under its earlier name, `docker-pull-verified.sh`.

It pulls nothing in these cases:

- The signature is missing or wrong.
- The file names a different release.

On a machine without `openssl`, it cannot check the signature. It tells you so, and then pulls and
pins the published digest without the check. To upgrade a pinned install, run the command again.

To do the same check by hand:

```bash
V=vX.Y.Z   # the release you want
curl -fsSLO "https://github.com/tsukhani/jclaw/releases/download/$V/IMAGE_DIGEST"
curl -fsSLO "https://github.com/tsukhani/jclaw/releases/download/$V/IMAGE_DIGEST.sig"
curl -fsSLO https://github.com/tsukhani/jclaw/releases/latest/download/release-signing.pub

# Must print "Verified OK". Then read the file: it must name the release you asked for.
openssl dgst -sha256 -verify release-signing.pub -signature IMAGE_DIGEST.sig IMAGE_DIGEST
cat IMAGE_DIGEST

# Put the line this prints in .env beside docker-compose.yml, then start.
awk '{print "JCLAW_IMAGE=" $2}' IMAGE_DIGEST
docker compose up -d
```

Both ways are optional. Without `JCLAW_IMAGE`, Compose still pulls `latest`, with no check.

---

## Optional extras

JClaw runs without any of these. Each one adds a feature.

### Tesseract OCR (text in images)

Install Tesseract OCR if you want agents to read the text in images, scanned PDFs and image-only
PDFs. The `documents` tool needs it for this, and runs the `tesseract` program through Apache Tika.
Without it, JClaw gets no text from an image. If Tesseract is missing when JClaw starts, JClaw
writes a WARN line to its log.

```bash
# Debian / Ubuntu
sudo apt-get install tesseract-ocr

# macOS
brew install tesseract

# Windows
choco install tesseract
# or: winget install --id UB-Mannheim.TesseractOCR
```

On Windows, the UB Mannheim installer (the `winget` line) does not add Tesseract to `PATH`. JClaw
then gets no text from images. To fix this, set `ocr.tesseract.path` in
`conf/application.conf` to the folder that holds `tesseract.exe`, for example
`C:\Program Files\Tesseract-OCR`. Do not set it to the `.exe` file itself.

Tesseract reads English (`eng`) by default. For other languages, install their language packs, such
as `tesseract-ocr-fra` or `tesseract-ocr-jpn`. Then add them under **Settings → Image → OCR**,
for example `eng+fra+jpn`.

### Ollama (models on your own computer)

Install Ollama if you want agents to use AI models that run on your own machine, through the
`ollama-local` provider. On its first start, JClaw sets
`provider.ollama-local.baseUrl=http://localhost:11434/v1`, so the provider is already listed in
Settings. To make it work, install Ollama on the host and download (pull) a model.

When JClaw starts, it checks for Ollama:

- If Ollama answers, JClaw logs an INFO line with the number of models.
- If Ollama answers but does not work, JClaw logs a WARN line with an install hint.
- If Ollama is not running, JClaw logs nothing.

```bash
# Linux
curl https://ollama.com/install.sh | sh

# macOS
brew install ollama

# Windows — download the installer from https://ollama.com/download
```

After you install Ollama, pull a model:

```bash
ollama pull qwen2.5
```

Then add the model to JClaw:

1. Go to **Settings → Providers → LLM Providers**.
2. On the Ollama Local row, open **Manage models**.
3. Add the model. Run **Discover Models** against `http://localhost:11434/v1`, or use the
   **Add model** form. The form asks for the ID, display name, context window and max tokens.
4. On the **Agents** page, open an agent and set its **Default Provider** to `ollama-local`.

### LM Studio (models on your own computer)

Install LM Studio if you want agents to use the `lm-studio` provider. On its first start, JClaw sets
`provider.lm-studio.baseUrl=http://localhost:1234/v1`, so the provider is already listed under
"Local" in Settings. When JClaw starts, it checks for LM Studio:

- If LM Studio answers, JClaw logs an INFO line.
- If LM Studio answers but does not work, JClaw logs a WARN line with a launch hint.
- If LM Studio is not running, JClaw logs only a DEBUG line.

LM Studio is a desktop app. To set it up:

1. Install it from [https://lmstudio.ai](https://lmstudio.ai) (macOS DMG, Windows installer or
   Linux AppImage).
2. Start LM Studio.
3. Load a model in the **My Models** tab.
4. Go to the **Server** tab and click **Start Server**. The default port is 1234.

Then add the model to JClaw:

1. Go to **Settings → Providers → LLM Providers**.
2. On the LM Studio row, open **Manage models**.
3. Add the model. Run **Discover Models** against `http://localhost:1234/v1`, or use the
   **Add model** form.
4. On the **Agents** page, open an agent and set its **Default Provider** to `lm-studio`.

### uv (local helper programs)

Install [uv](https://docs.astral.sh/uv/) only if you want JClaw's optional local helper programs,
called sidecars. They handle speech recognition (ASR), telling speakers apart (diarization), spoken
replies (TTS), image generation and video generation. The fetch and stealth sidecars help agents
read websites. JClaw starts each sidecar with `uv run serve.py`. uv sets up the Python and the
packages that each sidecar needs. So you do not need Python on your system.

The image and video generation checks look for `uv` once and remember the answer. So after you
install uv, restart JClaw.

### ffmpeg (audio and video)

JClaw uses ffmpeg for these jobs:

- Local Whisper transcription. ffmpeg converts the audio to the PCM format that Whisper reads.
- Speaker diarization.
- Making large or lossless audio attachments smaller before JClaw sends them to a model.
- Encoding spoken replies for the channel that plays them.
- Taking frames from a video, so a model can interpret it.

The Docker image already includes ffmpeg.

```bash
# Debian / Ubuntu
sudo apt-get install ffmpeg

# macOS
brew install ffmpeg
```

---

## HTTPS without browser warnings

The Docker container also serves HTTPS on port **9443**, with HTTP/3 on the same UDP port. On its
first start, it makes a self-signed TLS certificate at `certs/host.cert`. HTTPS works with this
certificate, but browsers show a certificate warning first. Chrome also refuses HTTP/3 completely,
because HTTP/3 (QUIC) needs the certificate in the system's trust store.

To get HTTPS that browsers trust, and working HTTP/3, sign the certificate with a local certificate
authority (CA) from [mkcert](https://github.com/FiloSottile/mkcert). Do these steps on your host:

1. Install mkcert with your system's package manager:

   ```bash
   # macOS
   brew install mkcert

   # Debian / Ubuntu (22.04+)
   sudo apt install mkcert libnss3-tools

   # Fedora / RHEL
   sudo dnf install mkcert nss-tools

   # Arch
   sudo pacman -S mkcert nss

   # Windows
   choco install mkcert
   # or: scoop bucket add extras && scoop install mkcert
   ```

   Some older Linux versions do not package mkcert. On those, get the prebuilt program from
   [the mkcert releases page](https://github.com/FiloSottile/mkcert/releases). Then install
   `libnss3-tools` (Debian/Ubuntu) or `nss-tools` (Fedora) separately, so mkcert can register
   with Firefox.

2. Add mkcert's CA to your system's trust store, and to Firefox's (NSS) if Firefox is installed:

   ```bash
   sudo mkcert -install
   ```

3. Make a new certificate and key, signed by the CA. This writes `certs/host.cert` and `host.key`:

   ```bash
   ./jclaw.sh https
   ```

4. Restart the container, so the JVM loads the new certificate:

   ```bash
   docker compose restart jclaw
   ```

Later `docker compose up -d` runs use the same certificate. Run `./jclaw.sh https` again only after
you change mkcert's CA, or after you delete the `certs/` folder.

To turn HTTPS off on a host install, run `./jclaw.sh no-https`. It deletes the certificate and key,
and the next start uses HTTP/1.1 only. In the container, HTTPS stays on: the container makes a new
self-signed pair on its next start. Neither command changes `conf/application.conf`.

---

## Running behind a reverse proxy

A reverse proxy is a server that receives requests and passes them on to JClaw. Examples are nginx,
Caddy, Traefik and a CDN. If you use one, make sure that it does not change JClaw's cache headers.
Otherwise, browsers can keep an old copy of the web interface (a stale SPA). A proxy that changes or
ignores these headers is the one remaining cause of a stale SPA.

JClaw already sends the right headers:

- The HTML page (the shell) gets `Cache-Control: no-cache`, so the browser checks it again every
  time.
- The `_nuxt/` files have a content hash in their names. They get
  `public, max-age=31536000, immutable`, so the browser never checks them again.

This works only if your proxy leaves these headers alone. Set up your proxy like this:

- **Do not cache HTML.** The browser must check the page on every load. If it does not, it keeps an
  `index.html` that points to files that no longer exist.
- **Pass JClaw's `Cache-Control` headers through unchanged.** Do not add a general `expires` or
  `proxy_cache_valid` rule for all responses.
- **If you turn on proxy caching, leave out `/` and `/_nuxt/builds/`.** The build manifest under
  `builds/` names the current build. If the proxy caches it, browsers do not see a new release.
  You can cache everything else under `_nuxt/` for a long time, because it is content-hashed.

To find out if a browser has a stale copy:

1. Run `curl -s localhost:9000/api/status | jq .spaBuildId`. It shows the build ID that the server
   sends.
2. In the browser, find its build ID in DevTools → Network → `builds/latest.json`.
3. Compare the two IDs. If they match, the browser is up to date, and the problem is somewhere else.
   If they differ, a cache is holding an old shell.

This nginx setup lets JClaw decide what to cache:

```nginx
location / {
    proxy_pass http://127.0.0.1:9000;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # Let the app decide cacheability — it already differentiates the
    # always-revalidate shell from the immutable hashed chunks.
    proxy_cache off;
}
```

---

## Custom ports

To change the backend port, add `--backend-port` to any `jclaw.sh` mode. `--frontend-port` works in
dev mode only, where it sets the port of the Nuxt dev server. At startup, the frontend reads the
backend port from the `JCLAW_BACKEND_PORT` environment variable, so no files change.

```bash
# Dev mode with custom ports
./jclaw.sh --dev --backend-port 8080 --frontend-port 4000 start

# Bare start with custom backend port
./jclaw.sh --backend-port 8080 start
```

For Docker, see [Running JClaw with Docker](#running-jclaw-with-docker).

---

## Telemetry (OpenTelemetry)

JClaw can send traces and metrics over OTLP, from inside its own process. It sends nothing until you
turn this on. It sends:

- An HTTP server span for each request, named after its route.
- One `turn` span for each agent turn. Under it are the model call (with the GenAI semantic
  conventions), the model call's HTTP request and every JDBC statement.
- The `gen_ai.client.*`, `jclaw.turn.segment.duration` and `jvm.*` metrics.

To turn it on:

1. Run a collector with an OTLP receiver, for example
   `docker run --rm -p 4318:4318 otel/opentelemetry-collector-contrib`.
2. Go to **Settings → System → Telemetry** and turn telemetry on.
3. Set the endpoint. The default is `http://localhost:4318`.
4. Click **Send test span**.

Changes to the endpoint, headers, protocol and sampling apply at once. You do not need to restart
JClaw.

### Optional: the OpenTelemetry Java agent

The OpenTelemetry Java agent adds telemetry from Hibernate, Lucene and every other library it knows.
To attach it:

```bash
# Host install: add to conf/application.conf and restart
%prod.javaagent.path=/opt/otel/opentelemetry-javaagent.jar

# Container: compose it into the JVM options
docker run -e JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar" \
           -e OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318 jclaw
```

When the agent is attached, its `OTEL_*` settings apply, and JClaw reads them when the JVM starts.
The Telemetry panel says so, and its settings become read-only. JClaw then leaves HTTP server,
OkHttp, JDBC and JVM telemetry to the agent. The agent names its request spans after the Play route.
JClaw still sends the turn and GenAI spans, because the agent cannot make them. This setup was
tested with agent 2.31.1, beside the framework's own enhancer agent, on Netty 4.2.
