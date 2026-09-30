package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.model.ArchiveKind;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.JdbcArchiveRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArchiveService {
    private final JdbcArchiveRepository archives;
    private final OrganizationRepository organizations;
    private final ActivityLogService activity;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entityManager;

    public ArchiveService(
            JdbcArchiveRepository archives,
            OrganizationRepository organizations,
            ActivityLogService activity,
            FindByIndexNameSessionRepository<?> sessions,
            Clock clock,
            jakarta.persistence.EntityManager entityManager) {
        this.archives = archives;
        this.organizations = organizations;
        this.activity = activity;
        this.sessions = sessions;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional
    public ArchiveView change(
            TarpeistoPrincipal principal, ArchiveKind kind, UUID id, boolean archived, long expectedVersion) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
        principal.requirePermanent();
        if (principal.role() != OrganizationRole.OWNER
                && (kind == ArchiveKind.USER || principal.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Manager role required.");
        organizations
                .findWithLockById(principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Organization not found."));
        entityManager.flush();
        var current = archives.lock(principal.organizationId(), kind, id);
        if (kind == ArchiveKind.USER) archives.requireSingleOrganizationUser(principal.organizationId(), id);
        if (current.archived() == archived) {
            if (current.version() != expectedVersion && current.version() != expectedVersion + 1)
                throw new ArchiveConflictException("Archive version is stale.");
            return new ArchiveView(id, archived, current.version());
        }
        if (current.version() != expectedVersion) throw new ArchiveConflictException("Archive version is stale.");
        if (archived) archives.requireEligible(principal.organizationId(), kind, id);
        entityManager.flush();
        archives.change(principal.organizationId(), kind, id, archived, clock.instant());
        entityManager.clear();
        if (kind == ArchiveKind.USER)
            sessions.findByPrincipalName(current.username()).keySet().forEach(sessions::deleteById);
        activity.record(
                principal.organizationId(),
                principal.userId(),
                archived ? "RECORD_ARCHIVED" : "RECORD_RESTORED",
                kind.name(),
                id,
                Map.of("version", current.version() + 1));
        return new ArchiveView(id, archived, current.version() + 1);
    }
}
