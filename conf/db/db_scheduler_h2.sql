-- db-scheduler schema for H2 (used by JClaw Personal Edition in MODE=MYSQL).
--
-- Adapted from com.github.kagkarlsson:db-scheduler:16.9.0's reference
-- Postgres schema; indexes are taken from the Postgres reference because the
-- HSQL/H2 reference in the upstream repo omits them, which would force a
-- table scan on every polling cycle once the table grows beyond a handful
-- of rows.
--
-- All statements are idempotent (CREATE TABLE IF NOT EXISTS,
-- CREATE INDEX IF NOT EXISTS) so DbSchedulerSchemaInitJob can run this on
-- every boot at microsecond cost when the schema already exists.
--
-- Column-type rationale for H2 vs Postgres divergence:
--   * task_name/task_instance/picked_by are VARCHAR(n) here vs TEXT in Postgres.
--     H2 in MySQL mode prefers bounded VARCHAR; our task_instance carries the
--     JClaw Task primary key as a string, so 100 chars is well above need.
--   * task_data is BLOB here vs BYTEA in Postgres. JClaw leaves this column
--     null (taskId is passed via task_instance), but the column has to exist
--     for db-scheduler's binder.
--   * TIMESTAMP WITH TIME ZONE is honored by H2 2.3+ in MYSQL mode (verified
--     against H2 2.3.232 the play1 fork bundles).

CREATE TABLE IF NOT EXISTS scheduled_tasks (
  task_name             VARCHAR(100)             NOT NULL,
  task_instance         VARCHAR(100)             NOT NULL,
  task_data             BLOB,
  execution_time        TIMESTAMP WITH TIME ZONE NOT NULL,
  picked                BOOLEAN                  NOT NULL,
  picked_by             VARCHAR(50),
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
