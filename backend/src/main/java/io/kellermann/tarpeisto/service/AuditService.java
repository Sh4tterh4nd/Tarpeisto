package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.AuditMutationConflictException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetCode;
import io.kellermann.tarpeisto.model.AssetCodeValidation;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.AssetSealHistory;
import io.kellermann.tarpeisto.model.AssetVerificationHistory;
import io.kellermann.tarpeisto.model.AuditBatch;
import io.kellermann.tarpeisto.model.AuditCompletionOutcome;
import io.kellermann.tarpeisto.model.AuditConsumableObservation;
import io.kellermann.tarpeisto.model.AuditConsumableStatus;
import io.kellermann.tarpeisto.model.AuditExpectedRequirement;
import io.kellermann.tarpeisto.model.AuditFinding;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.AuditOperation;
import io.kellermann.tarpeisto.model.AuditScan;
import io.kellermann.tarpeisto.model.AuditScanOutcome;
import io.kellermann.tarpeisto.model.AuditTask;
import io.kellermann.tarpeisto.model.AuditTaskDependency;
import io.kellermann.tarpeisto.model.AuditTaskState;
import io.kellermann.tarpeisto.model.CheckoutManifestAsset;
import io.kellermann.tarpeisto.model.ConsumableStock;
import io.kellermann.tarpeisto.model.ContainerAudit;
import io.kellermann.tarpeisto.model.ContainerAuditState;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingRequirement;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.model.SealHistoryAction;
import io.kellermann.tarpeisto.model.StockMovementReason;
import io.kellermann.tarpeisto.model.VerificationState;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.AssetSealHistoryRepository;
import io.kellermann.tarpeisto.repository.AssetVerificationHistoryRepository;
import io.kellermann.tarpeisto.repository.AuditBatchRepository;
import io.kellermann.tarpeisto.repository.AuditConsumableObservationRepository;
import io.kellermann.tarpeisto.repository.AuditExpectedRequirementRepository;
import io.kellermann.tarpeisto.repository.AuditFindingRepository;
import io.kellermann.tarpeisto.repository.AuditOperationRepository;
import io.kellermann.tarpeisto.repository.AuditScanRepository;
import io.kellermann.tarpeisto.repository.AuditTaskDependencyRepository;
import io.kellermann.tarpeisto.repository.AuditTaskRepository;
import io.kellermann.tarpeisto.repository.BookingRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestAssetRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestConsumableRepository;
import io.kellermann.tarpeisto.repository.ConsumableStockRepository;
import io.kellermann.tarpeisto.repository.ContainerAuditRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Online-only Phase 9.1 audit workflow. Browser retry safety is server-side through operation UUIDs. */
@Service
public class AuditService {
    private final AuditTaskRepository tasks;
    private final AuditTaskDependencyRepository dependencies;
    private final AuditBatchRepository batches;
    private final ContainerAuditRepository audits;
    private final AuditExpectedRequirementRepository expected;
    private final AuditScanRepository scans;
    private final AuditFindingRepository findings;
    private final AuditConsumableObservationRepository consumables;
    private final AuditOperationRepository operations;
    private final PackingRequirementRepository requirements;
    private final AssetRepository assets;
    private final AssetSealHistoryRepository sealHistory;
    private final AssetVerificationHistoryRepository verificationHistory;
    private final ConsumableStockRepository stockBalances;
    private final ConsumableStockService stock;
    private final CheckoutManifestAssetRepository manifestAssets;
    private final ActivityLogService activity;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final OrganizationRepository organizations;
    private final BookingRepository bookings;
    private final CheckoutManifestConsumableRepository manifestConsumables;
    private final AssetModelRepository models;
    private final BookingReturnStateService returnStates;

    public AuditService(
            AuditTaskRepository tasks,
            AuditTaskDependencyRepository dependencies,
            AuditBatchRepository batches,
            ContainerAuditRepository audits,
            AuditExpectedRequirementRepository expected,
            AuditScanRepository scans,
            AuditFindingRepository findings,
            AuditConsumableObservationRepository consumables,
            AuditOperationRepository operations,
            PackingRequirementRepository requirements,
            AssetRepository assets,
            AssetSealHistoryRepository sealHistory,
            AssetVerificationHistoryRepository verificationHistory,
            ConsumableStockRepository stockBalances,
            ConsumableStockService stock,
            CheckoutManifestAssetRepository manifestAssets,
            ActivityLogService activity,
            Clock clock,
            ObjectMapper mapper,
            OrganizationRepository organizations,
            BookingRepository bookings,
            CheckoutManifestConsumableRepository manifestConsumables,
            AssetModelRepository models,
            BookingReturnStateService returnStates) {
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.batches = batches;
        this.audits = audits;
        this.expected = expected;
        this.scans = scans;
        this.findings = findings;
        this.consumables = consumables;
        this.operations = operations;
        this.requirements = requirements;
        this.assets = assets;
        this.sealHistory = sealHistory;
        this.verificationHistory = verificationHistory;
        this.stockBalances = stockBalances;
        this.stock = stock;
        this.manifestAssets = manifestAssets;
        this.activity = activity;
        this.clock = clock;
        this.mapper = mapper;
        this.organizations = organizations;
        this.bookings = bookings;
        this.manifestConsumables = manifestConsumables;
        this.models = models;
        this.returnStates = returnStates;
    }

