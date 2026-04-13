package controllers;

import com.google.gson.Gson;
import models.EventLog;
import play.mvc.Controller;

import static utils.GsonHolder.INSTANCE;
import play.mvc.With;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiLogsController extends Controller {

    private static final Gson gson = INSTANCE;

    public static void list(String category, String level, String agentId, String channel,
                            String since, String until, String search,
                            Integer limit, Integer offset) {
        var filter = new JpqlFilter()
                .eq("category", category)
                .eq("level", level)
                .eq("agentId", agentId)
                .eq("channel", channel)
                .gte("timestamp", since != null && !since.isBlank() ? Instant.parse(since) : null)
                .lte("timestamp", until != null && !until.isBlank() ? Instant.parse(until) : null)
                .like("LOWER(message)", search != null && !search.isBlank() ? "%" + search.toLowerCase() + "%" : null);

        var where = filter.toWhereClause();
        var query = where.isEmpty() ? "ORDER BY timestamp DESC" : where + " ORDER BY timestamp DESC";

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var jpql = EventLog.find(query, filter.params());
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
