-- db-scheduler schema for Postgres (used by JClaw when the operator opts
-- into Postgres via %prod.db.url in application.conf).
--
-- Column types and indexes taken verbatim from
-- com.github.kagkarlsson:db-scheduler:16.9.0's reference Postgres schema
-- (db-scheduler/src/test/resources/postgresql_tables.sql). Wrapped with
-- IF NOT EXISTS so DbSchedulerSchemaInitJob can run this on every boot at
-- microsecond cost when the schema already exists.
--
-- The composite (priority DESC, execution_time ASC) index matches the
-- shape of db-scheduler's polling query
-- (SELECT ... WHERE picked = false ORDER BY priority DESC, execution_time ASC
-- LIMIT N), so a multi-key index walk is enough; no table scan even when
-- the table grows large.

CREATE TABLE IF NOT EXISTS scheduled_tasks (
  task_name             TEXT                     NOT NULL,
  task_instance         TEXT                     NOT NULL,
  task_data             BYTEA,
  execution_time        TIMESTAMP WITH TIME ZONE NOT NULL,
  picked                BOOLEAN                  NOT NULL,
  picked_by             TEXT,
  last_success          TIMESTAMP WITH TIME ZONE,
  last_failure          TIMESTAMP WITH TIME ZONE,
  consecutive_failures  INT,
  last_heartbeat        TIMESTAMP WITH TIME ZONE,
  version               BIGINT                   NOT NULL,
  priority              SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX IF NOT EXISTS execution_time_idx
  ON scheduled_tasks (execution_time);

CREATE INDEX IF NOT EXISTS last_heartbeat_idx
  ON scheduled_tasks (last_heartbeat);

CREATE INDEX IF NOT EXISTS priority_execution_time_idx
  ON scheduled_tasks (priority DESC, execution_time ASC);
