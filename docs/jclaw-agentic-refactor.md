# JClaw Agentic Refactor Plan

**Source:** Application of "How to Build a Good Agentic AI System" Medium article  
**Target Repository:** https://github.com/tsukhani/jclaw  
**Created:** 2026-04-27

---

## Overview

This document outlines architectural changes to align JClaw with modern agentic AI system best practices:

1. **Single-purpose agents** (avoid "super agency")
2. **Context graphs** instead of flat conversation history
3. **MCP-style tool discovery**
4. **Planner/Executor agent separation**
5. **A2A (Agent-to-Agent) Protocol**
6. **Git worktree isolation** for agent workspaces

---

## 1. Agent Role Separation

### Current State
Monolithic agents in `app/agents/` with broad responsibilities.

### Target Architecture

```
app/agents/
├── core/
│   ├── PlannerAgent.java         # Orchestrates, delegates, tracks tasks
│   ├── ExecutorAgent.java        # Executes specific assigned tasks
│   ├── ResearcherAgent.java      # Information gathering, search
│   └── ReviewerAgent.java        # Validates outputs, QA
├── specialized/
│   ├── CodeGenerationAgent.java  # Creates/modifies code
│   ├── CodeReviewAgent.java      # Reviews code changes
│   └── DocumentationAgent.java   # Creates docs, comments
└── coordination/
    ├── AgentRegistry.java        # Discovery and lifecycle
    └── TaskRouter.java           # Routes tasks to appropriate agents
```

### Implementation: Agent Base Class with Capability System

```java
// app/agents/core/Agent.java
public abstract class Agent {
    protected final String agentId;
    protected final Set<Capability> capabilities;
    protected final SecurityProfile securityProfile;
    protected final ContextGraph contextGraph;
    
    public enum Capability {
        READ_CODE, WRITE_CODE, EXECUTE_SHELL, ACCESS_FILESYSTEM,
        CALL_EXTERNAL_API, POST_COMMENTS, CREATE_PULL_REQUEST
    }
    
    public static class SecurityProfile {
        public final boolean canAccessFilesystem;
        public final boolean canExecuteCommands;
        public final Set<Path> allowedPaths;
        public final int maxTokensPerRequest;
        
        public SecurityProfile(boolean fs, boolean exec, Set<Path> paths, int tokens) {
            this.canAccessFilesystem = fs;
            this.canExecuteCommands = exec;
            this.allowedPaths = paths;
            this.maxTokensPerRequest = tokens;
        }
    }
    
    protected Agent(String id, Set<Capability> caps, SecurityProfile profile) {
        this.agentId = id;
        this.capabilities = Collections.unmodifiableSet(caps);
        this.securityProfile = profile;
        this.contextGraph = new ContextGraph(id);
    }
    
    // Subclasses implement
    public abstract TaskResult execute(Task task);
    
    // Verify capability before execution
    protected void verifyCapability(Capability required) {
        if (!capabilities.contains(required)) {
            throw new SecurityException(
                "Agent " + agentId + " lacks capability: " + required
            );
        }
    }
}

// Example: CodeReviewAgent with minimal privileges
public class CodeReviewAgent extends Agent {
    public CodeReviewAgent(String id) {
        super(
            id,
            Set.of(Capability.READ_CODE, Capability.POST_COMMENTS),
            new SecurityProfile(
                false,  // No filesystem write
                false,  // No shell execution
                Set.of(), // No path access needed (reads via API)
                8000    // Limited context
            )
        );
    }
    
    @Override
    public TaskResult execute(Task task) {
        verifyCapability(Capability.READ_CODE);
        // Implementation: fetch code via API, review, post comments
    }
}
```

---

## 2. Context Graph System

### Problem
Current flat conversation history loses structure, causes confusion.

### Solution: Context Graph Instead of Context Window

