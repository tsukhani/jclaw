---
name: mcp
description: Connect JClaw to Model Context Protocol (MCP) servers for structured tool access and cross-platform integrations
version: 1.0.0
tools: [exec, filesystem]
icon: 🔌
---

# MCP - Model Context Protocol Integration

Integrate JClaw with MCP (Model Context Protocol) servers to access external tools, APIs, and services through a standardized interface.

## What is MCP?

Model Context Protocol (MCP) is an open protocol that standardizes how AI agents connect to external data sources and tools. MCP servers expose tools that can be dynamically discovered and invoked by the agent.

## When to Use This Skill

Use this skill when you need to:
- Connect to external services (GitHub, Slack, databases, etc.) via MCP
- Access tools that require authentication management
- Integrate with MCP-compatible servers

## Prerequisites

Before using MCP integrations:
1. An MCP server must be running (local or remote)
2. MCP server configuration (JSON config file or command)
3. Required environment variables set (if authenticating to services)

## Configuration

MCP servers are configured in `credentials/mcp-config.json`. Example structure:

```json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "your-token-here"
      }
    },
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/files"]
    }
  }
}
```

## Available MCP Servers

Common MCP servers you can connect to:

| Server | Purpose | Install |
|--------|---------|---------|
| `@modelcontextprotocol/server-github` | GitHub API access | `npx -y @modelcontextprotocol/server-github` |
| `@modelcontextprotocol/server-filesystem` | File operations | `npx -y @modelcontextprotocol/server-filesystem <path>` |
| `@modelcontextprotocol/server-postgres` | PostgreSQL queries | `npx -y @modelcontextprotocol/server-postgres <db_url>` |
| `@modelcontextprotocol/server-slack` | Slack integration | `npx -y @modelcontextprotocol/server-slack` |
| `@modelcontextprotocol/server-sqlite` | SQLite database access | `npx -y @modelcontextprotocol/server-sqlite <db_path>` |

## Workflow

### 1. Configure an MCP Server

Read the MCP configuration:
```
Read credentials/mcp-config.json using filesystem tool
```

### 2. Start the MCP Server

Use exec to start the MCP server:
```
Exec: npx -y @modelcontextprotocol/server-github
```

### 3. Use MCP Tools

Once connected, MCP servers expose tools that can be invoked. Tools are called via the MCP protocol.

## Example: GitHub via MCP

1. **Configure** `credentials/mcp-config.json` with GitHub token:
   ```json
   {
     "mcpServers": {
       "github": {
         "command": "npx",
         "args": ["-y", "@modelcontextprotocol/server-github"],
         "env": {
           "GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxx"
         }
       }
     }
   }
   ```

2. **Start** the GitHub MCP server

3. **Available tools** include:
   - `create_issue` - Create a new GitHub issue
   - `update_issue` - Update an existing issue
   - `add_issue_comment` - Comment on an issue
   - `search_issues` - Search for issues and PRs
   - `create_pull_request` - Create a PR
   - `list_commits` - List repository commits
   - `get_file_contents` - Read file contents
   - `push_files` - Commit and push files

## Example: Filesystem via MCP

The filesystem MCP server provides read-only or read-write access to a directory:

```json
{
  "mcpServers": {
    "project-files": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/project"]
    }
  }
}
```

**Available tools**:
- `read_file` - Read file contents
- `write_file` - Write to files (if write enabled)
- `list_directory` - List files in a directory
- `directory_tree` - Get recursive directory structure
- `move_file` - Move/rename files
- `search_files` - Search file contents

## Installation via Composio

Composio provides managed MCP servers with authentication handling:

1. Get API key from dashboard.composio.dev
2. Configure in credentials:
   ```json
   {
     "composio": {
       "apiKey": "your-composio-api-key"
     }
   }
   ```

3. Use Composio MCP server to access 850+ apps with 20,000+ tools

## Guards and Safety

- **Review all MCP server permissions** before connecting
- **Use read-only servers** when possible (filesystem access)
- **Keep tokens in credentials/**, never in the skill body
- **Verify server provenance** - only use trusted MCP servers
- **Monitor tool invocations** - MCP tools run with your user permissions

## Error Handling

Common issues:

| Error | Cause | Solution |
|-------|-------|----------|
| "MCP server not found" | Server not running | Start the MCP server process |
| "Authentication failed" | Invalid/missing token | Check credentials/mcp-config.json |
| "Tool not available" | Server doesn't expose that tool | Check server capabilities |
| "Connection refused" | Port already in use | Use different port or kill existing process |

## Troubleshooting

To diagnose MCP connection issues:

1. **Verify config syntax**:
   ```bash
   cat credentials/mcp-config.json | npx jsonlint
   ```

2. **Test server start manually**:
   ```bash
   npx -y @modelcontextprotocol/server-github
   ```

3. **Check environment variables** are exported:
   ```bash
   echo $GITHUB_PERSONAL_ACCESS_TOKEN
   ```

## References

- MCP Specification: https://modelcontextprotocol.io
- MCP Servers GitHub: https://github.com/modelcontextprotocol/servers
- Composio MCP Integration: https://composio.dev
