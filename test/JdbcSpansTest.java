import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.test.UnitTest;
import services.telemetry.OtelRuntime;
import services.telemetry.TelemetryDataSource;
import utils.LatencyTrace;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC spans through {@code db.factory} (JCLAW-1164): the pool draws from the raw driver
 * datasource while export is off and from the instrumented one while it is on, and a
 * statement run under a turn is a CLIENT span beneath it.
 */
public class JdbcSpansTest extends UnitTest {

    private static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");
    private static final AttributeKey<String> DB_QUERY_TEXT = AttributeKey.stringKey("db.query.text");

    @BeforeEach
    public void lock() {
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    public void unlock() {
        TelemetryTestSync.release();
    }

    private static TelemetryDataSource inner() {
        var pool = (HikariDataSource) DB.getDataSource();
        return (TelemetryDataSource) pool.getDataSource();
    }

    private static void selectOnce() {
        try (var connection = DB.getDataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT CURRENT_DATE")) {
            assertTrue(rows.next());
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void thePoolDrawsFromTheRawDatasourceWhileExportIsOff() {
        assertFalse(inner().instrumented());
    }

    @Test
    public void aStatementUnderATurnIsAClientSpanBeneathIt() {
        var spans = OtelRuntime.captureForTest(() -> {
            assertTrue(inner().instrumented(), "export on switches the pool to instrumented connections");
            var trace = LatencyTrace.forTurn("web", null);
            try (var _ = LatencyTrace.bind(trace)) {
                selectOnce();
            }
            trace.end();
        });
        assertFalse(inner().instrumented(), "export off again switches it back");

        var turn = spans.stream().filter(s -> s.getName().equals("turn")).findFirst().orElseThrow();
        var queries = spans.stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT
                        && "SELECT CURRENT_DATE".equals(s.getAttributes().get(DB_QUERY_TEXT))
                        && turn.getSpanContext().getSpanId().equals(s.getParentSpanContext().getSpanId()))
                .toList();
        assertEquals(1, queries.size(), () -> "query spans under the turn in " + describe(spans));
        assertEquals("h2database", queries.getFirst().getAttributes().get(DB_SYSTEM_NAME), "the semconv well-known name for H2");
    }

    private static String describe(List<SpanData> spans) {
        var sb = new StringBuilder();
        for (var s : spans) {
            sb.append("\n  ").append(s.getName()).append(" kind=").append(s.getKind())
              .append(" id=").append(s.getSpanContext().getSpanId())
              .append(" parent=").append(s.getParentSpanContext().getSpanId())
              .append(" attrs=").append(s.getAttributes().asMap());
        }
        return sb.toString();
    }
}