```java
// app/agents/context/ContextNode.java
@Entity
@Table(name = "context_nodes")
public class ContextNode extends Model {
    
    @Enumerated(EnumType.STRING)
    public NodeType type;           // TASK, OBSERVATION, DECISION, TOOL_RESULT, EXTERNAL
    
    @ManyToOne
    public ContextNode parent;      // Hierarchical relationships
    
    @ManyToMany
    @JoinTable(name = "context_relationships")
    public Set<ContextNode> related; // Graph relationships (peer, causal, similar)
    
    @Enumerated(EnumType.STRING)
    public RelationshipType relationshipType;
    
    @Column(columnDefinition = "TEXT")
    public String content;
    
    @Column(columnDefinition = "TEXT")
    public String summary;          // LLM-generated summary for retrieval
    
    public String sourceAgent;        // Which agent created this
    public DateTime createdAt;
    public DateTime relevanceExpiry; // TTL for stale context
    
    @ElementCollection
    @CollectionTable(name = "context_keywords")
    public Set<String> keywords;    // For semantic search
    
    public JsonObject metadata;      // Flexible extra data
    
    public enum NodeType {
        TASK,           // Assigned task
        SUBTASK,        // Decomposed task
        OBSERVATION,    // Something noticed (code, output, error)
        DECISION,       // Agent decision with reasoning
        TOOL_RESULT,    // Output from tool execution
        EXTERNAL,       // User input, file change, external event
        SUMMARY         // Aggregated summary node
    }
    
    public enum RelationshipType {
        CAUSES,         // A caused B
        DEPENDS_ON,     // B depends on A
        SIMILAR_TO,     // Semantic similarity
        PART_OF,        // B is part of A
        REPLACES,       // A supersedes B
        CONTRADICTS     // A contradicts B
    }
}

// app/agents/context/ContextGraph.java
@Service
public class ContextGraph {
    private final String agentId;
    
    public ContextSnapshot getRelevantContext(Task task, int maxNodes) {
        // 1. Query by task keywords
        Set<ContextNode> byKeywords = ContextNode.find(
            "keywords in ? and relevanceExpiry > ?",
            task.getKeywords(), DateTime.now()
        ).fetch();
        
        // 2. Traverse graph from current task node
        Set<ContextNode> byTraversal = traverseFrom(task.getContextNode(), 3);
        
        // 3. Rank by relevance (recency + relationship strength)
        List<ContextNode> ranked = rankByRelevance(
            Sets.union(byKeywords, byTraversal),
            task
        );
        
        return new ContextSnapshot(ranked.stream()
            .limit(maxNodes)
            .collect(Collectors.toList()));
    }
    
    public void addRelationship(ContextNode from, ContextNode to, 
                                 RelationshipType type) {
        // Add bidirectional relationship with type
    }
    
    public ContextNode summarizeSubgraph(Set<ContextNode> nodes) {
        // Create summary node for long chains
    }
}

// app/agents/context/ContextSnapshot.java
public class ContextSnapshot {
    private final List<ContextNode> nodes;
    
    public String toPrompt() {
        // Serialize to structured prompt for LLM
        StringBuilder sb = new StringBuilder();
        for (ContextNode node : nodes) {
            sb.append("[").append(node.type).append("] ");
            sb.append(node.summary != null ? node.summary : node.content);
            sb.append("\n");
        }
        return sb.toString();
    }
    
    public JsonObject toJson() {
        // For A2A serialization
    }
    
    public static ContextSnapshot fromJson(JsonObject json) {
        // Deserialize from A2A message
    }
}
```

### Database Schema

```sql
-- Flyway migration
CREATE TABLE context_nodes (
    id VARCHAR(36) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    parent_id VARCHAR(36) REFERENCES context_nodes(id),
    content TEXT,
    summary TEXT,
    source_agent VARCHAR(64),
    created_at TIMESTAMP,
    relevance_expiry TIMESTAMP,
    metadata JSONB
);

CREATE TABLE context_relationships (
    from_id VARCHAR(36) REFERENCES context_nodes(id),
    to_id VARCHAR(36) REFERENCES context_nodes(id),
    relationship_type VARCHAR(20),
    PRIMARY KEY (from_id, to_id)
);

CREATE TABLE context_keywords (
    context_node_id VARCHAR(36) REFERENCES context_nodes(id),
    keyword VARCHAR(64),
    PRIMARY KEY (context_node_id, keyword)
);

CREATE INDEX idx_context_type ON context_nodes(type);
CREATE INDEX idx_context_expiry ON context_nodes(relevance_expiry);
CREATE INDEX idx_context_agent ON context_nodes(source_agent);
```

