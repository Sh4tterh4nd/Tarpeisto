package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.AssetSealHistory;
import io.kellermann.bigcontainers.model.AssetVerificationHistory;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.SealHistoryAction;
import io.kellermann.bigcontainers.model.VerificationState;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.AssetSealHistoryRepository;
import io.kellermann.bigcontainers.repository.AssetVerificationHistoryRepository;
import io.kellermann.bigcontainers.repository.AuditScanRepository;
import io.kellermann.bigcontainers.repository.AuditTaskDependencyRepository;
import io.kellermann.bigcontainers.repository.AuditTaskRepository;
import io.kellermann.bigcontainers.repository.ContainerAuditRepository;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Seal projection changes always append a history row. Numbered seal references are deliberately absent. */
@Service
public class AssetSealService {
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final AssetSealHistoryRepository history;
    private final AuditTaskRepository tasks;
    private final AuditTaskDependencyRepository dependencies;
    private final ContainerAuditRepository audits;
    private final Clock clock;
    private final OrganizationRepository organizations;
    private final AssetVerificationHistoryRepository verificationHistory;
    private final BookingReturnStateService returnStates;
    private final ActivityLogService activity;
    private final AuditScanRepository scans;
    private final BookingImpactService bookingImpact;

    public AssetSealService(
            AssetRepository assets,
            AssetModelRepository models,
            AssetSealHistoryRepository history,
            AuditTaskRepository tasks,
            AuditTaskDependencyRepository dependencies,
            ContainerAuditRepository audits,
            OrganizationRepository organizations,
            AssetVerificationHistoryRepository verificationHistory,
            BookingReturnStateService returnStates,
            ActivityLogService activity,
            AuditScanRepository scans,
            BookingImpactService bookingImpact,
            Clock clock) {
        this.assets = assets;
        this.models = models;
        this.history = history;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.audits = audits;
        this.clock = clock;
        this.organizations = organizations;
        this.verificationHistory = verificationHistory;
        this.returnStates = returnStates;
        this.activity = activity;
        this.scans = scans;
        this.bookingImpact = bookingImpact;
    }

    @Transactional
    public void setSealable(BigContainersPrincipal principal, UUID assetId, boolean sealable) {
        requireReviewer(principal);
        organizations.findWithLockById(principal.organizationId()).orElseThrow();
        Asset asset = asset(principal.organizationId(), assetId);
        AssetModel model = models.findByIdAndOrganizationId(asset.getAssetModelId(), principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset model not found."));
        if (!model.isCanContainAssets()) throw new ValidationFailedException("Only a container can be made sealable.");
        if (asset.isSealable() != sealable) {
            invalidate(principal, assetId, "Sealability changed.");
            asset.setSealable(sealable, clock.instant());
            activity.record(
                    principal.organizationId(),
                    principal.userId(),
                    "ASSET_SEALABILITY_CHANGED",
                    "ASSET",
                    assetId,
                    java.util.Map.of("sealable", sealable));
            bookingImpact.changed(principal);
        }
    }

    @Transactional
    public void breakSeal(BigContainersPrincipal principal, UUID assetId, String note) {
        requireReviewer(principal);
        organizations.findWithLockById(principal.organizationId()).orElseThrow();
        Asset asset = asset(principal.organizationId(), assetId);
        if (!asset.isSealable()) throw new ValidationFailedException("This container is not sealable.");
        asset.breakSeal(clock.instant());
        history.save(new AssetSealHistory(
                UUID.randomUUID(),
                principal.organizationId(),
                assetId,
                SealHistoryAction.BROKEN,
                null,
                blankToNull(note),
                principal.userId(),
                clock.instant()));
        invalidateVerification(principal, asset);
        tasks
                .findAllByOrganizationIdAndContainerAssetIdOrderByCreatedAtDesc(principal.organizationId(), assetId)
                .stream()
                .filter(task -> audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(
                                principal.organizationId(), task.getId())
                        .isPresent())
                .findFirst()
                .ifPresent(task -> {
                    reopenAttempt(principal, task, false, new java.util.HashSet<>());
                    audits.flush();
                    returnStates.recalculateForBatch(principal.organizationId(), task.getAuditBatchId());
                });
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_SEAL_BROKEN",
                "ASSET",
                assetId,
                java.util.Map.of());
        bookingImpact.changed(principal);
    }

