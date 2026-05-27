package agents;

/**
 * A single action exposed by a {@link ToolRegistry.Tool}. Used by the
 * {@code GET /api/tools/meta} endpoint so the admin UI can enumerate and
 * describe each action without re-deriving them from the JSON parameter
 * schema. The backend is the authoritative source of this metadata —
 * JCLAW-72 removed the duplicated list previously hardcoded on the frontend.
 *
 * @param name        human-facing action name (e.g. {@code "readFile"},
 *                    {@code "scheduleRecurringTask"})
 * @param description short blurb rendered under the tool card on
 *                    {@code /tools} and anywhere else a user browses
 *                    capabilities
 */
public record ToolAction(String name, String description) {}