---

## 3. MCP-Style Tool Discovery

### Problem
Tools hardcoded, agents can't discover capabilities dynamically.

### Solution: Tool Registry with Schema

```java
// app/agents/tools/MCPTool.java
public interface MCPTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    JsonSchema getOutputSchema();
    ToolCategory getCategory();
    Set<Agent.Capability> requiredCapabilities();
    ToolResult execute(JsonObject params, ExecutionContext ctx);
}

// app/agents/tools/ToolRegistry.java
@Service
public class ToolRegistry {
    private final Map<String, MCPTool> tools = new ConcurrentHashMap<>();
    private final Map<ToolCategory, Set<MCPTool>> byCategory = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void discoverTools() {
        // Auto-discover from skills/
        // Scan for classes implementing MCPTool
    }
    
    public void register(MCPTool tool) {
        tools.put(tool.getName(), tool);
        byCategory.computeIfAbsent(tool.getCategory(), k -> new HashSet<>()).add(tool);
    }
    
    public String buildToolsPrompt(Agent agent) {
        // Only include tools agent has capabilities for
        StringBuilder sb = new StringBuilder("Available Tools:\n\n");
        
        for (MCPTool tool : tools.values()) {
            if (agent.capabilities.containsAll(tool.requiredCapabilities())) {
                sb.append("- ").append(tool.getName()).append("\n");
                sb.append("  ").append(tool.getDescription()).append("\n");
                sb.append("  Schema: ").append(tool.getInputSchema().toString()).append("\n\n");
            }
        }
        
        return sb.toString();
    }
    
    public ToolResult execute(String toolName, JsonObject params, 
                            Agent agent, ExecutionContext ctx) {
        MCPTool tool = tools.get(toolName);
        if (tool == null) {
            throw new ToolNotFoundException(toolName);
        }
        
        // Security check
        if (!agent.capabilities.containsAll(tool.requiredCapabilities())) {
            throw new SecurityException("Agent lacks required capabilities");
        }
        
        return tool.execute(params, ctx);
    }
}

// Example tool implementation
@Service
public class GitWorktreeTool implements MCPTool {
    
    @Override
    public String getName() { return "git.create_worktree"; }
    
    @Override
    public String getDescription() { 
        return "Creates a new git worktree for isolated agent workspace"; 
    }
    
    @Override
    public JsonSchema getInputSchema() {
        return JsonSchema.builder()
            .addProperty("branch", JsonSchema.Type.STRING, true)
            .addProperty("base_path", JsonSchema.Type.STRING, true)
            .addProperty("task_id", JsonSchema.Type.STRING, true)
            .build();
    }
    
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }
    
    @Override
    public Set<Agent.Capability> requiredCapabilities() {
        return Set.of(Agent.Capability.ACCESS_FILESYSTEM);
    }
    
    @Override
    public ToolResult execute(JsonObject params, ExecutionContext ctx) {
        // Implementation
    }
}
```

---

## 4. Job Scheduling for Deterministic Workflows

### Problem
Agent tasks run unpredictably

### Solution: Scheduled Agent Cycles

```java
// app/jobs/AgentOrchestratorJob.java
@Every("5min")
public class AgentOrchestratorJob extends Job {
    
    @Inject PlannerAgent planner;
    @Inject AgentRegistry registry;
    
    public void doJob() {
        // Fixed deterministic cycle
        
        // 1. Review pending tasks
        List<Task> pending = Task.find("status = ?", TaskStatus.PENDING).fetch();
        
        for (Task task : pending) {
            // 2. Planner decomposes if needed
            if (task.needsDecomposition()) {
                Plan plan = planner.decompose(task);
                task.setPlan(plan);
                task.save();
            }
            
            // 3. Assign to available executor
            Optional<ExecutorAgent> executor = registry.findAvailableExecutor();
            if (executor.isPresent()) {
                executor.get().assign(task);
                task.status = TaskStatus.ASSIGNED;
                task.save();
            }
        }
        
        // 4. Check for completed tasks, validate
        List<Task> completed = Task.find("status = ?", TaskStatus.COMPLETED).fetch();
        for (Task task : completed) {
            if (task.requiresReview()) {
                task.status = TaskStatus.PENDING_REVIEW;
                task.save();
                // Notify reviewer agent
            }
        }
    }
}

// app/jobs/ExecutionMonitorJob.java
@Every("1min")
public class ExecutionMonitorJob extends Job {
    
    public void doJob() {
        // Monitor running tasks for timeouts, errors
        // Cancel stuck tasks
        // Retry failed tasks (with backoff)
    }
}
```