    @Transactional(readOnly = true)
    public ContainerAuditView get(TarpeistoPrincipal principal, UUID taskId) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
        AuditTask task = task(principal, taskId);
        return audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(principal.organizationId(), taskId)
                .map(a -> view(principal.organizationId(), a))
                .orElseGet(() -> pendingView(principal.organizationId(), task));
    }

    @Transactional
    public ContainerAuditView start(TarpeistoPrincipal principal, UUID taskId, String targetCode) {
        authorizeOperator(principal);
        lockOrganization(principal);
        AuditTask task = task(principal, taskId);
        Asset target = requireAssetCode(principal.organizationId(), targetCode);
        if (!target.getId().equals(task.getContainerAssetId()))
            throw new ValidationFailedException("Scan the assigned container to start this audit.");
        Optional<ContainerAudit> existing =
                audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(principal.organizationId(), taskId);
        if (existing.isPresent()) return view(principal.organizationId(), existing.get());
        if (task.getState() != AuditTaskState.READY)
            throw new ValidationFailedException("This audit is blocked until its child containers are completed.");
        AuditBatch batch = batches.findById(task.getAuditBatchId())
                .orElseThrow(() -> new NotFoundException("Audit batch not found."));
        ContainerAudit audit = audits.save(new ContainerAudit(
                UUID.randomUUID(),
                principal.organizationId(),
                batch.getId(),
                task.getId(),
                target.getId(),
                principal.userId(),
                clock.instant(),
                audits.findAllByOrganizationIdAndAuditTaskIdOrderByAttemptNumberAsc(principal.organizationId(), taskId)
                                .size()
                        + 1));
        for (PackingRequirement requirement :
                requirements.findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(
                        principal.organizationId(), target.getId())) {
            if (!requirement.isArchived())
                expected.save(new AuditExpectedRequirement(
                        UUID.randomUUID(),
                        principal.organizationId(),
                        audit.getId(),
                        requirement,
                        requirementSnapshot(requirement)));
        }
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_STARTED",
                "CONTAINER_AUDIT",
                audit.getId(),
                Map.of("taskId", taskId, "containerAssetId", target.getId()));
        return view(principal.organizationId(), audit);
    }

    @Transactional
    public ContainerAuditView scan(TarpeistoPrincipal principal, UUID auditId, UUID operationId, String rawCode) {
        authorizeOperator(principal);
        ContainerAudit audit = activeAudit(principal, auditId);
        if (!recordOperation(principal, audit, operationId, "SCAN", fingerprint(rawCode)))
            return view(principal.organizationId(), audit);
        Asset asset;
        try {
            asset = requireAssetCode(principal.organizationId(), rawCode);
        } catch (ValidationFailedException ex) {
            findings.save(new AuditFinding(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    auditId,
                    null,
                    AuditFindingType.UNKNOWN_CODE,
                    ex.getMessage(),
                    json(Map.of("rawCode", rawCode)),
                    principal.userId(),
                    clock.instant()));
            return view(principal.organizationId(), audit);
        }
        if (!asset.isActive())
            throw new ValidationFailedException(
                    "This asset is inactive or lost. Deputy restoration is required before it can count.");
        if (!isManifestAsset(principal.organizationId(), audit, asset.getId())
                && manifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                        principal.organizationId(), asset.getId()))
            throw new ValidationFailedException("This asset is in the custody of another event or return batch.");
        requireSafePlacement(principal.organizationId(), asset, audit.getContainerAssetId());
        List<AuditScan> active = activeScans(principal.organizationId(), auditId);
        if (active.stream().anyMatch(scan -> scan.getAssetId().equals(asset.getId()))) {
            activity.record(
                    principal.organizationId(),
                    principal.userId(),
                    "AUDIT_SCAN_DUPLICATE",
                    "CONTAINER_AUDIT",
                    auditId,
                    Map.of("assetId", asset.getId()));
            return view(principal.organizationId(), audit);
        }
        List<UUID> otherAudits = audits
                .findAllByOrganizationIdAndAuditBatchIdOrderById(principal.organizationId(), audit.getAuditBatchId())
                .stream()
                .filter(ContainerAudit::isCurrentAttempt)
                .map(ContainerAudit::getId)
                .filter(id -> !id.equals(auditId))
                .toList();
        if (!otherAudits.isEmpty()
                && !scans.findAllByOrganizationIdAndAuditIdInAndAssetIdAndUndoneAtIsNull(
                                principal.organizationId(), otherAudits, asset.getId())
                        .isEmpty())
            throw new ValidationFailedException(
                    "This asset was scanned in another audit. Use Move scan here to transfer it.");
        AuditScanOutcome outcome = match(principal.organizationId(), auditId, asset, active);
        scans.save(new AuditScan(
                UUID.randomUUID(),
                principal.organizationId(),
                auditId,
                audit.getAuditBatchId(),
                asset.getId(),
                operationId,
                outcome,
                principal.userId(),
                clock.instant(),
                scanContext(principal.organizationId(), audit, asset)));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_SCAN_RECORDED",
                "CONTAINER_AUDIT",
                auditId,
                Map.of("assetId", asset.getId(), "outcome", outcome.name()));
        return view(principal.organizationId(), audit);
    }

    @Transactional
    public ContainerAuditView undo(TarpeistoPrincipal principal, UUID auditId, UUID scanId, UUID operationId) {
        authorizeOperator(principal);
        ContainerAudit audit = activeAudit(principal, auditId);
        if (!recordOperation(principal, audit, operationId, "UNDO", scanId.toString()))
            return view(principal.organizationId(), audit);
        AuditScan scan = scans.findByIdAndOrganizationIdAndAuditId(scanId, principal.organizationId(), auditId)
                .orElseThrow(() -> new NotFoundException("Audit scan not found."));
        scan.undo(principal.userId(), clock.instant());
        scans.flush();
        rematch(principal.organizationId(), auditId);
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_SCAN_UNDONE",
                "CONTAINER_AUDIT",
                auditId,
                Map.of("scanId", scanId));
        return view(principal.organizationId(), audit);
    }

    @Transactional
    public ContainerAuditView moveScanHere(
            TarpeistoPrincipal principal, UUID auditId, UUID sourceScanId, UUID operationId) {
        authorizeOperator(principal);
        ContainerAudit target = activeAudit(principal, auditId);
        if (!recordOperation(principal, target, operationId, "MOVE_SCAN", sourceScanId.toString()))
            return view(principal.organizationId(), target);
        AuditScan source = scans.findByIdAndOrganizationId(sourceScanId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Source audit scan not found."));
        if (source.getUndoneAt() != null || source.getAuditId().equals(auditId))
            throw new ValidationFailedException("That scan cannot be moved.");
        ContainerAudit sourceAudit = audits.findByOrganizationIdAndId(principal.organizationId(), source.getAuditId())
                .orElseThrow(() -> new NotFoundException("Source audit scan not found."));
        if (sourceAudit.getState() != ContainerAuditState.IN_PROGRESS)
            throw new ValidationFailedException("Completed audit observations cannot be moved.");
        if (!sourceAudit.getAuditBatchId().equals(target.getAuditBatchId()))
            throw new ValidationFailedException("A scan may only move within the same return batch.");
        Asset asset = assets.findByIdAndOrganizationId(source.getAssetId(), principal.organizationId())
                .orElseThrow();
        source.undo(principal.userId(), clock.instant());
        scans.flush();
        rematch(principal.organizationId(), source.getAuditId());
        scans.save(new AuditScan(
                UUID.randomUUID(),
                principal.organizationId(),
                auditId,
                target.getAuditBatchId(),
                asset.getId(),
                operationId,
                match(principal.organizationId(), auditId, asset, activeScans(principal.organizationId(), auditId)),
                principal.userId(),
                clock.instant(),
                scanContext(principal.organizationId(), target, asset)));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_SCAN_MOVED",
                "CONTAINER_AUDIT",
                auditId,
                Map.of("assetId", asset.getId(), "fromAuditId", source.getAuditId()));
        return view(principal.organizationId(), target);
    }

    @Transactional
    public ContainerAuditView moveCodeHere(TarpeistoPrincipal principal, UUID auditId, UUID operationId, String code) {
        authorizeOperator(principal);
        ContainerAudit target = activeAudit(principal, auditId);
        if (!recordOperation(principal, target, operationId, "MOVE_CODE", fingerprint(code)))
            return view(principal.organizationId(), target);
        Asset asset = requireAssetCode(principal.organizationId(), code);
        List<UUID> otherIds = audits
                .findAllByOrganizationIdAndAuditBatchIdOrderById(principal.organizationId(), target.getAuditBatchId())
                .stream()
                .filter(ContainerAudit::isCurrentAttempt)
                .map(ContainerAudit::getId)
                .filter(id -> !id.equals(auditId))
                .toList();
        List<AuditScan> sources = otherIds.isEmpty()
                ? List.of()
                : scans.findAllByOrganizationIdAndAuditIdInAndAssetIdAndUndoneAtIsNull(
                        principal.organizationId(), otherIds, asset.getId());
        if (sources.size() != 1)
            throw new ValidationFailedException("No active scan in another audit can be moved here.");
        AuditScan source = sources.getFirst();
        ContainerAudit sourceAudit = audits.findByOrganizationIdAndId(principal.organizationId(), source.getAuditId())
                .orElseThrow();
        if (sourceAudit.getState() != ContainerAuditState.IN_PROGRESS)
            throw new ValidationFailedException("Completed audit observations cannot be moved.");
        requireSafePlacement(principal.organizationId(), asset, target.getContainerAssetId());
        source.undo(principal.userId(), clock.instant());
        scans.flush();
        rematch(principal.organizationId(), source.getAuditId());
        scans.save(new AuditScan(
                UUID.randomUUID(),
                principal.organizationId(),
                auditId,
                target.getAuditBatchId(),
                asset.getId(),
                operationId,
                match(principal.organizationId(), auditId, asset, activeScans(principal.organizationId(), auditId)),
                principal.userId(),
                clock.instant(),
                scanContext(principal.organizationId(), target, asset)));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_SCAN_MOVED",
                "CONTAINER_AUDIT",
                auditId,
                Map.of("assetId", asset.getId(), "fromAuditId", source.getAuditId()));
        return view(principal.organizationId(), target);
    }

    @Transactional
    public ContainerAuditView recordFinding(
            TarpeistoPrincipal principal,
            UUID auditId,
            UUID operationId,
            AuditFindingType type,
            UUID assetId,
            String note) {
        authorizeOperator(principal);
        ContainerAudit audit = activeAudit(principal, auditId);
        if (!recordOperation(principal, audit, operationId, "FINDING_" + type.name(), fingerprint(assetId, note)))
            return view(principal.organizationId(), audit);
        if (assetId != null)
            assets.findByIdAndOrganizationId(assetId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Asset not found."));
        if (type == AuditFindingType.UNREADABLE_LABEL) {
            if (assetId == null)
                throw new ValidationFailedException("Select the expected asset with the unreadable label.");
            Asset asset = assets.findByIdAndOrganizationId(assetId, principal.organizationId())
                    .orElseThrow();
            if (!asset.isActive()) throw new ValidationFailedException("Inactive assets require Deputy restoration.");
            boolean exactExpected = expected
                    .findAllByOrganizationIdAndAuditIdOrderByDisplayOrderAsc(principal.organizationId(), auditId)
                    .stream()
                    .anyMatch(r -> assetId.equals(r.getSpecificAssetId()));
            if (!exactExpected)
                throw new ValidationFailedException("Select an exact expected asset or scan its readable code.");
            List<UUID> otherIds = audits
                    .findAllByOrganizationIdAndAuditBatchIdOrderById(
                            principal.organizationId(), audit.getAuditBatchId())
                    .stream()
                    .filter(ContainerAudit::isCurrentAttempt)
                    .map(ContainerAudit::getId)
                    .filter(id -> !id.equals(auditId))
                    .toList();
            if (!otherIds.isEmpty()
                    && !scans.findAllByOrganizationIdAndAuditIdInAndAssetIdAndUndoneAtIsNull(
                                    principal.organizationId(), otherIds, assetId)
                            .isEmpty())
                throw new ValidationFailedException("This asset was already observed in another audit.");
            if (!hasActive(activeScans(principal.organizationId(), auditId), assetId))
                scans.save(new AuditScan(
                        UUID.randomUUID(),
                        principal.organizationId(),
                        auditId,
                        audit.getAuditBatchId(),
                        assetId,
                        operationId,
                        AuditScanOutcome.EXPECTED_EXACT,
                        principal.userId(),
                        clock.instant(),
                        scanContext(principal.organizationId(), audit, asset)));
        }
        findings.save(new AuditFinding(
                UUID.randomUUID(),
                principal.organizationId(),
                auditId,
                assetId,
                type,
                note,
                json(Map.of()),
                principal.userId(),
                clock.instant()));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_FINDING_RECORDED",
                "CONTAINER_AUDIT",
                auditId,
                Map.of("type", type.name()));
        return view(principal.organizationId(), audit);
    }

    @Transactional
    public ContainerAuditView observeConsumable(
            TarpeistoPrincipal principal,
            UUID auditId,
            UUID expectedId,
            UUID operationId,
            AuditConsumableStatus status,
            BigDecimal observed,
            String reason) {
        authorizeOperator(principal);
        ContainerAudit audit = activeAudit(principal, auditId);
        if (!recordOperation(
                principal,
                audit,
                operationId,
                "CONSUMABLE_" + status.name(),
                fingerprint(expectedId, observed, reason))) return view(principal.organizationId(), audit);
        AuditExpectedRequirement row = expected.findByIdAndOrganizationIdAndAuditId(
                        expectedId, principal.organizationId(), auditId)
                .orElseThrow(() -> new NotFoundException("Expected consumable not found."));
        if (row.getType() != PackingRequirementType.CONSUMABLE_QUANTITY)
            throw new ValidationFailedException("This expected row is not a consumable.");
        if (consumables
                .findByOrganizationIdAndAuditIdAndExpectedId(principal.organizationId(), auditId, expectedId)
                .isPresent())
            throw new ValidationFailedException("This consumable already has a recorded observation.");
        if (observed != null
                && (observed.signum() < 0 || observed.stripTrailingZeros().scale() > 3))
            throw new ValidationFailedException(
                    "Observed quantity must be nonnegative with at most three decimal places.");
        if (status == AuditConsumableStatus.OBSERVED && observed == null)
            throw new ValidationFailedException("Enter the observed quantity.");
        if (status == AuditConsumableStatus.OBSERVED) {
            if (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)
                throw new AccessDeniedException(
                        "Only an Owner or Deputy may approve a balance-changing observed quantity.");
            ConsumableStock balance = stockBalances
                    .findByOrganizationIdAndAssetModelIdAndContainerAssetId(
                            principal.organizationId(), row.getAssetModelId(), audit.getContainerAssetId())
                    .orElse(null);
            BigDecimal current = balance == null ? BigDecimal.ZERO : balance.getQuantity();
            BigDecimal delta = observed.subtract(current);
            if (delta.signum() != 0)
                stock.adjust(
                        principal,
                        row.getAssetModelId(),
                        audit.getContainerAssetId(),
                        delta,
                        StockMovementReason.AUDIT_ADJUSTMENT,
                        reason,
                        auditId);
        }
        consumables.save(new AuditConsumableObservation(
                UUID.randomUUID(),
                principal.organizationId(),
                auditId,
                expectedId,
                status,
                observed,
                operationId,
                principal.userId(),
                clock.instant()));
        if (status == AuditConsumableStatus.MISSING_LOW)
            findings.save(new AuditFinding(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    auditId,
                    null,
                    AuditFindingType.MISSING,
                    reason,
                    json(Map.of("expectedId", expectedId)),
                    principal.userId(),
                    clock.instant()));
        return view(principal.organizationId(), audit);
    }

    @Transactional
    public ContainerAuditView complete(
            TarpeistoPrincipal principal,
            UUID auditId,
            UUID operationId,
            String finalCode,
            boolean confirmMissing,
            Boolean sealConfirmed) {
        authorizeOperator(principal);
        ContainerAudit audit = activeAudit(principal, auditId);
        if (!recordOperation(
                principal, audit, operationId, "COMPLETE", fingerprint(finalCode, confirmMissing, sealConfirmed)))
            return view(principal.organizationId(), audit);
        Asset target = requireAssetCode(principal.organizationId(), finalCode);
        if (!target.getId().equals(audit.getContainerAssetId()))
            throw new ValidationFailedException("Rescan the same container to complete this audit.");
        if (target.isSealable() && !Boolean.TRUE.equals(sealConfirmed))
            throw new ValidationFailedException(
                    "Confirm that a seal was applied before completing this sealable container audit.");
        List<AuditExpectedRequirement> unmet = unmet(principal.organizationId(), audit);
        if (!unmet.isEmpty() && !confirmMissing)
            throw new ValidationFailedException("Confirm remaining expected contents as missing before completion.");
        for (AuditExpectedRequirement row : unmet)
            findings.save(new AuditFinding(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    auditId,
                    row.getSpecificAssetId(),
                    AuditFindingType.MISSING,
                    null,
                    json(Map.of("expectedId", row.getId())),
                    principal.userId(),
                    clock.instant()));
        List<AuditScan> active = activeScans(principal.organizationId(), auditId);
        for (AuditScan scan : active) {
            if (scan.getOutcome() == AuditScanOutcome.EXTRA || scan.getOutcome() == AuditScanOutcome.MISPLACED)
                addFinding(
                        principal,
                        auditId,
                        scan.getAssetId(),
                        scan.getOutcome() == AuditScanOutcome.EXTRA
                                ? AuditFindingType.UNEXPECTED
                                : AuditFindingType.MISPLACED,
                        "Unexpected direct content.",
                        Map.of("scanId", scan.getId(), "scanContext", scan.getContextSnapshot()));
            if (!isManifestAsset(principal.organizationId(), audit, scan.getAssetId()))
                addFinding(
                        principal,
                        auditId,
                        scan.getAssetId(),
                        AuditFindingType.UNEXPECTED,
                        "This asset was not on the checkout manifest.",
                        Map.of("eventManifest", false, "scanContext", scan.getContextSnapshot()));
        }
        reconcileManifest(principal, audit);
        // Missing direct contents are detached; verified observations become the current physical placement.
        Set<UUID> presentIds = active.stream().map(AuditScan::getAssetId).collect(java.util.stream.Collectors.toSet());
        for (Asset previous : assets.findAllByOrganizationIdAndParentContainerAssetIdOrderByUnitNumberAsc(
                principal.organizationId(), audit.getContainerAssetId()))
            if (!presentIds.contains(previous.getId())) previous.moveTo(null, null, clock.instant());
        for (AuditScan scan : active) {
            Asset asset = assets.findByIdAndOrganizationId(scan.getAssetId(), principal.organizationId())
                    .orElseThrow();
            requireSafePlacement(principal.organizationId(), asset, audit.getContainerAssetId());
            asset.moveTo(null, audit.getContainerAssetId(), clock.instant());
        }
        boolean hasFindings = !findings.findAllByOrganizationIdAndAuditIdOrderById(principal.organizationId(), auditId)
                .isEmpty();
        audit.complete(
                principal.userId(),
                hasFindings ? AuditCompletionOutcome.FINDINGS : AuditCompletionOutcome.CLEAN,
                target.getPublicCode(),
                sealConfirmed,
                clock.instant());
        if (target.isSealable()) {
            target.applySeal(clock.instant());
            sealHistory.save(new AssetSealHistory(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    target.getId(),
                    SealHistoryAction.APPLIED,
                    audit.getId(),
                    null,
                    principal.userId(),
                    clock.instant()));
        }
        AuditTask task = task(principal, audit.getAuditTaskId());
        task.complete();
        if (!hasFindings) {
            Set<UUID> verifiedIds = new HashSet<>(presentIds);
            verifiedIds.add(audit.getContainerAssetId());
            for (UUID assetId : verifiedIds)
                assets.findByIdAndOrganizationId(assetId, principal.organizationId())
                        .ifPresent(asset -> {
                            asset.recordVerification(audit.getId(), clock.instant());
                            verificationHistory.save(new AssetVerificationHistory(
                                    UUID.randomUUID(),
                                    principal.organizationId(),
                                    assetId,
                                    audit.getId(),
                                    VerificationState.VERIFIED,
                                    clock.instant(),
                                    principal.userId()));
                        });
            for (UUID assetId : verifiedIds)
                manifestAssets
                        .findAllByOrganizationIdAndManifestIdOrderById(
                                principal.organizationId(),
                                batches.findById(audit.getAuditBatchId())
                                        .orElseThrow()
                                        .getManifestId())
                        .stream()
                        .filter(item -> item.getAssetId().equals(assetId) && item.getReturnedAt() != null)
                        .forEach(item -> item.releaseAfterAudit(clock.instant()));
        }
        returnStates.confirmReviewedAudit(principal, auditId);
        returnStates.recalculateForBatch(principal.organizationId(), audit.getAuditBatchId());
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "AUDIT_COMPLETED",
                "CONTAINER_AUDIT",
                auditId,
                Map.of("outcome", audit.getCompletionOutcome().name()));
        return view(principal.organizationId(), audit);
    }

    private AuditScanOutcome match(UUID org, UUID auditId, Asset asset, List<AuditScan> active) {
        ContainerAudit audit = audits.findByOrganizationIdAndId(org, auditId).orElseThrow();
        if (requirements
                .findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(org, asset.getId())
                .filter(r -> !r.getContainerAssetId().equals(audit.getContainerAssetId()))
                .isPresent()) return AuditScanOutcome.MISPLACED;
        List<AuditExpectedRequirement> rows =
                expected.findAllByOrganizationIdAndAuditIdOrderByDisplayOrderAsc(org, auditId);
        if (rows.stream()
                .anyMatch(r -> asset.getId().equals(r.getSpecificAssetId()) && !hasActive(active, asset.getId())))
            return AuditScanOutcome.EXPECTED_EXACT;
        long capacity = rows.stream()
                .filter(r -> r.getType() == PackingRequirementType.MODEL_QUANTITY
                        && asset.getAssetModelId().equals(r.getAssetModelId()))
                .mapToLong(r -> r.getRequiredQuantity().longValue())
                .sum();
        long filled = active.stream()
                .filter(s -> s.getOutcome() == AuditScanOutcome.EXPECTED_MODEL)
                .filter(s -> assets.findByIdAndOrganizationId(s.getAssetId(), org)
                        .map(a -> a.getAssetModelId().equals(asset.getAssetModelId()))
                        .orElse(false))
                .count();
        if (filled < capacity) return AuditScanOutcome.EXPECTED_MODEL;
        return rows.stream().anyMatch(r -> asset.getId().equals(r.getSpecificAssetId()))
                ? AuditScanOutcome.MISPLACED
                : AuditScanOutcome.EXTRA;
    }

    private List<AuditScan> activeScans(UUID organizationId, UUID auditId) {
        return scans.findAllByOrganizationIdAndAuditIdOrderByScannedAtAsc(organizationId, auditId).stream()
                .filter(scan -> scan.getUndoneAt() == null)
                .toList();
    }

    private void rematch(UUID org, UUID auditId) {
        List<AuditScan> counted = new java.util.ArrayList<>();
        for (AuditScan scan : activeScans(org, auditId)) {
            Asset asset =
                    assets.findByIdAndOrganizationId(scan.getAssetId(), org).orElseThrow();
            scan.matchAs(match(org, auditId, asset, counted));
            counted.add(scan);
        }
    }

    private boolean isManifestAsset(UUID organizationId, ContainerAudit audit, UUID assetId) {
        UUID manifestId =
                batches.findById(audit.getAuditBatchId()).orElseThrow().getManifestId();
        return manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(organizationId, manifestId).stream()
                .anyMatch(item -> item.getAssetId().equals(assetId));
    }

    private boolean hasActive(List<AuditScan> active, UUID assetId) {
        return active.stream().anyMatch(s -> s.getAssetId().equals(assetId));
    }

    private List<AuditExpectedRequirement> unmet(UUID org, ContainerAudit audit) {
        List<AuditScan> active = activeScans(org, audit.getId());
        Set<UUID> ids = active.stream().map(AuditScan::getAssetId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Long> modelCounts = new HashMap<>();
        for (AuditScan s : active)
            if (s.getOutcome() == AuditScanOutcome.EXPECTED_MODEL)
                assets.findByIdAndOrganizationId(s.getAssetId(), org)
                        .ifPresent(a -> modelCounts.merge(a.getAssetModelId(), 1L, Long::sum));
        Map<UUID, AuditConsumableObservation> observed =
                consumables.findAllByOrganizationIdAndAuditId(org, audit.getId()).stream()
                        .collect(java.util.stream.Collectors.toMap(AuditConsumableObservation::getExpectedId, o -> o));
        return expected.findAllByOrganizationIdAndAuditIdOrderByDisplayOrderAsc(org, audit.getId()).stream()
                .filter(r -> r.getType() == PackingRequirementType.SPECIFIC_ASSET
                        ? !ids.contains(r.getSpecificAssetId())
                        : r.getType() == PackingRequirementType.MODEL_QUANTITY
                                ? modelCounts.getOrDefault(r.getAssetModelId(), 0L)
                                        < r.getRequiredQuantity().longValue()
                                : !consumableSatisfied(r, observed.get(r.getId())))
                .toList();
    }

    private void unlockParents(UUID org, UUID childTask) {
        for (AuditTaskDependency dependency :
                dependencies.findAllByOrganizationIdAndIdDependsOnTaskId(org, childTask)) {
            AuditTask parent = tasks.findByOrganizationIdAndId(
                            org, dependency.getId().getTaskId())
                    .orElseThrow();
            List<AuditTaskDependency> all =
                    dependencies.findAllByOrganizationIdAndIdTaskIdIn(org, List.of(parent.getId()));
            boolean done = all.stream()
                    .allMatch(d -> audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(
                                    org, d.getId().getDependsOnTaskId())
                            .map(a -> a.getState() == ContainerAuditState.COMPLETED
                                    && a.getCompletionOutcome() == AuditCompletionOutcome.CLEAN)
                            .orElse(false));
            if (done) parent.markReady();
        }
    }

    private ContainerAudit activeAudit(TarpeistoPrincipal p, UUID auditId) {
        lockOrganization(p);
        ContainerAudit a = audits.findByOrganizationIdAndId(p.organizationId(), auditId)
                .orElseThrow(() -> new NotFoundException("Audit not found."));
        return a;
    }

    private AuditTask task(TarpeistoPrincipal p, UUID taskId) {
        return tasks.findByOrganizationIdAndId(p.organizationId(), taskId)
                .orElseThrow(() -> new NotFoundException("Audit task not found."));
    }

    private Asset requireAssetCode(UUID org, String raw) {
        AssetCodeValidation v = AssetCode.validate(raw);
        if (!(v instanceof AssetCodeValidation.Valid valid)) throw new ValidationFailedException("Invalid asset code.");
        return assets.findByOrganizationIdAndPublicCode(org, valid.code().value())
                .orElseThrow(() -> new ValidationFailedException("Unknown asset code."));
    }

    private boolean recordOperation(
            TarpeistoPrincipal p, ContainerAudit audit, UUID op, String action, String fingerprint) {
        Optional<AuditOperation> old =
                operations.findByOrganizationIdAndAuditIdAndOperationId(p.organizationId(), audit.getId(), op);
        if (old.isPresent()) {
            if (!old.get().getAction().equals(action)
                    || !old.get().getFingerprint().equals(fingerprint)) throw new AuditMutationConflictException();
            return false;
        }
        if (audit.getState() != ContainerAuditState.IN_PROGRESS || !audit.isCurrentAttempt())
            throw new ValidationFailedException("Completed audits are immutable.");
        operations.save(new AuditOperation(
                UUID.randomUUID(), p.organizationId(), audit.getId(), op, action, fingerprint, clock.instant()));
        return true;
    }

    private void lockOrganization(TarpeistoPrincipal principal) {
        organizations
                .findWithLockById(principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private String fingerprint(Object... values) {
        try {
            byte[] bytes =
                    mapper.writeValueAsString(Arrays.asList(values)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireSafePlacement(UUID org, Asset asset, UUID containerId) {
        Set<UUID> visited = new HashSet<>();
        UUID current = containerId;
        while (current != null && visited.add(current)) {
            if (current.equals(asset.getId()))
                throw new ValidationFailedException("A container cannot contain itself or an ancestor.");
            current =
                    assets.findByIdAndOrganizationId(current, org).orElseThrow().getParentContainerAssetId();
        }
    }

    private boolean consumableSatisfied(AuditExpectedRequirement row, AuditConsumableObservation observation) {
        return observation != null
                && (observation.getStatus() == AuditConsumableStatus.CONFIRMED
                        || observation.getStatus() == AuditConsumableStatus.OBSERVED
                                && observation.getObservedQuantity().compareTo(row.getRequiredQuantity()) >= 0);
    }

    private void addFinding(
            TarpeistoPrincipal principal,
            UUID auditId,
            UUID assetId,
            AuditFindingType type,
            String note,
            Map<String, Object> detail) {
        findings.save(new AuditFinding(
                UUID.randomUUID(),
                principal.organizationId(),
                auditId,
                assetId,
                type,
                note,
                json(detail),
                principal.userId(),
                clock.instant()));
    }

    private void reconcileManifest(TarpeistoPrincipal principal, ContainerAudit current) {
        List<AuditTask> batchTasks = tasks.findAllByOrganizationIdAndAuditBatchIdOrderById(
                principal.organizationId(), current.getAuditBatchId());
        if (batchTasks.stream()
                .anyMatch(t -> !t.getId().equals(current.getAuditTaskId()) && t.getState() != AuditTaskState.COMPLETED))
            return;
        Set<UUID> found = new HashSet<>();
        for (ContainerAudit audit : audits.findAllByOrganizationIdAndAuditBatchIdOrderById(
                principal.organizationId(), current.getAuditBatchId())) {
            if (!audit.isCurrentAttempt()) continue;
            found.add(audit.getContainerAssetId());
            activeScans(principal.organizationId(), audit.getId()).forEach(s -> found.add(s.getAssetId()));
        }
        UUID manifestId =
                batches.findById(current.getAuditBatchId()).orElseThrow().getManifestId();
        for (CheckoutManifestAsset item :
                manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(principal.organizationId(), manifestId)) {
            if (found.contains(item.getAssetId()))
                item.markReturned(principal.userId(), UUID.randomUUID(), clock.instant());
            else if (item.getAuditReleasedAt() == null)
                addFinding(
                        principal,
                        current.getId(),
                        item.getAssetId(),
                        AuditFindingType.MISSING,
                        "This exact checkout-manifest asset was not found anywhere in the audit batch.",
                        Map.of("eventManifest", true, "manifestSnapshot", item.getAssetSnapshot()));
        }
    }

    private ContainerAuditView pendingView(UUID org, AuditTask task) {
        List<String> blocked = task.getState() == AuditTaskState.BLOCKED
                ? dependencies.findAllByOrganizationIdAndIdTaskIdIn(org, List.of(task.getId())).stream()
                        .map(d -> "Complete child audit " + d.getId().getDependsOnTaskId())
                        .toList()
                : List.of();
        return new ContainerAuditView(
                null,
                task.getId(),
                task.getAuditBatchId(),
                task.getContainerAssetId(),
                task.getState().name(),
                null,
                List.of(),
                List.of(),
                List.of(),
                blocked);
    }

    private ContainerAuditView view(UUID org, ContainerAudit audit) {
        List<AuditScan> all = scans.findAllByOrganizationIdAndAuditIdOrderByScannedAtAsc(org, audit.getId());
        List<AuditExpectedRequirement> rows =
                expected.findAllByOrganizationIdAndAuditIdOrderByDisplayOrderAsc(org, audit.getId());
        List<AuditExpectedRequirement> unmet = unmet(org, audit);
        return new ContainerAuditView(
                audit.getId(),
                audit.getAuditTaskId(),
                audit.getAuditBatchId(),
                audit.getContainerAssetId(),
                audit.getState().name(),
                audit.getCompletionOutcome() == null
                        ? null
                        : audit.getCompletionOutcome().name(),
                rows.stream()
                        .map(r -> new AuditExpectedRequirementView(
                                r.getId(),
                                r.getType().name(),
                                r.getAssetModelId(),
                                r.getSpecificAssetId(),
                                r.getRequiredQuantity(),
                                r.getDisplayOrder(),
                                r.getSnapshot(),
                                !unmet.contains(r)))
                        .toList(),
                all.stream()
                        .map(s -> new AuditScanView(
                                s.getId(),
                                s.getAssetId(),
                                assets.findByIdAndOrganizationId(s.getAssetId(), org)
                                        .map(Asset::getPublicCode)
                                        .orElse("Unknown"),
                                s.getOutcome().name(),
                                s.getScannedAt(),
                                s.getUndoneAt() != null,
                                s.getContextSnapshot()))
                        .toList(),
                findings.findAllByOrganizationIdAndAuditIdOrderById(org, audit.getId()).stream()
                        .map(f -> new AuditFindingView(
                                f.getId(), f.getAssetId(), f.getType().name(), f.getNote(), f.getDetail()))
                        .toList(),
                List.of());
    }

    private String requirementSnapshot(PackingRequirement r) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("type", r.getRequirementType().name());
        snapshot.put("requiredQuantity", r.getRequiredQuantity());
        Asset exact = r.getSpecificAssetId() == null
                ? null
                : assets.findByIdAndOrganizationId(r.getSpecificAssetId(), r.getOrganizationId())
                        .orElseThrow();
        UUID modelId = exact == null ? r.getAssetModelId() : exact.getAssetModelId();
        AssetModel model =
                models.findByIdAndOrganizationId(modelId, r.getOrganizationId()).orElseThrow();
        snapshot.put("assetModelId", modelId);
        snapshot.put("modelName", model.getName());
        if (model.getStockUnitLabel() != null) snapshot.put("stockUnitLabel", model.getStockUnitLabel());
        if (exact != null) {
            snapshot.put("specificAssetId", exact.getId());
            snapshot.put("assetName", assetName(exact, model));
            snapshot.put("assetCode", exact.getPublicCode());
        }
        return json(snapshot);
    }

    private String scanContext(UUID org, ContainerAudit audit, Asset asset) {
        AssetModel model =
                models.findByIdAndOrganizationId(asset.getAssetModelId(), org).orElseThrow();
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("modelName", model.getName());
        context.put("assetName", assetName(asset, model));
        context.put("assetCode", asset.getPublicCode());
        Optional<PackingRequirement> pinned =
                requirements.findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(org, asset.getId());
        UUID destinationId =
                pinned.map(PackingRequirement::getContainerAssetId).orElse(asset.getParentContainerAssetId());
        if (destinationId != null && !destinationId.equals(audit.getContainerAssetId())) {
            Asset destination =
                    assets.findByIdAndOrganizationId(destinationId, org).orElseThrow();
            context.put("destinationContainerId", destinationId);
            context.put(
                    "destinationContainerName",
                    assetName(
                            destination,
                            models.findByIdAndOrganizationId(destination.getAssetModelId(), org)
                                    .orElseThrow()));
            context.put("destinationContainerCode", destination.getPublicCode());
        }
        List<Map<String, Object>> suggestions = new java.util.ArrayList<>();
        if (pinned.isEmpty()) {
            for (AuditTask task : tasks.findAllByOrganizationIdAndAuditBatchIdOrderById(org, audit.getAuditBatchId())) {
                if (task.getContainerAssetId().equals(audit.getContainerAssetId())
                        || task.getState() == AuditTaskState.COMPLETED) continue;
                Optional<ContainerAudit> otherAudit =
                        audits.findByOrganizationIdAndAuditTaskIdAndCurrentAttemptTrue(org, task.getId());
                boolean needsModel = otherAudit
                        .map(other -> unmet(org, other).stream()
                                .anyMatch(r -> r.getType() == PackingRequirementType.MODEL_QUANTITY
                                        && asset.getAssetModelId().equals(r.getAssetModelId())))
                        .orElseGet(() -> requirements
                                .findAllByOrganizationIdAndContainerAssetIdOrderByDisplayOrderAsc(
                                        org, task.getContainerAssetId())
                                .stream()
                                .anyMatch(r -> !r.isArchived()
                                        && r.getRequirementType() == PackingRequirementType.MODEL_QUANTITY
                                        && asset.getAssetModelId().equals(r.getAssetModelId())));
                if (needsModel) {
                    Asset destination = assets.findByIdAndOrganizationId(task.getContainerAssetId(), org)
                            .orElseThrow();
                    suggestions.add(Map.of(
                            "containerId",
                            destination.getId(),
                            "name",
                            assetName(
                                    destination,
                                    models.findByIdAndOrganizationId(destination.getAssetModelId(), org)
                                            .orElseThrow()),
                            "code",
                            destination.getPublicCode()));
                }
            }
        }
        context.put("suggestions", suggestions);
        return json(context);
    }

    private String assetName(Asset asset, AssetModel model) {
        return asset.getIndividualName() == null
                ? model.getName() + " " + asset.getUnitNumber()
                : asset.getIndividualName();
    }

    private String json(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void authorizeOperator(TarpeistoPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
        if (p.role() == OrganizationRole.VIEWER)
            throw new AccessDeniedException("Operator, Deputy, or Owner role required.");
    }
}
