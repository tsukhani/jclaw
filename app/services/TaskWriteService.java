package services;

import com.google.gson.JsonObject;
import models.Agent;
import models.Task;
import play.db.jpa.JPA;

import java.time.Instant;

/**
 * JCLAW-676: pure create/update entity-mapping and the FK-cascade delete for
 * the Task write path, extracted from {@code ApiTasksController} so the write
 * actions ({@code create}, {@code update}, {@code delete}) stay thin. Only pure
 * JSON→entity mapping and the cascade DELETE move here — every Result-throwing
 * validation guard (required-field 400s, duplicate-name 409, the invalid-schedule
 * and invalid-timezone 400s) stays in the controller so the halt boundary and
 * its ordering are preserved. Relies on the caller's ambient JPA transaction.
 */
public final class TaskWriteService {

    private TaskWriteService() {}

    // JSON body keys the write mapping reads. Guards in the controller keep
    // their own copies of the subset they validate.
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_DELIVERY = "delivery";
    private static final String KEY_PAYLOAD_TYPE = "payloadType";
    private static final String KEY_MODEL_PROVIDER = "modelProvider";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_ENABLED_TOOL_NAMES = "enabledToolNames";
    private static final String KEY_WORKDIR = "workdir";
    private static final String KEY_PRE_CHECK = "preCheck";
    private static final String KEY_SCRIPT = "script";
    private static final String KEY_NO_AGENT = "noAgent";
    private static final String KEY_AUTO_DELETE = "autoDeleteOnComplete";
    private static final String KEY_CONTEXT_FROM_TASK_IDS = "contextFromTaskIds";
    private static final String KEY_REPEAT_LIMIT = "repeatLimit";
    private static final String KEY_PAUSED = "paused";
    private static final String KEY_TIMEZONE = "timezone";

    /**
     * Map a validated create body onto a fresh {@link Task} and persist it. The
     * caller has already validated agent/name/schedule/delivery/timezone and
     * rejected duplicate recurring names, so this is pure field-mapping + save.
     */
    public static Task persistNewTask(JsonObject body, Agent agent, String name,
                                      ScheduleShorthandParser.ScheduleSpec spec) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = readOptionalString(body, KEY_DESCRIPTION);
        if (t.description == null) t.description = "";

        t.type = spec.type();
        // Status depends on type: CRON / INTERVAL start ACTIVE (ongoing
        // recurrence), IMMEDIATE / SCHEDULED start PENDING (waiting to fire).
        // The Task entity's default is PENDING; override here for recurring.
        t.status = Task.initialStatusFor(spec.type());
        t.scheduledAt = spec.scheduledAt();
        t.cronExpression = spec.cronExpression();
        t.intervalSeconds = spec.intervalSeconds();
        t.scheduleDisplay = spec.scheduleDisplay();
        // nextRunAt is no longer authoritative under db-scheduler (see
        // JCLAW-21), but keep it populated for the Tasks-page render
        // until the column is dropped.
        t.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();

        // Plumbing-ahead fields (consumed by JCLAW-295/296/297/298).
        t.delivery = readOptionalString(body, KEY_DELIVERY);
        t.payloadType = readOptionalString(body, KEY_PAYLOAD_TYPE);
        t.modelProvider = readOptionalString(body, KEY_MODEL_PROVIDER);
        t.modelId = readOptionalString(body, KEY_MODEL_ID);
        t.enabledToolNames = readOptionalString(body, KEY_ENABLED_TOOL_NAMES);
        t.workdir = readOptionalString(body, KEY_WORKDIR);
        t.preCheck = readOptionalString(body, KEY_PRE_CHECK);
        t.script = readOptionalString(body, KEY_SCRIPT);
        if (body.has(KEY_NO_AGENT) && !body.get(KEY_NO_AGENT).isJsonNull()) {
            t.noAgent = body.get(KEY_NO_AGENT).getAsBoolean();
        }
        // Reminders default to auto-delete-after-fire (a fired one-off reminder
        // has served its purpose); regular tasks keep their audit history. An
        // explicit body value overrides.
        if (body.has(KEY_AUTO_DELETE) && !body.get(KEY_AUTO_DELETE).isJsonNull()) {
            t.autoDeleteOnComplete = body.get(KEY_AUTO_DELETE).getAsBoolean();
        } else {
            t.autoDeleteOnComplete = "reminder".equalsIgnoreCase(t.payloadType);
        }
        t.contextFromTaskIds = readOptionalString(body, KEY_CONTEXT_FROM_TASK_IDS);
        if (body.has(KEY_REPEAT_LIMIT) && !body.get(KEY_REPEAT_LIMIT).isJsonNull()) {
            t.repeatLimit = body.get(KEY_REPEAT_LIMIT).getAsInt();
        }
        // JCLAW-261: optional per-task IANA timezone (already validated by the
        // controller's invalid-timezone guard). Persist the trimmed value for
        // all types since the column is cheap and the UI may surface it.
        var tzRaw = readOptionalString(body, KEY_TIMEZONE);
        t.timezone = tzRaw != null ? tzRaw.trim() : null;

