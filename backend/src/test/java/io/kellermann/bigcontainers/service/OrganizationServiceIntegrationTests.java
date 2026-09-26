package io.kellermann.bigcontainers.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.config.SeedProperties;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrganizationServiceIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private SeedProperties seedProperties;

    @Test
    void defaultOrganizationIsSeededOnStartup() {
        // OrganizationSeeder (an ApplicationRunner) already ran before this test executes.
        assertThat(organizationRepository.findByNameIgnoreCase(seedProperties.defaultOrganizationName()))
                .isPresent();
    }

    @Test
    void ensureOrganizationExistsIsIdempotent() {
        String name = "Integration Test Organization " + UUID.randomUUID();

        Organization first = organizationService.ensureOrganizationExists(name);
        Organization second = organizationService.ensureOrganizationExists(name);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(organizationRepository.findAll())
                .filteredOn(organization -> organization.getName().equals(name))
                .hasSize(1);
    }

    @Test
    void findByIdReturnsEmptyForAnUnknownOrganization() {
        assertThat(organizationService.findById(UUID.randomUUID())).isEmpty();
    }
}
