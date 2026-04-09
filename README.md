<h1 align="center">🦞 JClaw - Java-based Enterprise AI Assistant</h1>

<p align="center">
  <img src="combined-logo.png" width="650" alt="JClaw Logo">
</p>

<p align="center">
  <strong>JAVA FIRST. NO BLOAT. PURE POWER.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/jenkins/build?jobUrl=https%3A%2F%2Fjenkins.abundent.com%2Fjob%2FJClaw&style=for-the-badge&label=BUILD" alt="Build Status">
  <img src="https://img.shields.io/github/v/release/tsukhani/jclaw?style=for-the-badge&label=RELEASE&color=blue" alt="Release">
  <img src="https://img.shields.io/badge/LICENSE-MIT-green?style=for-the-badge" alt="License: MIT">
  <img src="https://img.shields.io/badge/JDK-25%2B-orange?style=for-the-badge" alt="JDK: 25+">
</p>

<br>

## Overview

JClaw is Abundent's AI-powered automation platform — built from scratch in **pure Java** on a customized [Play Framework 1.x](https://github.com/tsukhani/play1) foundation. It draws ideas and feature designs from two predecessor projects:

- **[OpenClaw](https://github.com/tsukhani/openclaw)** (Node.js/TypeScript) — agent orchestration, memory system, conversational AI patterns
- **[JavaClaw](https://github.com/jobrunr/javaclaw)** (Spring Boot) — job scheduling, background task processing, browser automation

The implementation is entirely original — no code is shared with either project. JClaw is built on Java library primitives (JDK HttpClient, ProcessBuilder, virtual threads, JPA) with no Spring, no heavy framework bloat, and no Node.js runtime on the server. The result is a leaner, faster, more maintainable platform for building AI agents and automation workflows.

---

## Screenshot

<p align="center">
  <img src="jclaw-screenshot.png" width="900" alt="JClaw Chat Interface">
</p>
<p align="center"><em>Web chat with memory-aware agents, tool execution, and markdown rendering.</em></p>

---

## Features

- 🤖 **Agent System** — Conversational AI agents with memory and context
- ⚡ **Job Scheduling** — Background tasks, cron jobs, and distributed execution
- 🔧 **Pure Java** — No Python/JavaScript runtimes required
- 📦 **Built-in Frontend** — Nuxt 3 SPA (Vue 3 + TypeScript)
- 🔌 **Plugin Architecture** — Modular, extensible design
- 🧠 **Memory & Context** — Persistent conversations across sessions
- 🚀 **Lightweight** — Minimal resource footprint, fast startup

---

## Directory Structure

```
jclaw/
├── app/                          # Application code
│   ├── controllers/              # HTTP controllers (Play 1.x pattern)
│   ├── models/                   # Domain models, entities
│   ├── services/                 # Business logic, services
│   ├── agents/                   # AI agent implementations
│   ├── jobs/                     # Background job handlers
│   ├── skills/                   # Modular skills/plugins
│   └── utils/                    # Utility classes
├── conf/                         # Play configuration
│   ├── application.conf          # Main app config
│   ├── routes                    # URL routing
│   ├── dependencies.yml          # Module dependencies
│   └── initial-data.yml          # Bootstrap data
├── frontend/                     # Nuxt 3 SPA
│   ├── app/                      # Nuxt app directory
│   ├── components/               # Vue components
│   ├── composables/              # Vue composables
│   ├── layouts/                  # Page layouts
│   ├── pages/                    # Nuxt pages
│   ├── stores/                   # Pinia stores
│   ├── server/                   # Server middleware
│   ├── public/                   # Static assets
│   └── nuxt.config.ts            # Nuxt configuration
├── lib/                          # Custom JARs (if needed)
├── modules/                      # Play modules (auto-managed)
├── public/                       # Static web assets
├── test/                         # Unit and integration tests
├── tmp/                          # Play temp/runtime files
├── logs/                         # Application logs
├── .github/                      # GitHub workflows (if migrated)
└── README.md                     # This file
```

---

## Getting Started

### Prerequisites

- JDK 25+ (Zulu recommended)
- Python 3.9+ (3.12 recommended) — Play's CLI commands are Python scripts
- [Abundent's Play Framework 1.x](https://github.com/tsukhani/play1) (`play` command in PATH)
- Node.js 20+ (24 recommended) for frontend
- pnpm for frontend package management

### Clone

```bash
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
cd jclaw
```

Dependencies are automatically installed when you start with `jclaw.sh`.

### Development

```bash
# Start both backend and frontend in dev mode
./jclaw.sh --dev start

# Stop
./jclaw.sh --dev stop

# Check status
./jclaw.sh --dev status
```

Default ports: backend on **:9000**, frontend on **:3000**.

### Production Deployment

```bash
# Deploy to /opt (creates /opt/jclaw), build everything, and start
./jclaw.sh --deploy /opt start

# Stop
./jclaw.sh --deploy /opt stop
```

This packages the app with `play dist`, unzips to `<dir>/jclaw/`, installs dependencies, builds the frontend, and starts both services in production mode.

To start an existing deployment (without re-packaging):

```bash
cd /opt/jclaw
./jclaw.sh start

# Stop
./jclaw.sh stop
```

### Custom Ports

Use `--backend-port` and `--frontend-port` with any mode. The script automatically updates the frontend API proxy to point at the correct backend port.

```bash
# Dev mode with custom ports
./jclaw.sh --dev --backend-port 8080 --frontend-port 4000 start

# Production deploy with custom ports (creates /opt/jclaw)
./jclaw.sh --deploy /opt --backend-port 8080 --frontend-port 4000 start
```

---

## Architecture

### Backend (Play 1.x + Java)

- **Models**: JPA entities with Play's model pattern
- **Controllers**: RESTful API endpoints
- **Services**: Business logic with dependency injection
- **Agents**: Conversational AI with memory/context persistence
- **Jobs**: Background processing powered by Play's built-in job system

### Frontend (Nuxt 3)

- **Framework**: Vue 3 + TypeScript + Nuxt 3
- **State**: Pinia stores
- **Styling**: Tailwind CSS
- **API**: Auto-generated from Play backend

---

## Key Principles

1. **Java-First** — Everything in Java. No Python, no Node for server-side logic.
2. **Minimal Dependencies** — Only bring in what we absolutely need.
3. **Memory & Context** — Agents remember. Context persists. Conversations flow.
4. **Async by Default** — Jobs run in background. APIs are non-blocking.
5. **Modular Skills** — Agents can automatically create, share, and chain skills. Skill primitives are reusable across agents and shareable with other JClaw users.

---

## Documentation

- [Play Framework 1.x](https://github.com/tsukhani/play1)
- [OpenClaw Reference](https://docs.openclaw.ai)
- [Nuxt 3 Docs](https://nuxt.com/docs)
- [JavaClaw Concepts](https://github.com/jobrunr/javaclaw)

---

## Contributing

This is an internal Abundent project. For questions or contributions, [reach out to the team](mailto:support@abundent.com).

---

## License

This project is licensed under the [MIT License](LICENSE).

---

*Built with ☕ Java and ❤️ by the Abundent crew.*
