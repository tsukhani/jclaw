package controllers;

import com.google.gson.Gson;
import models.EventLog;
import play.mvc.Controller;
import play.mvc.With;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiLogsController extends Controller {

    private static final Gson gson = new Gson();

    public static void list(String category, String level, String agentId, String channel,
                            String since, String until, String search,
                            Integer limit, Integer offset) {
        var queryParts = new ArrayList<String>();
        var params = new ArrayList<Object>();
        int paramIdx = 1;

        if (category != null && !category.isBlank()) {
            queryParts.add("category = ?" + paramIdx++);
            params.add(category);
        }
        if (level != null && !level.isBlank()) {
            queryParts.add("level = ?" + paramIdx++);
            params.add(level);
        }
        if (agentId != null && !agentId.isBlank()) {
            queryParts.add("agentId = ?" + paramIdx++);
            params.add(agentId);
        }
        if (channel != null && !channel.isBlank()) {
            queryParts.add("channel = ?" + paramIdx++);
            params.add(channel);
        }
        if (since != null && !since.isBlank()) {
            queryParts.add("timestamp >= ?" + paramIdx++);
            params.add(Instant.parse(since));
        }
        if (until != null && !until.isBlank()) {
            queryParts.add("timestamp <= ?" + paramIdx++);
            params.add(Instant.parse(until));
        }
        if (search != null && !search.isBlank()) {
            queryParts.add("LOWER(message) LIKE ?" + paramIdx++);
            params.add("%" + search.toLowerCase() + "%");
        }

        var where = queryParts.isEmpty() ? "" : String.join(" AND ", queryParts);
        var query = where.isEmpty() ? "ORDER BY timestamp DESC" : where + " ORDER BY timestamp DESC";

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var jpql = EventLog.find(query, params.toArray());
        List<EventLog> events = jpql.from(effectiveOffset).fetch(effectiveLimit);

        var result = new HashMap<String, Object>();
        result.put("events", events.stream().map(e -> {
            var map = new HashMap<String, Object>();
            map.put("id", e.id);
            map.put("timestamp", e.timestamp.toString());
            map.put("level", e.level);
            map.put("category", e.category);
            map.put("agentId", e.agentId);
            map.put("channel", e.channel);
            map.put("message", e.message);
            map.put("details", e.details);
            return map;
        }).toList());
        result.put("limit", effectiveLimit);
        result.put("offset", effectiveOffset);

        renderJSON(gson.toJson(result));
    }
}
