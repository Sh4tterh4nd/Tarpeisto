package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.config.SeedProperties;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Exercises {@link OwnerSeeder} directly with hand-built {@link SeedProperties} (rather than the
 * application's real, blank-by-default {@code TARPEISTO_SEED_OWNER_*} environment variables),
 * so first-run Owner creation and its idempotency can be tested without touching process
 * environment variables.
 */
class OwnerSeederIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Test
    void createsTheOwnerOnFirstRunAndIsIdempotentOnRepeatedRuns() {
        String organizationName = "Owner Seeder Test Org " + UUID.randomUUID();
        String username = "seeded-owner-" + UUID.randomUUID();
        OwnerSeeder seeder = ownerSeeder(organizationName, username, "seeded-owner-password-123");

        seeder.run(null);
        seeder.run(null);

        Organization organization = organizationService.ensureOrganizationExists(organizationName);
        assertThat(userRepository.findByUsernameIgnoreCase(username)).isPresent();
        assertThat(membershipRepository.findAllByOrganizationIdAndRole(organization.getId(), OrganizationRole.OWNER))
                .hasSize(1);
    }

    @Test
    void doesNothingWhenAnOwnerAlreadyExistsForTheOrganization() {
        String organizationName = "Owner Seeder Existing Owner Org " + UUID.randomUUID();
        Organization organization = organizationService.ensureOrganizationExists(organizationName);
        var now = clock.instant();
        User existingOwner = new User(
                UUID.randomUUID(),
                "existing-owner-" + UUID.randomUUID(),
                null,
                "Existing Owner",
                passwordEncoder.encode("existing-password"),
                true,
                now);
        userRepository.save(existingOwner);
        membershipRepository.save(new OrganizationMembership(
                UUID.randomUUID(), organization.getId(), existingOwner.getId(), OrganizationRole.OWNER, now));

        String configuredUsername = "should-not-be-created-" + UUID.randomUUID();
        OwnerSeeder seeder = ownerSeeder(organizationName, configuredUsername, "some-password-123");

        seeder.run(null);

        assertThat(userRepository.findByUsernameIgnoreCase(configuredUsername)).isEmpty();
        assertThat(membershipRepository.findAllByOrganizationIdAndRole(organization.getId(), OrganizationRole.OWNER))
                .hasSize(1);
    }

    @Test
    void doesNothingWhenUsernameOrPasswordIsBlank() {
        String organizationName = "Owner Seeder Blank Config Org " + UUID.randomUUID();
        OwnerSeeder seeder = ownerSeeder(organizationName, "", "");

        seeder.run(null);

        Organization organization = organizationService.ensureOrganizationExists(organizationName);
        assertThat(membershipRepository.findAllByOrganizationIdAndRole(organization.getId(), OrganizationRole.OWNER))
                .isEmpty();
    }

    private OwnerSeeder ownerSeeder(String organizationName, String ownerUsername, String ownerPassword) {
        SeedProperties seedProperties =
                new SeedProperties(organizationName, ownerUsername, ownerPassword, "Seeded Owner", null);
        return new OwnerSeeder(
                organizationService, seedProperties, userRepository, membershipRepository, passwordEncoder, clock);
    }
}
