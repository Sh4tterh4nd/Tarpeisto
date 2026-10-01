package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.AssetSealHistory;
import io.kellermann.tarpeisto.model.AssetVerificationHistory;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.SealHistoryAction;
import io.kellermann.tarpeisto.model.VerificationState;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.AssetSealHistoryRepository;
import io.kellermann.tarpeisto.repository.AssetVerificationHistoryRepository;
import io.kellermann.tarpeisto.repository.AuditScanRepository;
import io.kellermann.tarpeisto.repository.AuditTaskDependencyRepository;
import io.kellermann.tarpeisto.repository.AuditTaskRepository;
import io.kellermann.tarpeisto.repository.ContainerAuditRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
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
    private final io.kellermann.tarpeisto.repository.JdbcArchiveRepository archives;
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
            io.kellermann.tarpeisto.repository.JdbcArchiveRepository archives,
            Clock clock) {
        this.assets = assets;
        this.models = models;
        this.history = history;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.audits = audits;
        this.clock = clock;
        this.archives = archives;
        this.organizations = organizations;
        this.verificationHistory = verificationHistory;
        this.returnStates = returnStates;
        this.activity = activity;
        this.scans = scans;
        this.bookingImpact = bookingImpact;
    }

    @Transactional
    public void setSealable(TarpeistoPrincipal principal, UUID assetId, boolean sealable) {
        if (principal != null) principal.requirePermanent();
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
    public void applySeal(TarpeistoPrincipal principal, UUID assetId, long expectedVersion) {
        if (principal != null) principal.requirePermanent();
        requireReviewer(principal);
        organizations.findWithLockById(principal.organizationId()).orElseThrow();
        Asset asset = asset(principal.organizationId(), assetId);
        AssetModel model = models.findByIdAndOrganizationId(asset.getAssetModelId(), principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset model not found."));
        if (!asset.isActive() || model.isArchived() || !model.isCanContainAssets() || !asset.isSealable())
            throw new ValidationFailedException("Choose an active sealable container.");
        if (asset.getSealState() == io.kellermann.tarpeisto.model.SealState.APPLIED) return;
        if (asset.getVersion() != expectedVersion)
            throw new io.kellermann.tarpeisto.exception.PackingConflictException(
                    "The asset changed. Refresh before applying a physical seal.");
        invalidate(principal, assetId, "Physical seal applied; verification requires an audit.");
        asset.applySeal(clock.instant());
        history.save(new AssetSealHistory(
                UUID.randomUUID(),
                principal.organizationId(),
                assetId,
                SealHistoryAction.APPLIED,
                null,
                null,
                principal.userId(),
                clock.instant()));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_SEAL_APPLIED",
                "ASSET",
                assetId,
                java.util.Map.of());
        bookingImpact.changed(principal);
    }

    @Transactional
    public void breakSeal(TarpeistoPrincipal principal, UUID assetId, String note) {
        if (principal != null) principal.requirePermanent();
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
                .filter(task -> !archives.taskArchived(principal.organizationId(), task.getId()))
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
    public void invalidate(TarpeistoPrincipal principal, UUID assetId, String note) {
        if (principal != null) principal.requirePermanent();
        organizations.findWithLockById(principal.organizationId()).orElseThrow();
        Asset asset = asset(principal.organizationId(), assetId);
        if (asset.getLastVerifiedAuditId() != null) invalidateVerification(principal, asset);
        if (asset.isSealable()
                && asset.getSealState() != io.kellermann.tarpeisto.model.SealState.UNSEALED
                && asset.getSealState() != io.kellermann.tarpeisto.model.SealState.INVALIDATED) {
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
                                current.getState() == io.kellermann.tarpeisto.model.ContainerAuditState.COMPLETED)
                        .orElse(false))
                .findFirst()
                .filter(task -> !archives.taskArchived(principal.organizationId(), task.getId()))
                .ifPresent(task -> {
                    reopenAttempt(principal, task, false, new java.util.HashSet<>());
                    audits.flush();
                    returnStates.recalculateForBatch(principal.organizationId(), task.getAuditBatchId());
                });
    }

    private void invalidateVerification(TarpeistoPrincipal principal, Asset asset) {
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
    public List<SealHistoryView> history(TarpeistoPrincipal principal, UUID assetId) {
        if (principal != null) principal.requirePermanent();
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
            TarpeistoPrincipal principal,
            io.kellermann.tarpeisto.model.AuditTask task,
            boolean blocked,
            java.util.Set<UUID> visited) {
        UUID organizationId = principal.organizationId();
        if (!visited.add(task.getId()) || archives.taskArchived(organizationId, task.getId())) return;
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
                && container.getSealState() != io.kellermann.tarpeisto.model.SealState.UNSEALED
                && container.getSealState() != io.kellermann.tarpeisto.model.SealState.INVALIDATED) {
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

    private static void requireReviewer(TarpeistoPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }
}
