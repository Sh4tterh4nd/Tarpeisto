package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.AuditMutationConflictException;
import io.kellermann.tarpeisto.exception.InvalidCredentialsException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TemporaryAccessInvitation;
import io.kellermann.tarpeisto.model.VolunteerSession;
import io.kellermann.tarpeisto.repository.AuditBatchRepository;
import io.kellermann.tarpeisto.repository.BookingRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.repository.TemporaryAccessRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.security.TemporaryAccessContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemporaryAccessService {
    private final TemporaryAccessRepository grants;
    private final OrganizationRepository organizations;
    private final BookingRepository bookings;
    private final AuditBatchRepository batches;
    private final Clock clock;
    private final ActivityLogService activity;
    private final SecureRandom random = new SecureRandom();

    public TemporaryAccessService(
            TemporaryAccessRepository grants,
            OrganizationRepository organizations,
            BookingRepository bookings,
            AuditBatchRepository batches,
            Clock clock,
            ActivityLogService activity) {
        this.grants = grants;
        this.organizations = organizations;
        this.bookings = bookings;
        this.batches = batches;
        this.clock = clock;
        this.activity = activity;
    }

    @Transactional
    public IssuedInvitation create(TarpeistoPrincipal principal, UUID bookingId, UUID auditBatchId) {
        requireManager(principal);
        requireScope(principal, bookingId, auditBatchId);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = clock.instant();
        var invitation = new TemporaryAccessInvitation(
                UUID.randomUUID(),
                principal.organizationId(),
                bookingId,
                auditBatchId,
                now,
                now.plus(Duration.ofHours(24)),
                null);
        grants.insert(invitation, digest(token), principal.userId());
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "TEMPORARY_ACCESS_ISSUED",
                "TEMPORARY_ACCESS_INVITATION",
                invitation.id(),
                null);
        return new IssuedInvitation(invitation, token);
    }

    @Transactional(readOnly = true)
    public List<TemporaryAccessInvitation> list(TarpeistoPrincipal principal, UUID bookingId, UUID auditBatchId) {
        requireManager(principal);
        requireScope(principal, bookingId, auditBatchId);
        return grants.list(principal.organizationId(), bookingId, auditBatchId);
    }

    @Transactional
    public void revoke(TarpeistoPrincipal principal, UUID id) {
        requireManager(principal);
        lock(principal.organizationId());
        grants.find(principal.organizationId(), id).orElseThrow(() -> new NotFoundException("Invitation not found."));
        grants.revoke(principal.organizationId(), id, principal.userId(), clock.instant());
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "TEMPORARY_ACCESS_REVOKED",
                "TEMPORARY_ACCESS_INVITATION",
                id,
                null);
    }

    @Transactional
    public TarpeistoPrincipal redeem(String token, String displayName, UUID operationId) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidInvitation();
        String name = displayName == null ? "" : displayName.strip().replaceAll("\\s+", " ");
        if (name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ValidationFailedException("Enter a display name of 1 to 100 characters.");
        if (operationId == null) throw new ValidationFailedException("A redemption operation ID is required.");
        var initial = grants.findByDigest(digest(token)).orElseThrow(TemporaryAccessService::invalidInvitation);
        lock(initial.organizationId());
        var invitation = grants.find(initial.organizationId(), initial.id())
                .orElseThrow(TemporaryAccessService::invalidInvitation);
        if (invitation.revokedAt() != null || !clock.instant().isBefore(invitation.expiresAt()))
            throw invalidInvitation();
        var existing = grants.redemption(invitation.id(), operationId);
        if (existing.isPresent()) {
            if (!existing.get().displayName().equals(name)) throw new AuditMutationConflictException();
            return principal(existing.get());
        }
        grants.createVolunteer(
                invitation.organizationId(),
                invitation.id(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                operationId,
                name,
                clock.instant(),
                invitation.expiresAt());
        var session = grants.redemption(invitation.id(), operationId).orElseThrow();
        activity.record(
                session.organizationId(),
                session.userId(),
                "VOLUNTEER_JOINED",
                "VOLUNTEER_SESSION",
                session.id(),
                null);
        return principal(session);
    }

    public TarpeistoPrincipal refresh(TarpeistoPrincipal principal) {
        if (!principal.temporary()) return principal;
        var session = grants.validSession(
                        principal.organizationId(),
                        principal.temporaryAccess().sessionId(),
                        principal.userId(),
                        clock.instant())
                .orElseThrow(() -> new InvalidCredentialsException("Temporary access has expired or was revoked."));
        if (!session.invitationId().equals(principal.temporaryAccess().invitationId())) throw invalidInvitation();
        return principal(session);
    }

    public void requireTask(TarpeistoPrincipal principal, UUID taskId) {
        if (principal.temporary()) {
            refresh(principal);
            if (!grants.taskAllowed(
                    principal.organizationId(), principal.temporaryAccess().invitationId(), taskId))
                throw scopeDenied();
        }
    }

    public void requireAudit(TarpeistoPrincipal principal, UUID auditId) {
        if (principal.temporary()) {
            refresh(principal);
            if (!grants.auditAllowed(
                    principal.organizationId(), principal.temporaryAccess().invitationId(), auditId))
                throw scopeDenied();
        }
    }

    public boolean assetAllowed(TarpeistoPrincipal principal, UUID assetId) {
        if (!principal.temporary()) return true;
        refresh(principal);
        return grants.assetAllowed(
                principal.organizationId(), principal.temporaryAccess().invitationId(), assetId);
    }

    public void requireAsset(TarpeistoPrincipal principal, UUID assetId) {
        if (!assetAllowed(principal, assetId)) throw scopeDenied();
    }

    public void requireModel(TarpeistoPrincipal principal, UUID modelId) {
        if (principal.temporary()) {
            refresh(principal);
            if (!grants.modelAllowed(
                    principal.organizationId(), principal.temporaryAccess().invitationId(), modelId))
                throw scopeDenied();
        }
    }

    public List<UUID> assignedTasks(TarpeistoPrincipal principal) {
        if (principal == null || !principal.temporary()) throw scopeDenied();
        refresh(principal);
        return grants.assignedTaskIds(
                principal.organizationId(), principal.temporaryAccess().invitationId());
    }

    private void requireScope(TarpeistoPrincipal p, UUID booking, UUID batch) {
        if ((booking == null) == (batch == null))
            throw new ValidationFailedException("Choose exactly one event or audit batch.");
        if (booking != null)
            bookings.findByIdAndOrganizationId(booking, p.organizationId())
                    .orElseThrow(() -> new NotFoundException("Event not found."));
        if (batch != null)
            batches.findById(batch)
                    .filter(b -> b.getOrganizationId().equals(p.organizationId()))
                    .orElseThrow(() -> new NotFoundException("Audit batch not found."));
    }

    private void lock(UUID org) {
        organizations.findWithLockById(org).orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private void requireManager(TarpeistoPrincipal p) {
        if (p == null || p.temporary() || (p.role() != OrganizationRole.OWNER && p.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }

    private static AccessDeniedException scopeDenied() {
        return new AccessDeniedException("This resource is outside your assignment.");
    }

    private static InvalidCredentialsException invalidInvitation() {
        return new InvalidCredentialsException("Invitation is unavailable.");
    }

    public static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private TarpeistoPrincipal principal(VolunteerSession s) {
        return new TarpeistoPrincipal(
                s.userId(),
                s.username(),
                s.displayName(),
                s.organizationId(),
                OrganizationRole.OPERATOR_AUDITOR,
                new TemporaryAccessContext(s.id(), s.invitationId(), s.bookingId(), s.auditBatchId(), s.expiresAt()));
    }
    /** Creation-only secret; never record this value in history or logs. */
    public record IssuedInvitation(TemporaryAccessInvitation invitation, String token) {}
}
