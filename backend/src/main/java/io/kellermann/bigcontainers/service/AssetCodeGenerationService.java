package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.AssetCode;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Generates public asset codes from a cryptographically secure random source and retries safely
 * on a uniqueness collision (specification section 9.3, ADR-0002 "Generation and uniqueness",
 * implementation plan section 4.2).
 *
 * <p>Deliberately has no dependency on any repository: Phase 2a's {@code physical_asset} table
 * does not exist yet (it is Phase 2b). Callers that DO have a real existence check (a repository
 * query once {@code physical_asset} exists) pass it in as an {@link AssetCodeUniquenessChecker},
 * which keeps this a clean, injectable domain service rather than something welded to an entity
 * that is not there yet - exactly the seam the Phase 2a task description asks for.
 */
@Service
public class AssetCodeGenerationService {

    /**
     * Bounded attempt count before giving up (ADR-0002: "A unique-constraint violation causes
     * regeneration and retry with a bounded attempt count"). With five random symbols over a
     * 32-symbol alphabet there are 32^5 (33,554,432) possible data portions per organization, so
     * exhausting this many attempts in practice means the checker itself is misbehaving (for
     * example always reporting a collision), not genuine collision pressure.
     */
    static final int MAX_ATTEMPTS = 25;

    private final SecureRandom secureRandom;

    public AssetCodeGenerationService(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * Generates a new, unique {@link AssetCode} for {@code organizationId}, drawing five
     * cryptographically random data symbols so all 32 alphabet symbols are equally likely
     * (ADR-0002 "Generation and uniqueness") and retrying on a reported collision.
     *
     * @throws IllegalStateException if {@link #MAX_ATTEMPTS} candidates all collide
     */
    public AssetCode generate(UUID organizationId, AssetCodeUniquenessChecker uniquenessChecker) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            AssetCode candidate = generateCandidate();
            if (!uniquenessChecker.exists(organizationId, candidate.value())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique public asset code for organization "
                + organizationId + " after " + MAX_ATTEMPTS + " attempts.");
    }

    private AssetCode generateCandidate() {
        StringBuilder data = new StringBuilder(AssetCode.DATA_LENGTH);
        for (int i = 0; i < AssetCode.DATA_LENGTH; i++) {
            data.append(AssetCode.ALPHABET.charAt(secureRandom.nextInt(AssetCode.ALPHABET.length())));
        }
        return AssetCode.format(data.toString());
    }
}
