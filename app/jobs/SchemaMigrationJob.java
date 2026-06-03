package jobs;

import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;

/**
 * Applies columns and tables introduced in this release that Hibernate's
 * {@code jpa.ddl=update} wouldn't patch onto a hot-reloaded dev server (DDL
 * only runs at SessionFactory init, not on class reload). Uses the idempotent
 * {@code IF NOT EXISTS} form so a server that's already in the right state does
 * nothing.
 *
 * <p>Runs FIRST among all {@code @OnApplicationStart} jobs ({@code priority =
 * -200}; play1 1.13.27+ runs startup jobs in ascending priority order). The
 * schema additions must land before any startup job that reads the new columns
 * — notably {@link TelegramStreamingRecoveryJob}, which scans
 * {@code conversation.active_stream_message_id}. Splitting this out of
 * {@link DefaultConfigJob} (the next job at {@code -100}, which seeds Config
 * rows and the default agent) keeps raw-DDL migration and active-record seeding
 * as separate responsibilities, and the strict priority gap makes the ordering
 * deterministic rather than relying on hand-placement of a call at the top of
 * another job's {@code doJob}.
 *
 * <p>{@code @NoTransaction} because DDL should not run inside a JPA
 * transaction; a short-lived raw connection from {@link play.db.DB} carries the
 * statements, separate from anything Hibernate manages.
 */
@OnApplicationStart(priority = -200)
@NoTransaction
public class SchemaMigrationJob extends Job<Void> {

    @Override
    public void doJob() {
        applySchemaAdditions();
    }

    static void applySchemaAdditions() {
        try (var conn = play.db.DB.getDataSource("default").getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE message ADD COLUMN IF NOT EXISTS reasoning TEXT");
            // JCLAW-95: Telegram streaming checkpoint. Non-null rows indicate
            // a placeholder message left dangling by a prior JVM crash — the
            // recovery job edits them on startup.
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS active_stream_message_id INT");
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS active_stream_chat_id VARCHAR(255)");
            // JCLAW-26: /reset watermark. Messages older than this timestamp
            // are excluded from the LLM context window. Null = no reset has
            // occurred, full history is eligible.
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS context_since TIMESTAMP");
            // JCLAW-38: session-compaction watermark. Messages older than
            // this timestamp have been summarized into a session_compaction
            // row; loadRecentMessages skips them on subsequent turns. Null
            // = conversation has never been compacted. Independent of
            // context_since so /reset and compaction compose cleanly.
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS compaction_since TIMESTAMP");
            // JCLAW-38: session_compaction history table. The current
            // summary for a conversation is the most recent row by
            // compacted_at. Older rows are retained as an audit trail of
            // how the conversation was progressively summarized.
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS session_compaction (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        conversation_id BIGINT NOT NULL,
                        turn_count INT NOT NULL,
                        summary_tokens INT NOT NULL,
                        model VARCHAR(255) NOT NULL,
                        summary CLOB NOT NULL,
                        compacted_at TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        CONSTRAINT fk_session_compaction_conversation
                            FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
                    )""");
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_session_compaction_conversation
                        ON session_compaction(conversation_id)""");
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_session_compaction_conv_compacted_at
                        ON session_compaction(conversation_id, compacted_at)""");
        } catch (Exception e) {
            play.Logger.warn("Schema migration: %s", e.getMessage());
        }
    }
}
