package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.AgentBinding;

import static utils.GsonHolder.INSTANCE;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;

import java.util.Map;

@With(AuthCheck.class)
public class ApiBindingsController extends Controller {

    private static final Gson gson = INSTANCE;

    private record BindingView(Long id, Long agentId, String agentName,
                               String channelType, String peerId, int priority) {
        static BindingView of(AgentBinding b) {
            return new BindingView(b.id, b.agent.id, b.agent.name,
                    b.channelType, b.peerId, b.priority);
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgentBinding.class))))
    public static void list() {
        java.util.List<AgentBinding> bindings = JPA.em()
                .createQuery("SELECT b FROM AgentBinding b JOIN FETCH b.agent", AgentBinding.class)
                .getResultList();
        var result = bindings.stream()
                .map(BindingView::of)
                .toList();
        renderJSON(gson.toJson(result));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentBinding.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
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

        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentBinding.class)))
    public static void update(Long id) {
        var binding = (AgentBinding) AgentBinding.findById(id);
        if (binding == null) notFound();

        var body = JsonBodyReader.readJsonBody();
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

        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    public static void delete(Long id) {
        var binding = (AgentBinding) AgentBinding.findById(id);
        if (binding == null) notFound();
        binding.delete();
        renderJSON(gson.toJson(Map.of("status", "ok")));
    }

}
