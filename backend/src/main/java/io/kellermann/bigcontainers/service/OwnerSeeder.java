package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.config.SeedProperties;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-run Owner creation (implementation plan section 3.2), building on {@link
 * OrganizationSeeder}'s idempotent default-organization seeding.
 *
 * <p>Bootstrap credentials are supplied through application configuration, environment variables,
 * or mounted secret files as {@code BIGCONTAINERS_SEED_OWNER_USERNAME}/{@code
 * BIGCONTAINERS_SEED_OWNER_PASSWORD}. Unlike {@link OrganizationSeeder}, this deliberately does
 * <strong>not</strong> generate and log a random
 * password: passwords are never logged, per the mandatory security constraints, and this seeder
 * has no other private channel to hand a generated credential to the operator. If the variables
 * are absent, no Owner is created and the application starts with no permanent account - the
 * operator must set them (and restart, or otherwise re-trigger this runner) to bootstrap access.
 *
 * <p>Idempotent and safe on every startup: it does nothing once any Owner exists for the default
 * organization, and it never overwrites an existing {@code app_user} row.
 */
@Component
@Order(1)
public class OwnerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerSeeder.class);

    private final OrganizationService organizationService;
    private final SeedProperties seedProperties;
    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public OwnerSeeder(
            OrganizationService organizationService,
            SeedProperties seedProperties,
            UserRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.organizationService = organizationService;
        this.seedProperties = seedProperties;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = seedProperties.ownerUsername();
        String password = seedProperties.ownerPassword();
        if (isBlank(username) || isBlank(password)) {
            log.info("BIGCONTAINERS_SEED_OWNER_USERNAME/BIGCONTAINERS_SEED_OWNER_PASSWORD are not set; "
                    + "skipping first-run Owner creation. Set both and restart to bootstrap the first "
                    + "Owner account.");
            return;
        }

        Organization organization =
                organizationService.ensureOrganizationExists(seedProperties.defaultOrganizationName());
        if (membershipRepository.existsByOrganizationIdAndRole(organization.getId(), OrganizationRole.OWNER)) {
            log.info("An Owner already exists for the default organization; skipping first-run Owner creation.");
            return;
        }
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            log.warn(
                    "BIGCONTAINERS_SEED_OWNER_USERNAME '{}' already exists as a user but is not an Owner of "
                            + "the default organization; skipping automatic first-run Owner creation rather than "
                            + "modifying an existing account.",
                    username);
            return;
        }

        Instant now = clock.instant();
        User owner = new User(
                UUID.randomUUID(),
                username,
                blankToNull(seedProperties.ownerEmail()),
                blankToNull(seedProperties.ownerDisplayName()) == null ? "Owner" : seedProperties.ownerDisplayName(),
                passwordEncoder.encode(password),
                true,
                now);
        userRepository.save(owner);
        membershipRepository.save(new OrganizationMembership(
                UUID.randomUUID(), organization.getId(), owner.getId(), OrganizationRole.OWNER, now));
        log.info("First-run Owner created: username='{}' id={}", username, owner.getId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
