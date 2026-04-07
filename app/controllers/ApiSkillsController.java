package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkill;
import models.Skill;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiSkillsController extends Controller {

    private static final Gson gson = new Gson();

    /** GET /api/skills — List all skills in the registry. */
    public static void list() {
        List<Skill> skills = Skill.find("ORDER BY name ASC").fetch();
        var result = skills.stream().map(s -> skillToMap(s)).toList();
        renderJSON(gson.toJson(result));
    }

    /** GET /api/skills/{id} — Get a single skill with full content. */
    public static void get(Long id) {
        Skill skill = Skill.findById(id);
        if (skill == null) notFound();
        var map = skillToMap(skill);
        map.put("content", skill.content);
        renderJSON(gson.toJson(map));
    }

    /** POST /api/skills — Create a new skill. */
    public static void create() {
        var body = readJsonBody();
        if (body == null || !body.has("name")) badRequest();

        var name = body.get("name").getAsString();
        if (Skill.findByName(name) != null) {
            error(409, "A skill named '%s' already exists".formatted(name));
        }

        var skill = new Skill();
        skill.name = name;
        skill.description = body.has("description") ? body.get("description").getAsString() : "";
        skill.content = body.has("content") ? body.get("content").getAsString() : "";
        skill.isGlobal = body.has("isGlobal") && body.get("isGlobal").getAsBoolean();
        skill.save();

        var map = skillToMap(skill);
        map.put("content", skill.content);
        renderJSON(gson.toJson(map));
    }

    /** PUT /api/skills/{id} — Update a skill. */
    public static void update(Long id) {
        Skill skill = Skill.findById(id);
        if (skill == null) notFound();

        var body = readJsonBody();
        if (body == null) badRequest();

        if (body.has("name")) skill.name = body.get("name").getAsString();
        if (body.has("description")) skill.description = body.get("description").getAsString();
        if (body.has("content")) skill.content = body.get("content").getAsString();
        if (body.has("isGlobal")) skill.isGlobal = body.get("isGlobal").getAsBoolean();
        skill.save();

        var map = skillToMap(skill);
        map.put("content", skill.content);
        renderJSON(gson.toJson(map));
    }

    /** DELETE /api/skills/{id} — Delete a skill. */
    public static void delete(Long id) {
        Skill skill = Skill.findById(id);
        if (skill == null) notFound();

        AgentSkill.delete("skill = ?1", skill);
        skill.delete();
        renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
    }

    /** GET /api/agents/{id}/skills — List skills for an agent (global + assigned). */
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var skills = AgentSkill.findSkillsForAgent(agent);
        var assigned = AgentSkill.findByAgent(agent);
        var assignedIds = assigned.stream().map(a -> (Long) a.skill.id).collect(java.util.stream.Collectors.toSet());

        var result = skills.stream().map(s -> {
            var map = skillToMap(s);
            map.put("assigned", s.isGlobal || assignedIds.contains(s.id));
            map.put("removable", !s.isGlobal); // Global skills can't be unassigned
            return map;
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /** POST /api/agents/{id}/skills/{skillId} — Assign a skill to an agent. */
    public static void assignToAgent(Long id, Long skillId) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        Skill skill = Skill.findById(skillId);
        if (skill == null) notFound();

        if (AgentSkill.findByAgentAndSkill(agent, skill) != null) {
            renderJSON(gson.toJson(java.util.Map.of("status", "already_assigned")));
            return;
        }

        var as = new AgentSkill();
        as.agent = agent;
        as.skill = skill;
        as.save();
        renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
    }

    /** DELETE /api/agents/{id}/skills/{skillId} — Unassign a skill from an agent. */
    public static void unassignFromAgent(Long id, Long skillId) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        Skill skill = Skill.findById(skillId);
        if (skill == null) notFound();

        var as = AgentSkill.findByAgentAndSkill(agent, skill);
        if (as != null) as.delete();
        renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
    }

    // --- Helpers ---

    private static HashMap<String, Object> skillToMap(Skill s) {
        var map = new HashMap<String, Object>();
        map.put("id", s.id);
        map.put("name", s.name);
        map.put("description", s.description);
        map.put("isGlobal", s.isGlobal);
        map.put("createdAt", s.createdAt.toString());
        map.put("updatedAt", s.updatedAt.toString());
        return map;
    }

    private static com.google.gson.JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