    /** Packing and physical-content changes revoke verification and dependent readiness. */
    @Transactional
    public void invalidate(BigContainersPrincipal principal, UUID assetId, String note) {
        Asset asset = asset(principal.organizationId(), assetId);
        if (asset.getLastVerifiedAuditId() != null) invalidateVerification(principal, asset);
        if (asset.isSealable()
                && asset.getSealState() != io.kellermann.bigcontainers.model.SealState.UNSEALED
                && asset.getSealState() != io.kellermann.bigcontainers.model.SealState.INVALIDATED) {
            asset.invalidateSeal(clock.instant());
            history.save(new AssetSealHistory(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    assetId,
                    SealHistoryAction.INVALIDATED,
                    null,
                    note,
                    principal.userId(),
                    clock.instant()));
        }
        tasks
                .findAllByOrganizationIdAndContainerAssetIdOrderByCreatedAtDesc(principal.organizationId(), assetId)
                .stream()
                .filter(task -> audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(
                                principal.organizationId(), task.getId())
                        .map(current ->
                                current.getState() == io.kellermann.bigcontainers.model.ContainerAuditState.COMPLETED)
                        .orElse(false))
                .findFirst()
                .ifPresent(task -> {
                    reopenAttempt(principal, task, false, new java.util.HashSet<>());
                    audits.flush();
                    returnStates.recalculateForBatch(principal.organizationId(), task.getAuditBatchId());
                });
    }

    private void invalidateVerification(BigContainersPrincipal principal, Asset asset) {
        UUID auditId = asset.getLastVerifiedAuditId();
        if (auditId != null) {
            verificationHistory.save(new AssetVerificationHistory(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    asset.getId(),
                    auditId,
                    VerificationState.INVALIDATED,
                    clock.instant(),
                    principal.userId()));
            asset.invalidateVerification(clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public List<SealHistoryView> history(BigContainersPrincipal principal, UUID assetId) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
        assets.findByIdAndOrganizationId(assetId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        return history
                .findAllByOrganizationIdAndAssetIdOrderByOccurredAtDesc(principal.organizationId(), assetId)
                .stream()
                .map(row -> new SealHistoryView(row.getAction(), row.getOccurredAt()))
                .toList();
    }

    private Asset asset(UUID org, UUID id) {
        return assets.findWithLockByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new NotFoundException("Asset not found."));
    }

    private void reopenAttempt(
            BigContainersPrincipal principal,
            io.kellermann.bigcontainers.model.AuditTask task,
            boolean blocked,
            java.util.Set<UUID> visited) {
        UUID organizationId = principal.organizationId();
        if (!visited.add(task.getId())) return;
        audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(organizationId, task.getId())
                .ifPresent(current -> {
                    scans.findAllByOrganizationIdAndAuditIdOrderByScannedAtAsc(organizationId, current.getId()).stream()
                            .filter(scan -> scan.getUndoneAt() == null)
                            .forEach(scan -> assets.findByIdAndOrganizationId(scan.getAssetId(), organizationId)
                                    .filter(asset -> current.getId().equals(asset.getLastVerifiedAuditId()))
                                    .ifPresent(asset -> invalidateVerification(principal, asset)));
                    current.retireFromCurrentAttempt();
                });
        Asset container = asset(organizationId, task.getContainerAssetId());
        invalidateVerification(principal, container);
        if (blocked
                && container.isSealable()
                && container.getSealState() != io.kellermann.bigcontainers.model.SealState.UNSEALED
                && container.getSealState() != io.kellermann.bigcontainers.model.SealState.INVALIDATED) {
            container.invalidateSeal(clock.instant());
            history.save(new AssetSealHistory(
                    UUID.randomUUID(),
                    organizationId,
                    container.getId(),
                    SealHistoryAction.INVALIDATED,
                    null,
                    "Dependent child verification changed.",
                    principal.userId(),
                    clock.instant()));
        }
        if (blocked) task.block();
        else task.reopen();
        for (var dependency : dependencies.findAllByOrganizationIdAndIdDependsOnTaskId(organizationId, task.getId())) {
            tasks.findByOrganizationIdAndId(organizationId, dependency.getId().getTaskId())
                    .ifPresent(parent -> reopenAttempt(principal, parent, true, visited));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireReviewer(BigContainersPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }
}