---

## 5. A2A (Agent-to-Agent) Protocol

### Problem
Agents can't collaborate

### Solution: Message-Based Communication

```java
// app/agents/a2a/A2AMessage.java
public class A2AMessage {
    public final String messageId;
    public final String correlationId;  // For request/response pairing
    public final AgentID from;
    public final AgentID to;
    public final MessageType type;
    public final JsonObject payload;
    public final ContextSnapshot contextSnapshot;
    public final DateTime timestamp;
    public final int ttlSeconds;         // Message expiration
    
    public enum MessageType {
        TASK_ASSIGNMENT,      // Planner -> Executor
        TASK_RESULT,          // Executor -> Planner
        CLARIFICATION_REQUEST, // Executor -> Planner (need more info)
        TOOL_REQUEST,         // Agent -> ToolRegistry
        TOOL_RESULT,          // ToolRegistry -> Agent
        DELEGATION,           // Agent -> Agent (peer delegation)
        NOTIFICATION,         // Broadcast
        HEARTBEAT             // Health check
    }
}

// app/agents/a2a/A2ABroker.java
@Service
public class A2ABroker {
    
    @Inject AgentRegistry registry;
    @Inject AuditLog auditLog;
    
    private final Map<AgentID, BlockingQueue<A2AMessage>> mailboxes = new ConcurrentHashMap<>();
    
    public void send(A2AMessage message) {
        // Security: Validate sender owns this identity
        if (!verifySender(message)) {
            auditLog.securityEvent("A2A_SPOOF_ATTEMPT", message);
            return;
        }
        
        // Log all inter-agent communication
        auditLog.a2aMessage(message);
        
        // Route to recipient mailbox
        mailboxes.computeIfAbsent(message.to, k -> new LinkedBlockingQueue<>())
                 .offer(message);
    }
    
    public List<A2AMessage> poll(AgentID recipient, int maxMessages) {
        BlockingQueue<A2AMessage> mailbox = mailboxes.get(recipient);
        if (mailbox == null) return Collections.emptyList();
        
        List<A2AMessage> messages = new ArrayList<>();
        mailbox.drainTo(messages, maxMessages);
        return messages;
    }
    
    public A2AMessage requestReply(A2AMessage request, Duration timeout) {
        send(request);
        // Wait for response with matching correlationId
        return awaitResponse(request.correlationId, timeout);
    }
}

// app/agents/core/PlannerAgent.java
public class PlannerAgent extends Agent {
    
    @Inject A2ABroker broker;
    
    public Plan createPlan(Task task) {
        // Decompose into subtasks
        List<Subtask> subtasks = decompose(task);
        
        Plan plan = new Plan(task, subtasks);
        
        for (Subtask subtask : subtasks) {
            // Find appropriate executor
            ExecutorAgent executor = selectExecutor(subtask);
            
            // Create context snapshot for this subtask only
            ContextSnapshot snapshot = contextGraph.getRelevantContext(subtask, 20);
            
            // Send A2A message
            A2AMessage msg = new A2AMessage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                this.agentId,
                executor.getAgentId(),
                A2AMessage.MessageType.TASK_ASSIGNMENT,
                subtask.toJson(),
                snapshot,
                DateTime.now(),
                300  // 5 min TTL
            );
            
            broker.send(msg);
        }
        
        return plan;
    }
}

// app/agents/core/ExecutorAgent.java
public class ExecutorAgent extends Agent implements Runnable {
    
    @Inject A2ABroker broker;
    @Inject ToolRegistry tools;
    
    @Override
    public void run() {
        // Background thread polls for messages
        while (running) {
            List<A2AMessage> messages = broker.poll(this.agentId, 10);
            
            for (A2AMessage msg : messages) {
                handleMessage(msg);
            }
        }
    }
    
    private void handleMessage(A2AMessage msg) {
        switch (msg.type) {
            case TASK_ASSIGNMENT -> executeTask(msg);
            case CLARIFICATION_REQUEST -> handleClarification(msg);
            case TOOL_RESULT -> handleToolResult(msg);
        }
    }
    
    private void executeTask(A2AMessage msg) {
        // Load context from snapshot
        contextGraph.loadSnapshot(msg.contextSnapshot);
        
        Subtask task = Subtask.fromJson(msg.payload);
        
        // Execute
        TaskResult result = execute(task);
        
        // Respond
        A2AMessage response = new A2AMessage(
            UUID.randomUUID().toString(),
            msg.correlationId,  // Link back to original
            this.agentId,
            msg.from,           // Back to planner
            A2AMessage.MessageType.TASK_RESULT,
            result.toJson(),
            contextGraph.createSnapshot(),
            DateTime.now(),
            60
        );
        
        broker.send(response);
    }
}
```

