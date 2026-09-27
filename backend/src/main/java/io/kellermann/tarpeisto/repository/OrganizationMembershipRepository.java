package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    Optional<OrganizationMembership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /**
     * Resolves the server-side active-organization context for a locally authenticated user
     * (see {@code io.kellermann.tarpeisto.security.TarpeistoUserDetailsService}): the
     * earliest membership row becomes the user's active organization. Phase 1 does not offer
     * organization switching, so a user is expected to have exactly one membership in practice.
     */
    Optional<OrganizationMembership> findFirstByUserIdOrderByCreatedAtAsc(UUID userId);

    List<OrganizationMembership> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    List<OrganizationMembership> findAllByOrganizationIdAndRole(UUID organizationId, OrganizationRole role);

    boolean existsByOrganizationIdAndRole(UUID organizationId, OrganizationRole role);

    boolean existsByRole(OrganizationRole role);
}
