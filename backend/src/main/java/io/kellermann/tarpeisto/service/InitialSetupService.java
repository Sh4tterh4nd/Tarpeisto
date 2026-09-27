package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.SetupAlreadyCompletedException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.InitialSetupRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the installation's organization and first local Owner from the one-time setup page. */
@Service
public class InitialSetupService {

    private final InitialSetupRepository initialSetupRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public InitialSetupService(
            InitialSetupRepository initialSetupRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            ActivityLogService activityLogService,
            Clock clock) {
        this.initialSetupRepository = initialSetupRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        return !membershipRepository.existsByRole(OrganizationRole.OWNER);
    }

    @Transactional
    public void complete(
            String organizationName, String username, String rawPassword, String displayName, String email) {
        initialSetupRepository.lock();
        if (!isSetupRequired()) {
            throw new SetupAlreadyCompletedException();
        }

        List<Organization> organizations = organizationRepository.findAll();
        if (organizations.size() > 1) {
            throw new ValidationFailedException(
                    "Initial setup cannot continue because multiple organizations already exist.");
        }

        String normalizedOrganizationName = organizationName.trim();
        String normalizedUsername = username.trim();
        String normalizedDisplayName = displayName.trim();
        String normalizedEmail = blankToNull(email);
        if (userRepository.findByUsernameIgnoreCase(normalizedUsername).isPresent()) {
            throw new ValidationFailedException("Username is already taken.");
        }

        Instant now = clock.instant();
        Organization organization;
        if (organizations.isEmpty()) {
            organization =
                    organizationRepository.save(new Organization(UUID.randomUUID(), normalizedOrganizationName, now));
        } else {
            organization = organizations.getFirst();
            organization.rename(normalizedOrganizationName, now);
        }

        User owner = userRepository.save(new User(
                UUID.randomUUID(),
                normalizedUsername,
                normalizedEmail,
                normalizedDisplayName,
                passwordEncoder.encode(rawPassword),
                true,
                now));
        membershipRepository.save(new OrganizationMembership(
                UUID.randomUUID(), organization.getId(), owner.getId(), OrganizationRole.OWNER, now));
        activityLogService.record(
                organization.getId(),
                owner.getId(),
                "INITIAL_SETUP_COMPLETED",
                "ORGANIZATION",
                organization.getId(),
                Map.of("organizationName", normalizedOrganizationName, "ownerUsername", normalizedUsername));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