---

## 6. Git Worktree Isolation

### Problem
Agents need isolated, reproducible workspaces

### Solution: Worktree Manager

```java
// app/services/WorkspaceManager.java
@Service
public class WorkspaceManager {
    
    private final Path baseWorktreeDir;
    
    public AgentWorkspace createWorkspace(Task task, String baseBranch) {
        String worktreeId = task.id + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path worktreePath = baseWorktreeDir.resolve(worktreeId);
        
        // Create git worktree
        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "add", "-b", worktreeId,
            worktreePath.toString(), "origin/" + baseBranch
        );
        pb.inheritIO();
        
        try {
            Process process = pb.start();
            process.waitFor(30, TimeUnit.SECONDS);
            
            if (process.exitValue() != 0) {
                throw new WorkspaceException("Failed to create worktree");
            }
            
            return new AgentWorkspace(worktreeId, worktreePath, task);
            
        } catch (Exception e) {
            throw new WorkspaceException(e);
        }
    }
    
    public void destroyWorkspace(AgentWorkspace workspace) {
        // Remove worktree
        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "remove", workspace.getPath().toString()
        );
        
        try {
            Process process = pb.start();
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            Logger.error(e, "Failed to remove worktree");
        }
        
        // Cleanup
        workspace.getPath().toFile().delete();
    }
}

// app/agents/workspace/AgentWorkspace.java
public class AgentWorkspace {
    private final String worktreeId;
    private final Path path;
    private final Task task;
    private final DateTime createdAt;
    private boolean destroyed = false;
    
    public void executeInWorkspace(WorkspaceAction action) {
        if (destroyed) {
            throw new IllegalStateException("Workspace already destroyed");
        }
        
        SecurityManager.restrictTo(path);  // JVM security
        try {
            action.execute(path);
        } finally {
            SecurityManager.clearRestriction();
        }
    }
    
    public String readFile(String relativePath) {
        Path file = path.resolve(relativePath).normalize();
        // Verify it's still within workspace
        if (!file.startsWith(path)) {
            throw new SecurityException("Path traversal attempt");
        }
        return Files.readString(file);
    }
    
    public void writeFile(String relativePath, String content) {
        verifyWithinWorkspace(relativePath);
        Files.writeString(path.resolve(relativePath), content);
    }
}

// Usage in ExecutorAgent
public class CodeGenerationAgent extends ExecutorAgent {
    
    @Inject WorkspaceManager workspaceManager;
    
    @Override
    public TaskResult execute(Task task) {
        // Create isolated workspace
        AgentWorkspace workspace = workspaceManager.createWorkspace(
            task, task.getBaseBranch()
        );
        
        try {
            return workspace.executeInWorkspace(path -> {
                // All file operations restricted to this worktree
                // Generate code, write files, run tests
                // If agent tries to escape, SecurityManager blocks it
                
                return generateCode(path, task);
            });
        } finally {
            // Cleanup
            workspaceManager.destroyWorkspace(workspace);
        }
    }
}
```

---

## 7. Human-in-the-Loop Hooks

### Implementation

