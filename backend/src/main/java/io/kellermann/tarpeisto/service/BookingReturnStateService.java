package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetSealHistory;
import io.kellermann.tarpeisto.model.AssetVerificationHistory;
import io.kellermann.tarpeisto.model.AuditBatch;
import io.kellermann.tarpeisto.model.AuditTask;
import io.kellermann.tarpeisto.model.AuditTaskDependency;
import io.kellermann.tarpeisto.model.AuditTaskState;
import io.kellermann.tarpeisto.model.Booking;
import io.kellermann.tarpeisto.model.CheckoutConsumableSemantics;
import io.kellermann.tarpeisto.model.ContainerAudit;
import io.kellermann.tarpeisto.model.ContainerAuditState;
import io.kellermann.tarpeisto.model.SealHistoryAction;
import io.kellermann.tarpeisto.model.SealState;
import io.kellermann.tarpeisto.model.VerificationState;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.AssetSealHistoryRepository;
import io.kellermann.tarpeisto.repository.AssetVerificationHistoryRepository;
import io.kellermann.tarpeisto.repository.AuditBatchRepository;
import io.kellermann.tarpeisto.repository.AuditFindingRepository;
import io.kellermann.tarpeisto.repository.AuditScanRepository;
import io.kellermann.tarpeisto.repository.AuditTaskDependencyRepository;
import io.kellermann.tarpeisto.repository.AuditTaskRepository;
import io.kellermann.tarpeisto.repository.BookingRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestAssetRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestConsumableRepository;
import io.kellermann.tarpeisto.repository.ContainerAuditRepository;
import io.kellermann.tarpeisto.repository.FindingResolutionRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Derives event-return state from immutable audit facts and append-only resolutions. */
@Service
public class BookingReturnStateService {
    private final AuditBatchRepository batches;
    private final AuditTaskRepository tasks;
    private final AuditTaskDependencyRepository dependencies;
    private final ContainerAuditRepository audits;
    private final AuditFindingRepository findings;
    private final FindingResolutionRepository resolutions;
    private final CheckoutManifestAssetRepository manifestAssets;
    private final CheckoutManifestConsumableRepository manifestConsumables;
    private final BookingRepository bookings;
    private final AssetRepository assets;
    private final AuditScanRepository scans;
    private final AssetVerificationHistoryRepository verificationHistory;
    private final AssetSealHistoryRepository sealHistory;
    private final Clock clock;

    public BookingReturnStateService(
            AuditBatchRepository batches,
            AuditTaskRepository tasks,
            AuditTaskDependencyRepository dependencies,
            ContainerAuditRepository audits,
            AuditFindingRepository findings,
            FindingResolutionRepository resolutions,
            CheckoutManifestAssetRepository manifestAssets,
            CheckoutManifestConsumableRepository manifestConsumables,
            BookingRepository bookings,
            AssetRepository assets,
            AuditScanRepository scans,
            AssetVerificationHistoryRepository verificationHistory,
            AssetSealHistoryRepository sealHistory,
            Clock clock) {
        this.batches = batches;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.audits = audits;
        this.findings = findings;
        this.resolutions = resolutions;
        this.manifestAssets = manifestAssets;
        this.manifestConsumables = manifestConsumables;
        this.bookings = bookings;
        this.assets = assets;
        this.scans = scans;
        this.verificationHistory = verificationHistory;
        this.sealHistory = sealHistory;
        this.clock = clock;
    }

    /** Review clears an effective attempt without changing its immutable completion outcome. */
    @Transactional
    public void confirmReviewedAudit(TarpeistoPrincipal principal, UUID auditId) {
        ContainerAudit audit = audits.findByOrganizationIdAndId(principal.organizationId(), auditId)
                .orElseThrow();
        if (!audit.isCurrentAttempt()
                || audit.getState() != ContainerAuditState.COMPLETED
                || hasUnresolvedFindings(principal.organizationId(), auditId)) return;
        java.util.Set<UUID> verified = new java.util.HashSet<>();
        verified.add(audit.getContainerAssetId());
        scans.findAllByOrganizationIdAndAuditIdOrderByScannedAtAsc(principal.organizationId(), auditId).stream()
                .filter(scan -> scan.getUndoneAt() == null)
                .forEach(scan -> verified.add(scan.getAssetId()));
        for (UUID id : verified) {
            Asset asset = assets.findByIdAndOrganizationId(id, principal.organizationId())
                    .orElseThrow();
            if (asset.isActive() && !auditId.equals(asset.getLastVerifiedAuditId())) {
                asset.recordVerification(auditId, clock.instant());
                verificationHistory.save(new AssetVerificationHistory(
                        UUID.randomUUID(),
                        principal.organizationId(),
                        id,
                        auditId,
                        VerificationState.VERIFIED,
                        clock.instant(),
                        principal.userId()));
            }
        }
        Asset container = assets.findByIdAndOrganizationId(audit.getContainerAssetId(), principal.organizationId())
                .orElseThrow();
        java.util.Set<UUID> released = new java.util.HashSet<>(verified);
        findings.findAllByOrganizationIdAndAuditIdOrderById(principal.organizationId(), auditId).stream()
                .filter(finding -> finding.getAssetId() != null)
                .forEach(finding -> released.add(finding.getAssetId()));
        UUID manifestId = batches.findByIdAndOrganizationId(audit.getAuditBatchId(), principal.organizationId())
                .orElseThrow()
                .getManifestId();
        manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(principal.organizationId(), manifestId).stream()
                .filter(item -> released.contains(item.getAssetId()) && item.getReturnedAt() != null)
                .forEach(item -> item.releaseAfterAudit(clock.instant()));
        if (container.isSealable() && container.getSealState() == SealState.APPLIED) {
            container.verifySeal(clock.instant());
            sealHistory.save(new AssetSealHistory(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    container.getId(),
                    SealHistoryAction.VERIFIED,
                    auditId,
                    null,
                    principal.userId(),
                    clock.instant()));
        }
    }

