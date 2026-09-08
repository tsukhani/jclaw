import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.telemetry.OtelConfig;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Write-time validation of the {@code otel.*} keys (JCLAW-34) — the rejections a POST to /api/config gets back. */
public class OtelConfigTest extends UnitTest {

    @Test
    public void enabledAcceptsBooleansAndBlankOnly() {
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENABLED, "true"));
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENABLED, "FALSE"));
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENABLED, ""));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENABLED, "yes"));
    }

    @Test
    public void endpointMustBeAnAbsoluteHttpUrl() {
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENDPOINT, "http://localhost:4318"));
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENDPOINT, "https://otlp.example.com:443/"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENDPOINT, "localhost:4318"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENDPOINT, "ftp://collector"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENDPOINT, "http://"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_ENDPOINT, "not a url"));
    }

    @Test
    public void protocolIsOneOfTwoWireNames() {
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_PROTOCOL, "grpc"));
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_PROTOCOL, "HTTP/protobuf"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_PROTOCOL, "http/json"));
    }

    @Test
    public void headersAreNameValuePairs() {
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_SECRET_HEADERS, "Authorization=Bearer x,x-tenant=1"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_SECRET_HEADERS, "Authorization"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_SECRET_HEADERS, "=value"));
        assertEquals(Map.of("Authorization", "Bearer x", "x-tenant", "1"),
                OtelConfig.parseHeaders(" Authorization = Bearer x , x-tenant=1 "));
    }

    @Test
    public void samplerRatioRejectsNaNAndOutOfRange() {
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_SAMPLER_RATIO, "0.25"));
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_SAMPLER_RATIO, "1"));
        // Double.parseDouble("NaN") succeeds, which is why the check is explicit.
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_SAMPLER_RATIO, "NaN"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_SAMPLER_RATIO, "Infinity"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_SAMPLER_RATIO, "1.5"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_SAMPLER_RATIO, "-0.1"));
    }

    @Test
    public void metricsIntervalIsAPositiveWholeNumber() {
        assertNull(OtelConfig.rejectionFor(OtelConfig.KEY_METRICS_INTERVAL, "30"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_METRICS_INTERVAL, "0"));
        assertNotNull(OtelConfig.rejectionFor(OtelConfig.KEY_METRICS_INTERVAL, "1.5"));
    }

    @Test
    public void unrelatedKeysAreNotJudged() {
        assertNull(OtelConfig.rejectionFor("otel.something.new", "anything"));
        assertNull(OtelConfig.rejectionFor("ui.theme", "dark"));
    }

    @Test
    public void disabledDefaultsMatchTheDocumentedOnes() {
        var d = OtelConfig.disabled();
        assertTrue(!d.enabled());
        assertEquals("http://localhost:4318", d.endpoint());
        assertEquals(OtelConfig.Protocol.HTTP_PROTOBUF, d.protocol());
        assertEquals(1.0, d.samplerRatio());
        assertEquals(60, d.metricsInterval());
    }
}