```java
// app/agents/core/PlannerAgent.java
public Plan createPlan(Task task) {
    Plan plan = decompose(task);
    
    // High-risk detection
    if (plan.hasRiskLevel(RiskLevel.HIGH)) {
        // Pause and request approval
        task.status = TaskStatus.PENDING_APPROVAL;
        task.save();
        
        notificationService.sendToUser(
            task.getUser(),
            ApprovalRequest.builder()
                .taskId(task.id)
                .description(plan.getDescription())
                .risks(plan.identifyRisks())
                .approveUrl("/api/tasks/" + task.id + "/approve")
                .rejectUrl("/api/tasks/" + task.id + "/reject")
                .build()
        );
        
        return null; // Pause execution
    }
    
    return plan;
}

// frontend: Approval UI
// pages/approvals/[taskId].vue
// Show plan details, risks, diff preview
// Approve/Reject buttons

// Resume on approval
@Post("/api/tasks/{taskId}/approve")
public void approveTask(String taskId) {
    Task task = Task.findById(taskId);
    task.status = TaskStatus.APPROVED;
    task.save();
    
    // Resume planner
    planner.resume(task);
}
```

---

## 8. Observability & Audit Logging

### Implementation

```java
// app/services/AuditLog.java
@Service
public class AuditLog {
    
    public void agentDecision(Agent agent, String decision, 
                               ContextSnapshot context,
                               List<String> toolsUsed) {
        AuditEntry entry = new AuditEntry();
        entry.type = AuditType.AGENT_DECISION;
        entry.agentId = agent.getAgentId();
        entry.decision = decision;
        entry.contextNodes = context.getNodeIds();
        entry.toolsUsed = toolsUsed;
        entry.timestamp = DateTime.now();
        entry.save();
    }
    
    public void a2aMessage(A2AMessage msg) {
        AuditEntry entry = new AuditEntry();
        entry.type = AuditType.A2A_COMMUNICATION;
        entry.fromAgent = msg.from;
        entry.toAgent = msg.to;
        entry.messageType = msg.type;
        entry.payloadHash = hash(msg.payload);
        entry.save();
    }
    
    public void toolExecution(String toolName, Agent agent,
                               JsonObject params, ToolResult result) {
        AuditEntry entry = new AuditEntry();
        entry.type = AuditType.TOOL_EXECUTION;
        entry.toolName = toolName;
        entry.agentId = agent.getAgentId();
        entry.inputParams = params;
        entry.outputHash = hash(result);
        entry.success = result.isSuccess();
        entry.durationMs = result.getDuration();
        entry.save();
    }
    
    public void securityEvent(String eventType, Object details) {
        AuditEntry entry = new AuditEntry();
        entry.type = AuditType.SECURITY_EVENT;
        entry.eventType = eventType;
        entry.details = Json.serialize(details);
        entry.severity = Severity.HIGH;
        entry.save();
        
        // Immediate alert
        alertService.sendSecurityAlert(entry);
    }
}
```

---

## Implementation Priority

### Phase 1: Foundation (Week 1-2)
1. Agent base class with capability system
2. ContextGraph entities and database migrations
3. ToolRegistry with MCP interface

### Phase 2: Communication (Week 3)
4. A2ABroker message system
5. Planner/Executor agent separation
6. Basic human approval hooks

### Phase 3: Isolation (Week 4)
7. WorkspaceManager with git worktrees
8. SecurityManager path restrictions
9. AuditLog integration

### Phase 4: Polish (Week 5)
10. Frontend approval UI
11. Monitoring dashboard
12. Documentation

---

## Integration with Existing JClaw

- **Skills:** Existing `app/skills/` become MCP Tool implementations
- **Jobs:** Play Framework job scheduling powers deterministic cycles
- **Frontend:** Nuxt 3 gets approval UI, agent monitoring views
- **Models:** Current JPA entities extend with context graph

---

## Security Checklist

- [ ] Agent capabilities verified before tool execution
- [ ] Path traversal protection in workspaces
- [ ] A2A message sender verification
- [ ] All agent decisions logged to audit
- [ ] High-risk tasks require human approval
- [ ] Context TTL prevents unbounded growth
- [ ] Tool input/output sanitized
- [ ] Network egress restricted per agent class

---

*Generated for JClaw Agentic AI System Refactor*  
*Source: "How to Build a Good Agentic AI System" Medium Article Application*