    @Transactional
    public void recalculateForAudit(UUID organizationId, UUID auditId) {
        ContainerAudit audit =
                audits.findByOrganizationIdAndId(organizationId, auditId).orElseThrow();
        recalculateForBatch(organizationId, audit.getAuditBatchId());
    }

    @Transactional
    public void recalculateForBatch(UUID organizationId, UUID batchId) {
        AuditBatch batch =
                batches.findByIdAndOrganizationId(batchId, organizationId).orElseThrow();
        List<AuditTask> batchTasks = tasks.findAllByOrganizationIdAndAuditBatchIdOrderById(organizationId, batchId);
        unlockReviewedParents(organizationId, batchTasks);

        boolean unresolved = audits.findAllByOrganizationIdAndAuditBatchIdOrderById(organizationId, batchId).stream()
                .anyMatch(audit -> hasUnresolvedFindings(organizationId, audit.getId()));
        Booking booking = bookings.findByIdAndOrganizationId(batch.getBookingId(), organizationId)
                .orElseThrow();
        if (unresolved) {
            booking.markReviewRequired(clock.instant());
            return;
        }
        boolean auditsCurrentAndComplete = batchTasks.stream()
                .allMatch(task -> currentAudit(organizationId, task)
                        .map(audit -> audit.getState() == ContainerAuditState.COMPLETED)
                        .orElse(false));
        if (auditsCurrentAndComplete) {
            // Every observation in the current attempts has now been reviewed. Physical return
            // facts and formal accounting remain distinct, and neither is rewritten.
            manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(organizationId, batch.getManifestId()).stream()
                    .filter(item -> item.getReturnedAt() != null)
                    .forEach(item -> item.releaseAfterAudit(clock.instant()));
        }
        boolean custodyReleased =
                manifestAssets
                        .findAllByOrganizationIdAndManifestIdOrderById(organizationId, batch.getManifestId())
                        .stream()
                        .allMatch(item -> item.getAuditReleasedAt() != null);
        boolean consumablesAccounted =
                manifestConsumables
                        .findAllByOrganizationIdAndManifestIdOrderById(organizationId, batch.getManifestId())
                        .stream()
                        .filter(item -> item.getSemantics() == CheckoutConsumableSemantics.SEPARATELY_ISSUED)
                        .allMatch(item -> item.getAccountedAt() != null);
        if (auditsCurrentAndComplete && custodyReleased && consumablesAccounted) {
            booking.completeReturn(clock.instant());
        } else {
            booking.markReturnedAuditsPending(clock.instant());
        }
    }

    private void unlockReviewedParents(UUID organizationId, List<AuditTask> batchTasks) {
        for (AuditTask parent : batchTasks) {
            if (parent.getState() != AuditTaskState.BLOCKED) continue;
            List<AuditTaskDependency> childDependencies =
                    dependencies.findAllByOrganizationIdAndIdTaskIdIn(organizationId, List.of(parent.getId()));
            if (!childDependencies.isEmpty()
                    && childDependencies.stream()
                            .allMatch(dependency -> tasks.findByOrganizationIdAndId(
                                            organizationId, dependency.getId().getDependsOnTaskId())
                                    .flatMap(task -> currentAudit(organizationId, task))
                                    .map(audit -> audit.getState() == ContainerAuditState.COMPLETED
                                            && !hasUnresolvedFindings(organizationId, audit.getId()))
                                    .orElse(false))) parent.markReady();
        }
    }

    private java.util.Optional<ContainerAudit> currentAudit(UUID organizationId, AuditTask task) {
        return audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(organizationId, task.getId());
    }

    private boolean hasUnresolvedFindings(UUID organizationId, UUID auditId) {
        return findings.findAllByOrganizationIdAndAuditIdOrderById(organizationId, auditId).stream()
                .anyMatch(finding -> resolutions
                        .findByOrganizationIdAndFindingId(organizationId, finding.getId())
                        .isEmpty());
    }
}
