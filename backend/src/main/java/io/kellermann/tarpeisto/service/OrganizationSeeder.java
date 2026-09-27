package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.config.SeedProperties;
import io.kellermann.tarpeisto.model.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the default organization on first run (Phase 0 foundation deliverable). Idempotent and
 * driven entirely by {@link SeedProperties}. {@code @Order(0)} guarantees this runs before {@link
 * OwnerSeeder}, which needs the organization to exist (it also idempotently ensures the
 * organization itself, defensively, regardless of runner order).
 */
@Component
@Order(0)
public class OrganizationSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrganizationSeeder.class);

    private final OrganizationService organizationService;
    private final SeedProperties seedProperties;

    public OrganizationSeeder(OrganizationService organizationService, SeedProperties seedProperties) {
        this.organizationService = organizationService;
        this.seedProperties = seedProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Organization organization =
                organizationService.ensureOrganizationExists(seedProperties.defaultOrganizationName());
        log.info("Default organization ready: name='{}' id={}", organization.getName(), organization.getId());
    }
}
