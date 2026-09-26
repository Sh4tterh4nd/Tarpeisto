package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read and idempotent-creation operations for the {@link Organization} tenancy root. */
@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizationRepository, Clock clock) {
        this.organizationRepository = organizationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Organization> findById(UUID organizationId) {
        return organizationRepository.findById(organizationId);
    }

    /**
     * Returns the organization named {@code organizationName}, creating it if it does not yet
     * exist. Safe to call repeatedly (for example on every application startup): a second call
     * with the same name returns the already-seeded organization rather than creating a
     * duplicate.
     */
    @Transactional
    public Organization ensureOrganizationExists(String organizationName) {
        return organizationRepository
                .findByNameIgnoreCase(organizationName)
                .orElseGet(() -> organizationRepository.save(
                        new Organization(UUID.randomUUID(), organizationName, clock.instant())));
    }
}
