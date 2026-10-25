<h1 align="center">JClaw - Java-based Enterprise AI Assistant</h1>

<p align="center">
  <img src="combined-logo.png" width="650" alt="JClaw Logo">
</p>

<p align="center">
  <strong>JAVA FIRST. NO BLOAT. PURE POWER.</strong>
</p>

<p align="center">
  <a href="https://jenkins.abundent.com/job/JClaw/"><img src="https://jenkins.abundent.com/buildStatus/icon?job=JClaw&style=flat-square" alt="Build Status"></a>
  <img src="https://img.shields.io/github/v/release/tsukhani/jclaw?style=flat-square&label=release&color=blue" alt="Release">
  <img src="https://img.shields.io/github/repo-size/tsukhani/jclaw?style=flat-square&label=repo%20size&color=blue" alt="Repo Size">
  <img src="https://img.shields.io/badge/license-PolyForm%20NC%201.0.0-blue?style=flat-square" alt="License: PolyForm Noncommercial 1.0.0">
  <img src="https://img.shields.io/badge/source-available-orange?style=flat-square" alt="Source-available">
  <img src="https://img.shields.io/badge/JDK-25%2B-orange?style=flat-square" alt="JDK: 25+">
</p>

> **Free for noncommercial use.** Commercial use needs a
> [commercial license](COMMERCIAL-LICENSE.md) from [Abundent Sdn Bhd](https://abundent.com).
> See [License](#license) for the details.

<br>

## What is JClaw?

JClaw lets you run your own AI assistants. We call each assistant an **agent**.

You talk to your agents in your web browser, or in Slack, Telegram or WhatsApp.
An agent can search the web, read your documents and do work on a schedule.
It also remembers what matters to you.

JClaw runs on your own computer or server. You choose the AI model behind each agent.
It can be a cloud service, such as OpenAI or OpenRouter, or a model that runs on your own computer.

## See it in action

<p align="center">
  <a href="https://youtu.be/9XxW7HWfC7o"><img src="https://img.youtube.com/vi/9XxW7HWfC7o/maxresdefault.jpg" width="900" alt="Watch the JClaw demo on YouTube"></a>
</p>
<p align="center"><em>A 2-minute demo of three JClaw features: tasks, the prompt library, and deep research.</em></p>

## What you can do

- 💬 **Chat with your agents.** Ask questions, give them work and read their answers.
- 📱 **Reach them where you already talk.** Connect an agent to Slack, Telegram or WhatsApp.
- ⏰ **Put work on a schedule.** A task runs at the time you choose, such as a daily news briefing.
  JClaw sends the result to the place you pick.
- 🔔 **Set reminders.** JClaw sends you a message at the time you ask for.
- 📝 **Keep a prompt library.** Save the instructions you use often, and use them again at any time.
- 🔬 **Research in depth.** An agent can search many sources, check them and write you a full report.
- 🌐 **Read the web.** Agents open web pages for you. They can also read a whole website in the background.
- 📄 **Read your files.** Agents read your documents, PDFs and images.
- 🎙️ **Talk out loud.** Speak to an agent and hear its answer in voice mode.
- 🧠 **Remember what matters.** Agents keep useful facts about you and your work.
  You can see and change what they remember.
- 👥 **Share out big jobs.** An agent can start helper agents that work at the same time.
- 🧩 **Teach it new skills.** Add skills, and connect other apps and services, so your agents can do more.
- 🗂️ **Get small apps built for you.** An agent can turn a request you repeat into a small web app.
- 🛡️ **Stay in control.** Agents ask for your approval before risky actions, such as running commands on your computer.

## Get started

### 1. Install JClaw

On **macOS or Linux**, open a terminal and run this command:

```bash
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/install.sh | sh
```

On **Windows**, open PowerShell and run this command:

```powershell
irm https://raw.githubusercontent.com/tsukhani/jclaw/main/install.ps1 | iex
```

The installer downloads JClaw and starts it.
JClaw needs Java 25. If your computer does not have it, the installer offers to download it for you.
On Windows, JClaw also needs Git Bash or WSL (the Windows Subsystem for Linux).

### 2. Open JClaw

Open <http://localhost:9000> in your web browser.
The first time, JClaw asks you to choose a password for your account.

### 3. Connect an AI model

Go to **Settings → Providers → LLM Providers** and set up one provider:

- For a **cloud service**, paste in the API key that the service gives you.
  An API key is a secret code that lets JClaw use your account.
- For a **model on your own computer**, install [Ollama](https://ollama.com) or
  [LM Studio](https://lmstudio.ai) first. [INSTALL.md](INSTALL.md#optional-extras) shows you how.

If the provider shows **0 models**, click the round-arrow button on its **models** row
(**Discover models from provider**). Then add the models you want.

Then tell your first agent to use that provider:

1. Open **Agents** and click the **main** agent.
2. Choose your provider in **Default Provider**.
3. Choose a model in **Default Model**.
4. Save the agent.

### 4. Say hello

Open **Chat** and send a message to the **main** agent.

The first time you sign in, JClaw offers you a short tour of the app.
The **User Guide**, under **Help** in the sidebar, explains every page.

### Everyday commands

Run these in a terminal:

| To do this | Run this |
| --- | --- |
| See if JClaw is running | `jclaw status` |
| Stop JClaw | `jclaw stop` |
| Restart JClaw | `jclaw restart` |
| Update to the newest version (your data stays) | `jclaw upgrade` |
| Remove JClaw and everything it stores | `jclaw uninstall` |

You can also update JClaw in the app, from **Settings → System → Maintenance**.

## Learn more

- **User guide**: how to use each page of JClaw. It is inside the app.
  Once JClaw runs, open **Help → User Guide** in the sidebar, or go to <http://localhost:9000/guide>.
- **[INSTALL.md](INSTALL.md)**: Docker, updates, HTTPS, optional extras and every install option.
- **[DEVELOPMENT.md](DEVELOPMENT.md)**: for the team that builds JClaw.
- **[Release notes](https://github.com/tsukhani/jclaw/releases)**: what changed in each version.

## Get help

- **Found a bug or have a question?** Open a [GitHub issue](https://github.com/tsukhani/jclaw/issues),
  or email [support@abundent.com](mailto:support@abundent.com).
- **Found a security problem?** Email [security@abundent.com](mailto:security@abundent.com).
  Do not open a public issue for it.
- **Want to contribute code?** Only the Abundent team writes JClaw's code, so we do not accept pull requests.
  [CONTRIBUTING.md](CONTRIBUTING.md) explains why, and lists the help we welcome.

## Where JClaw comes from

JClaw takes ideas from three earlier projects:
[OpenClaw](https://github.com/tsukhani/openclaw),
[JavaClaw](https://github.com/jobrunr/javaclaw) and
[Hermes](https://github.com/NousResearch/hermes-agent).
All of JClaw's code is new. It shares no code with them.
JClaw is written in Java, and it is built to be light and fast.

## License

JClaw is **source-available**. You can read and study its code.
It is not open-source software, because the free license limits how you can use it.

- **Noncommercial use is free**, under the [PolyForm Noncommercial License 1.0.0](LICENSE.md).
  This covers personal use, study, research and evaluation.
  It also covers use by registered non-profits, schools and other educational institutions, and government bodies.
- **Commercial use of any kind needs a license.** Get a
  [commercial license from Abundent Sdn Bhd](COMMERCIAL-LICENSE.md).
  The size of your company changes the price. It does not change the need for a license.

Versions up to and including **v0.15.4** were released under the MIT License.
They stay available under that license from their git tags.
The current license applies from **v0.16.0** onward.

---

*Built with ☕ Java and ❤️ by the Abundent crew.*
