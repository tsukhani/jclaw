package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.AgentBinding;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import utils.ApiResponses;

import java.util.List;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiBindingsController extends Controller {

    private static final Gson gson = INSTANCE;

    // JSON body keys for AgentBinding fields.
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_CHANNEL_TYPE = "channelType";
    private static final String KEY_PEER_ID = "peerId";
    private static final String KEY_PRIORITY = "priority";

    private record BindingView(Long id, Long agentId, String agentName,
                               String channelType, String peerId, int priority) {
        static BindingView of(AgentBinding b) {
            return new BindingView(b.id, b.agent.id, b.agent.name,
                    b.channelType, b.peerId, b.priority);
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = BindingView.class))))
    public static void list() {
        List<AgentBinding> bindings = JPA.em()
                .createQuery("SELECT b FROM AgentBinding b JOIN FETCH b.agent", AgentBinding.class)
                .getResultList();
        var result = bindings.stream()
                .map(BindingView::of)
                .toList();
        renderJSON(gson.toJson(result));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AgentBinding.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw new AssertionError("unreachable: badRequest() throws");
        }

        var agentId = body.get(KEY_AGENT_ID).getAsLong();
        var agent = (Agent) Agent.findById(agentId);
        if (agent == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }

        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = body.get(KEY_CHANNEL_TYPE).getAsString();
        binding.peerId = body.has(KEY_PEER_ID) && !body.get(KEY_PEER_ID).isJsonNull()
                ? body.get(KEY_PEER_ID).getAsString() : null;
        binding.priority = body.has(KEY_PRIORITY) ? body.get(KEY_PRIORITY).getAsInt() : 0;
        binding.save();

        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AgentBinding.class)))
    public static void update(Long id) {
        var binding = (AgentBinding) AgentBinding.findById(id);
        if (binding == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }

        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw new AssertionError("unreachable: badRequest() throws");
        }

        if (body.has(KEY_AGENT_ID)) {
            var agent = (Agent) Agent.findById(body.get(KEY_AGENT_ID).getAsLong());
            if (agent == null) {
                notFound();
                throw new AssertionError("unreachable: notFound() throws");
            }
            binding.agent = agent;
        }
        if (body.has(KEY_CHANNEL_TYPE)) binding.channelType = body.get(KEY_CHANNEL_TYPE).getAsString();
        if (body.has(KEY_PEER_ID)) binding.peerId = body.get(KEY_PEER_ID).isJsonNull() ? null : body.get(KEY_PEER_ID).getAsString();
        if (body.has(KEY_PRIORITY)) binding.priority = body.get(KEY_PRIORITY).getAsInt();
        binding.save();

        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @SuppressWarnings("java:S2259")
    public static void delete(Long id) {
        var binding = (AgentBinding) AgentBinding.findById(id);
        if (binding == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        binding.delete();
        ApiResponses.ok();
    }

}
