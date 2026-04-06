package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentBinding;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@With(AuthCheck.class)
public class ApiBindingsController extends Controller {

    private static final Gson gson = new Gson();

    public static void list() {
        java.util.List<AgentBinding> bindings = AgentBinding.findAll();
        var result = bindings.stream()
                .map(ApiBindingsController::bindingToMap)
                .toList();
        renderJSON(gson.toJson(result));
    }

    public static void create() {
        var body = readJsonBody();
        if (body == null) badRequest();

        var agentId = body.get("agentId").getAsLong();
        var agent = (Agent) Agent.findById(agentId);
        if (agent == null) notFound();

        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = body.get("channelType").getAsString();
        binding.peerId = body.has("peerId") && !body.get("peerId").isJsonNull()
                ? body.get("peerId").getAsString() : null;
        binding.priority = body.has("priority") ? body.get("priority").getAsInt() : 0;
        binding.save();

        renderJSON(gson.toJson(bindingToMap(binding)));
    }

    public static void update(Long id) {
        var binding = (AgentBinding) AgentBinding.findById(id);
        if (binding == null) notFound();

        var body = readJsonBody();
        if (body == null) badRequest();

        if (body.has("agentId")) {
            var agent = (Agent) Agent.findById(body.get("agentId").getAsLong());
            if (agent == null) notFound();
            binding.agent = agent;
        }
        if (body.has("channelType")) binding.channelType = body.get("channelType").getAsString();
        if (body.has("peerId")) binding.peerId = body.get("peerId").isJsonNull() ? null : body.get("peerId").getAsString();
        if (body.has("priority")) binding.priority = body.get("priority").getAsInt();
        binding.save();

        renderJSON(gson.toJson(bindingToMap(binding)));
    }

    public static void delete(Long id) {
        var binding = (AgentBinding) AgentBinding.findById(id);
        if (binding == null) notFound();
        binding.delete();
        renderJSON(gson.toJson(Map.of("status", "ok")));
    }

    private static HashMap<String, Object> bindingToMap(AgentBinding b) {
        var map = new HashMap<String, Object>();
        map.put("id", b.id);
        map.put("agentId", b.agent.id);
        map.put("agentName", b.agent.name);
        map.put("channelType", b.channelType);
        map.put("peerId", b.peerId);
        map.put("priority", b.priority);
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