        t.save();
        return t;
    }

    public static String readOptionalString(JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Apply every non-schedule, non-name patchable field present in the body.
     * Pure mapping — the schedule and name updates (which throw Results on
     * invalid input) stay in the controller.
     *
     * @return true if any field was touched.
     */
    public static boolean applyOptionalFieldUpdates(Task task, JsonObject body) {
        // Split per-field-kind so each helper stays well under the cognitive-
        // complexity bar; the boolean OR reduction below preserves "any field
        // touched" semantics without short-circuiting.
        boolean changed = applyOptionalStringFields(task, body);
        changed |= applyOptionalBooleanFields(task, body);
        changed |= applyOptionalIntegerFields(task, body);
        return changed;
    }

    /**
     * Optional-string fields: present-and-null clears, present-and-blank
     * also clears (readOptionalString collapses both to null). Description
     * uniquely coerces null → "" instead of leaving it null.
     */
    private static boolean applyOptionalStringFields(Task task, JsonObject body) {
        boolean changed = false;
        if (body.has(KEY_DESCRIPTION)) {
            var v = readOptionalString(body, KEY_DESCRIPTION);
            task.description = v != null ? v : "";
            changed = true;
        }
        if (body.has(KEY_DELIVERY))            { task.delivery          = readOptionalString(body, KEY_DELIVERY);            changed = true; }
        if (body.has(KEY_PAYLOAD_TYPE))        { task.payloadType       = readOptionalString(body, KEY_PAYLOAD_TYPE);        changed = true; }
        if (body.has(KEY_MODEL_PROVIDER))      { task.modelProvider     = readOptionalString(body, KEY_MODEL_PROVIDER);      changed = true; }
        if (body.has(KEY_MODEL_ID))            { task.modelId           = readOptionalString(body, KEY_MODEL_ID);            changed = true; }
        if (body.has(KEY_ENABLED_TOOL_NAMES))  { task.enabledToolNames  = readOptionalString(body, KEY_ENABLED_TOOL_NAMES);  changed = true; }
        if (body.has(KEY_WORKDIR))             { task.workdir           = readOptionalString(body, KEY_WORKDIR);             changed = true; }
        if (body.has(KEY_PRE_CHECK))           { task.preCheck          = readOptionalString(body, KEY_PRE_CHECK);           changed = true; }
        if (body.has(KEY_SCRIPT))              { task.script            = readOptionalString(body, KEY_SCRIPT);              changed = true; }
        if (body.has(KEY_CONTEXT_FROM_TASK_IDS)){ task.contextFromTaskIds= readOptionalString(body, KEY_CONTEXT_FROM_TASK_IDS);changed = true; }
        return changed;
    }

    /**
     * Boolean fields. Explicit null is rejected (no meaningful semantic) —
     * callers should omit instead.
     */
    private static boolean applyOptionalBooleanFields(Task task, JsonObject body) {
        boolean changed = false;
        if (body.has(KEY_PAUSED) && !body.get(KEY_PAUSED).isJsonNull()) {
            task.paused = body.get(KEY_PAUSED).getAsBoolean();
            changed = true;
        }
        if (body.has(KEY_NO_AGENT) && !body.get(KEY_NO_AGENT).isJsonNull()) {
            task.noAgent = body.get(KEY_NO_AGENT).getAsBoolean();
            changed = true;
        }
        if (body.has(KEY_AUTO_DELETE) && !body.get(KEY_AUTO_DELETE).isJsonNull()) {
            task.autoDeleteOnComplete = body.get(KEY_AUTO_DELETE).getAsBoolean();
            changed = true;
        }
        return changed;
    }

    /** Integer fields. Explicit null clears (sets back to unlimited). */
    private static boolean applyOptionalIntegerFields(Task task, JsonObject body) {
        if (!body.has(KEY_REPEAT_LIMIT)) return false;
        var el = body.get(KEY_REPEAT_LIMIT);
        task.repeatLimit = el.isJsonNull() ? null : el.getAsInt();
        return true;
    }

    /**
     * Hard-delete a task's run history and the task row.
     *
     * <p>FK chain (NOT NULL on both sides): {@code TaskRunMessage → TaskRun →
     * Task}. JPQL bulk deletes cover the descendants in two queries regardless
     * of run-count; the task's user-visible notifications are cleared in
     * lockstep (reminder tasks emit one Notification per fire, and the
     * Reminders surface must not re-show a deleted task's row); then the
     * scheduler row is dropped (idempotent), then the Task row itself.
     */
    public static void deleteWithHistory(Task task) {
        var taskId = task.id;
        final String taskIdParam = "taskId";

        var em = JPA.em();
        em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.task.id = :taskId")
                .setParameter(taskIdParam, taskId).executeUpdate();
        em.createQuery("DELETE FROM TaskRun r WHERE r.task.id = :taskId")
                .setParameter(taskIdParam, taskId).executeUpdate();
        // Cascade-delete user-visible notifications that originated from this
        // task. Reminder tasks (payloadType="reminder") emit one Notification
        // per fire; when the operator deletes the task the toast/Reminders
        // surface should clear in lockstep so the row doesn't reappear after
        // the next poll. Safe for non-reminder tasks too — they don't write
        // Notifications, so this is a no-op.
        em.createQuery("DELETE FROM Notification n WHERE n.sourceTaskId = :taskId")
                .setParameter(taskIdParam, taskId).executeUpdate();
        em.flush();

        // Idempotent — harmless if the task is already in a terminal
        // state and its scheduler row was already removed by cancel.
        TaskSchedulingService.cancel(taskId);

        task.delete();
    }
}
