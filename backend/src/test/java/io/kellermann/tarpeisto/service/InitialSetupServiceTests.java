package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class InitialSetupServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock
    private InitialSetupRepository initialSetupRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationMembershipRepository membershipRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ActivityLogService activityLogService;

    private InitialSetupService service;

    @BeforeEach
    void setUp() {
        service = new InitialSetupService(
                initialSetupRepository,
                organizationRepository,
                userRepository,
                membershipRepository,
                passwordEncoder,
                activityLogService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reportsSetupRequiredOnlyWhileNoOwnerExists() {
        when(membershipRepository.existsByRole(OrganizationRole.OWNER)).thenReturn(false, true);

        assertThat(service.isSetupRequired()).isTrue();
        assertThat(service.isSetupRequired()).isFalse();
    }

    @Test
    void createsAnOrganizationAndLocalOwnerAtomically() {
        when(membershipRepository.existsByRole(OrganizationRole.OWNER)).thenReturn(false);
        when(organizationRepository.findAll()).thenReturn(List.of());
        when(userRepository.findByUsernameIgnoreCase("owner")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("strong-password")).thenReturn("encoded-password");
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete("  Alpine Events  ", " owner ", "strong-password", " First Owner ", " owner@example.org ");

        InOrder order = inOrder(initialSetupRepository, membershipRepository);
        order.verify(initialSetupRepository).lock();
        order.verify(membershipRepository).existsByRole(OrganizationRole.OWNER);

        ArgumentCaptor<Organization> organization = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(organization.capture());
        assertThat(organization.getValue().getName()).isEqualTo("Alpine Events");

        ArgumentCaptor<User> owner = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(owner.capture());
        assertThat(owner.getValue().getUsername()).isEqualTo("owner");
        assertThat(owner.getValue().getDisplayName()).isEqualTo("First Owner");
        assertThat(owner.getValue().getEmail()).isEqualTo("owner@example.org");
        assertThat(owner.getValue().getPasswordHash()).isEqualTo("encoded-password");

        ArgumentCaptor<OrganizationMembership> membership = ArgumentCaptor.forClass(OrganizationMembership.class);
        verify(membershipRepository).save(membership.capture());
        assertThat(membership.getValue().getRole()).isEqualTo(OrganizationRole.OWNER);
        assertThat(membership.getValue().getOrganizationId())
                .isEqualTo(organization.getValue().getId());
        assertThat(membership.getValue().getUserId()).isEqualTo(owner.getValue().getId());
        verify(activityLogService)
                .record(
                        eq(organization.getValue().getId()),
                        eq(owner.getValue().getId()),
                        eq("INITIAL_SETUP_COMPLETED"),
                        eq("ORGANIZATION"),
                        eq(organization.getValue().getId()),
                        any());
    }

    @Test
    void reusesAndRenamesTheSingleOrganizationLeftByTheOldSeeder() {
        Organization existing = new Organization(java.util.UUID.randomUUID(), "Default Organization", NOW);
        when(membershipRepository.existsByRole(OrganizationRole.OWNER)).thenReturn(false);
        when(organizationRepository.findAll()).thenReturn(List.of(existing));
        when(userRepository.findByUsernameIgnoreCase("owner")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("strong-password")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete("My Organization", "owner", "strong-password", "Owner", "");

        assertThat(existing.getName()).isEqualTo("My Organization");
        verify(organizationRepository, never()).save(any(Organization.class));
    }

    @Test
    void rejectsASecondSetupAfterTakingTheSerializationLock() {
        when(membershipRepository.existsByRole(OrganizationRole.OWNER)).thenReturn(true);

        assertThatThrownBy(() -> service.complete("Org", "owner", "strong-password", "Owner", null))
                .isInstanceOf(SetupAlreadyCompletedException.class);

        InOrder order = inOrder(initialSetupRepository, membershipRepository);
        order.verify(initialSetupRepository).lock();
        order.verify(membershipRepository).existsByRole(OrganizationRole.OWNER);
        verify(organizationRepository, never()).findAll();
    }

    @Test
    void refusesToGuessWhichOrganizationToUseWhenTheDatabaseIsInconsistent() {
        when(membershipRepository.existsByRole(OrganizationRole.OWNER)).thenReturn(false);
        when(organizationRepository.findAll())
                .thenReturn(List.of(
                        new Organization(java.util.UUID.randomUUID(), "One", NOW),
                        new Organization(java.util.UUID.randomUUID(), "Two", NOW)));

        assertThatThrownBy(() -> service.complete("Org", "owner", "strong-password", "Owner", null))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("multiple organizations");
    }
}
