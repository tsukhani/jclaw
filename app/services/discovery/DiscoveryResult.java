package services.discovery;

import java.util.List;
import java.util.Map;

/** Result of a model discovery call: the normalized catalog, or a status code and message. */
public sealed interface DiscoveryResult {
    record Ok(List<Map<String, Object>> models) implements DiscoveryResult {}
    record Error(int statusCode, String message) implements DiscoveryResult {}
}
