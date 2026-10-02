package memory;

import models.MemoryVerification;
import org.jspecify.annotations.Nullable;
import services.ConfigService;

import java.util.List;

/**
 * The two tiers computed from a memory's stored provenance (JCLAW-1318). Neither is stored:
 * both are a function of the actor, the verification events and the corroboration count.
 */
public final class MemoryTrust {

    private MemoryTrust() {}

    public enum TrustTier { HUMAN_REVIEWED, MACHINE_CONFIRMED, UNVERIFIED }

    public enum GraphTier { FIRM, TENTATIVE }

    public static final String KEY_FIRM_THRESHOLD = "memory.graph.firmCorroborationThreshold";
    public static final int DEFAULT_FIRM_THRESHOLD = 2;

    public static TrustTier trustTier(List<MemoryVerification> verifications) {
        if (hasHumanVerification(verifications)) return TrustTier.HUMAN_REVIEWED;
        return verifications.isEmpty() ? TrustTier.UNVERIFIED : TrustTier.MACHINE_CONFIRMED;
    }

    public static GraphTier graphTier(@Nullable String actor, List<MemoryVerification> verifications,
                                      int corroborationCount, int threshold) {
        boolean firm = isHuman(actor)
                || hasHumanVerification(verifications)
                || corroborationCount >= threshold;
        return firm ? GraphTier.FIRM : GraphTier.TENTATIVE;
    }

    public static int firmThreshold() {
        return parseFirmThreshold(ConfigService.get(KEY_FIRM_THRESHOLD));
    }

    /** The configured threshold, or the default when absent, non-numeric or below 1. */
    public static int parseFirmThreshold(@Nullable String raw) {
        if (raw == null) return DEFAULT_FIRM_THRESHOLD;
        try {
            int t = Integer.parseInt(raw.strip());
            return t < 1 ? DEFAULT_FIRM_THRESHOLD : t;
        } catch (NumberFormatException _) {
            return DEFAULT_FIRM_THRESHOLD;
        }
    }

    private static boolean hasHumanVerification(List<MemoryVerification> verifications) {
        return verifications.stream().anyMatch(v -> isHuman(v.actor));
    }

    private static boolean isHuman(@Nullable String actor) {
        return actor != null && actor.startsWith(MemoryProvenance.HUMAN_ACTOR_PREFIX);
    }
}
