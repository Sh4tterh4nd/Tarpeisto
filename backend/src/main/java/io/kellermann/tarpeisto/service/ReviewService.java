package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ReviewMutationConflictException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetRepair;
import io.kellermann.tarpeisto.model.AssetStateChange;
import io.kellermann.tarpeisto.model.AssetStateChangeType;
import io.kellermann.tarpeisto.model.AuditFinding;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.Condition;
import io.kellermann.tarpeisto.model.FindingResolution;
import io.kellermann.tarpeisto.model.FindingResolutionAction;
import io.kellermann.tarpeisto.model.LifecycleState;
import io.kellermann.tarpeisto.model.ManifestAssetAccounting;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepairRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.AssetStateChangeRepository;
import io.kellermann.tarpeisto.repository.AuditBatchRepository;
import io.kellermann.tarpeisto.repository.AuditFindingRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestAssetRepository;
import io.kellermann.tarpeisto.repository.ContainerAuditRepository;
import io.kellermann.tarpeisto.repository.FindingResolutionRepository;
import io.kellermann.tarpeisto.repository.ManifestAssetAccountingRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.repository.PackingRequirementRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deputy/Owner remediation, append-only by design. It never edits an audit finding or scan. */
@Service
public class ReviewService {
    private final AuditFindingRepository findings;
    private final FindingResolutionRepository resolutions;
    private final AssetRepository assets;
    private final AssetRepairRepository repairs;
    private final CheckoutManifestAssetRepository manifestAssets;
    private final ManifestAssetAccountingRepository accounting;
    private final OrganizationRepository organizations;
    private final BookingReturnStateService returnStates;
    private final ActivityLogService activity;
    private final Clock clock;
    private final AssetStateChangeRepository stateHistory;
    private final AssetModelRepository models;
    private final PackingRequirementRepository requirements;
    private final ContainerAuditRepository audits;
    private final AuditBatchRepository batches;
    private final BookingImpactService bookingImpact;
    private final AssetSealService seals;

    public ReviewService(
            AuditFindingRepository findings,
            FindingResolutionRepository resolutions,
            AssetRepository assets,
            AssetRepairRepository repairs,
            CheckoutManifestAssetRepository manifestAssets,
            ManifestAssetAccountingRepository accounting,
            OrganizationRepository organizations,
            BookingReturnStateService returnStates,
            ActivityLogService activity,
            AssetStateChangeRepository stateHistory,
            AssetModelRepository models,
            PackingRequirementRepository requirements,
            ContainerAuditRepository audits,
            AuditBatchRepository batches,
            BookingImpactService bookingImpact,
            AssetSealService seals,
            Clock clock) {
        this.findings = findings;
        this.resolutions = resolutions;
        this.assets = assets;
        this.repairs = repairs;
        this.manifestAssets = manifestAssets;
        this.accounting = accounting;
        this.organizations = organizations;
        this.returnStates = returnStates;
        this.activity = activity;
        this.clock = clock;
        this.stateHistory = stateHistory;
        this.models = models;
        this.requirements = requirements;
        this.audits = audits;
        this.batches = batches;
        this.bookingImpact = bookingImpact;
        this.seals = seals;
    }

    @Transactional(readOnly = true)
    public List<FindingReviewView> list(TarpeistoPrincipal principal, boolean unresolvedOnly) {
        requireReviewer(principal);
        return findings.findAllByOrganizationIdOrderByRecordedAtDesc(principal.organizationId()).stream()
                .filter(finding -> finding.getAuditId() != null
                        && audits.findByOrganizationIdAndId(principal.organizationId(), finding.getAuditId())
                                .map(audit ->
                                        audit.getState() == io.kellermann.tarpeisto.model.ContainerAuditState.COMPLETED)
                                .orElse(false))
                .map(finding -> toView(principal.organizationId(), finding))
                .filter(view -> !unresolvedOnly || !view.resolved())
                .toList();
    }

    @Transactional(readOnly = true)
    public FindingReviewView get(TarpeistoPrincipal principal, UUID findingId) {
        requireReviewer(principal);
        return toView(principal.organizationId(), finding(principal.organizationId(), findingId));
    }

    @Transactional
    public FindingReviewView resolve(
            TarpeistoPrincipal principal,
            UUID findingId,
            UUID operationId,
            FindingResolutionAction action,
            String note,
            UUID targetAssetId,
            UUID targetContainerId,
            String repairReference) {
        requireReviewer(principal);
        if (operationId == null || action == null)
            throw new ValidationFailedException("A resolution operation ID and action are required.");
        if (action == FindingResolutionAction.DISMISS && (note == null || note.isBlank()))
            throw new ValidationFailedException("Dismissal requires a reason.");
        organizations
                .findWithLockById(principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Organization not found."));
        AuditFinding finding = finding(principal.organizationId(), findingId);
        String fingerprint = fingerprint(action, note, targetAssetId, targetContainerId, repairReference);
        var byOperation = resolutions.findByOrganizationIdAndOperationId(principal.organizationId(), operationId);
        if (byOperation.isPresent()) {
            FindingResolution prior = byOperation.get();
            if (!prior.getFindingId().equals(findingId)
                    || !prior.getFingerprint().equals(fingerprint))
                throw new ReviewMutationConflictException(
                        "This resolution operation ID was already used with different data.");
            return toView(principal.organizationId(), finding);
        }
        if (resolutions
                .findByOrganizationIdAndFindingId(principal.organizationId(), findingId)
                .isPresent()) throw new ReviewMutationConflictException("This finding already has a final resolution.");
        var sourceAudit = audits.findByOrganizationIdAndId(principal.organizationId(), finding.getAuditId())
                .orElseThrow(() -> new NotFoundException("Audit not found."));
        if (sourceAudit.getState() != io.kellermann.tarpeisto.model.ContainerAuditState.COMPLETED)
            throw new ValidationFailedException("Complete the audit before reviewing its findings.");
        Asset asset = requireFindingAsset(principal.organizationId(), finding, action);
        if (targetAssetId != null && (asset == null || !targetAssetId.equals(asset.getId()))) {
            assets.findByIdAndOrganizationId(targetAssetId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Target asset not found."));
            throw new ValidationFailedException("The target asset must be the asset observed in this finding.");
        }
        validateApplicability(finding, action, asset, targetContainerId, repairReference);
        UUID sourceManifestId = batches.findByIdAndOrganizationId(
                        sourceAudit.getAuditBatchId(), principal.organizationId())
                .orElseThrow()
                .getManifestId();
        if (asset != null
                && manifestAssets
                        .findAllByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                                principal.organizationId(), asset.getId())
                        .stream()
                        .anyMatch(item -> sourceManifestId == null
                                || !item.getManifestId().equals(sourceManifestId)))
            throw new ValidationFailedException("This asset is in the custody of another event.");
        if (targetContainerId != null
                && manifestAssets
                        .findAllByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                                principal.organizationId(), targetContainerId)
                        .stream()
                        .anyMatch(item -> sourceManifestId == null
                                || !item.getManifestId().equals(sourceManifestId)))
            throw new ValidationFailedException("The target container is in the custody of another event.");
        Instant now = clock.instant();
        FindingResolution resolution = resolutions.save(new FindingResolution(
                UUID.randomUUID(),
                principal.organizationId(),
                findingId,
                action,
                operationId,
                fingerprint,
                blankToNull(note),
                targetAssetId,
                targetContainerId,
                principal.userId(),
                now));
        apply(
                principal,
                finding,
                asset,
                action,
                targetContainerId,
                repairReference,
                blankToNull(note),
                operationId,
                now);
        if (asset != null
                && (action == FindingResolutionAction.MARK_LOST || action == FindingResolutionAction.MARK_DESTROYED))
            formallyAccountForOpenCustody(principal, asset.getId(), resolution, action, now);
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "FINDING_RESOLVED",
                "AUDIT_FINDING",
                findingId,
                Map.of("resolutionId", resolution.getId(), "action", action.name()));
        returnStates.confirmReviewedAudit(principal, finding.getAuditId());
        returnStates.recalculateForAudit(principal.organizationId(), finding.getAuditId());
        repairs.flush();
        bookingImpact.changed(principal);
        return toView(principal.organizationId(), finding);
    }

    @Transactional
    public RepairView openRepair(TarpeistoPrincipal principal, UUID assetId, UUID sourceFindingId, String reference) {
        requireReviewer(principal);
        if (reference == null || reference.isBlank())
            throw new ValidationFailedException("Repair reference or description is required.");
        organizations.findWithLockById(principal.organizationId()).orElseThrow();
        Asset asset = assets.findWithLockByIdAndOrganizationId(assetId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        if (repairs.existsByOrganizationIdAndAssetIdAndClosedAtIsNull(principal.organizationId(), assetId))
            throw new ReviewMutationConflictException("This asset already has an open repair.");
        if (!asset.isActive()) throw new ValidationFailedException("Only an active asset can enter repair.");
        if (sourceFindingId != null) {
            AuditFinding source = finding(principal.organizationId(), sourceFindingId);
            if (!assetId.equals(source.getAssetId()) || source.getType() != AuditFindingType.DAMAGED)
                throw new ValidationFailedException("The source must be a damage finding for this asset.");
        }
        AssetRepair repair = repairs.save(new AssetRepair(
                UUID.randomUUID(),
                principal.organizationId(),
                asset.getId(),
                sourceFindingId,
                reference,
                principal.userId(),
                clock.instant()));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "REPAIR_OPENED",
                "ASSET_REPAIR",
                repair.getId(),
                Map.of("assetId", assetId));
        repairs.flush();
        bookingImpact.changed(principal);
        return repairView(repair);
    }

    @Transactional
    public RepairView closeRepair(TarpeistoPrincipal principal, UUID repairId, Condition condition) {
        requireReviewer(principal);
        organizations.findWithLockById(principal.organizationId()).orElseThrow();
        AssetRepair repair = repairs.findByIdAndOrganizationId(repairId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Repair not found."));
        if (condition == null) throw new ValidationFailedException("A resulting condition is required.");
        if (repair.getClosedAt() != null) throw new ReviewMutationConflictException("This repair is already closed.");
        Asset asset = assets.findWithLockByIdAndOrganizationId(repair.getAssetId(), principal.organizationId())
                .orElseThrow();
        repair.close(principal.userId(), condition, clock.instant());
        changeCondition(
                principal, asset, condition, "Repair closed: " + repair.getReferenceOrDescription(), clock.instant());
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "REPAIR_CLOSED",
                "ASSET_REPAIR",
                repairId,
                Map.of("assetId", asset.getId(), "condition", condition.name()));
        repairs.flush();
        bookingImpact.changed(principal);
        return repairView(repair);
    }

    @Transactional(readOnly = true)
    public List<RepairView> repairs(TarpeistoPrincipal principal, UUID assetId) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
        assets.findByIdAndOrganizationId(assetId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        return repairs
                .findAllByOrganizationIdAndAssetIdOrderByOpenedAtDesc(principal.organizationId(), assetId)
                .stream()
                .map(this::repairView)
                .toList();
    }

    private void apply(
            TarpeistoPrincipal principal,
            AuditFinding finding,
            Asset asset,
            FindingResolutionAction action,
            UUID targetContainerId,
            String repairReference,
            String note,
            UUID operationId,
            Instant now) {
        if (action == FindingResolutionAction.MARK_LOST)
            changeLifecycle(principal, asset, LifecycleState.LOST, note, now);
        if (action == FindingResolutionAction.MARK_DESTROYED)
            changeLifecycle(principal, asset, LifecycleState.DESTROYED, note, now);
        if (action == FindingResolutionAction.MARK_DAMAGED && asset.getCondition() != Condition.DAMAGED)
            changeCondition(principal, asset, Condition.DAMAGED, note, now);
        if (action == FindingResolutionAction.CREATE_REPAIR)
            openRepair(principal, asset.getId(), finding.getId(), repairReference);
        if (action == FindingResolutionAction.FOUND_AND_RETURNED) {
            if (asset.getLifecycleState() == LifecycleState.LOST)
                changeLifecycle(principal, asset, LifecycleState.ACTIVE, note, now);
            UUID manifestId = batches.findByIdAndOrganizationId(
                            audits.findByOrganizationIdAndId(principal.organizationId(), finding.getAuditId())
                                    .orElseThrow()
                                    .getAuditBatchId(),
                            principal.organizationId())
                    .orElseThrow()
                    .getManifestId();
            if (manifestId != null)
                manifestAssets
                        .findByOrganizationIdAndManifestIdAndAssetId(
                                principal.organizationId(), manifestId, asset.getId())
                        .ifPresent(item -> item.markReturned(principal.userId(), operationId, now));
        }
        if ((action == FindingResolutionAction.MOVE_TO_CORRECT_CONTAINER
                        || action == FindingResolutionAction.REASSIGN_CURRENT_CONTAINER)
                && targetContainerId != null) {
            validateContainer(principal.organizationId(), asset, targetContainerId);
            UUID previous = asset.getParentContainerAssetId();
            asset.moveTo(null, targetContainerId, now);
            if (previous != null) seals.invalidate(principal, previous, "Finding review moved direct contents.");
            seals.invalidate(principal, targetContainerId, "Finding review moved direct contents.");
            activity.record(
                    principal.organizationId(),
                    principal.userId(),
                    "ASSET_MOVED",
                    "ASSET",
                    asset.getId(),
                    Map.of(
                            "previousParentContainerAssetId",
                            String.valueOf(previous),
                            "parentContainerAssetId",
                            targetContainerId));
        }
    }

    private void validateApplicability(
            AuditFinding finding,
            FindingResolutionAction action,
            Asset asset,
            UUID targetContainerId,
            String repairReference) {
        if (!applicableActions(finding, asset).contains(action))
            throw new ValidationFailedException("This action does not apply to this finding and asset.");
        if (targetContainerId != null
                && action != FindingResolutionAction.MOVE_TO_CORRECT_CONTAINER
                && action != FindingResolutionAction.REASSIGN_CURRENT_CONTAINER)
            throw new ValidationFailedException("This action does not accept a target container.");
        if (repairReference != null && !repairReference.isBlank() && action != FindingResolutionAction.CREATE_REPAIR)
            throw new ValidationFailedException("This action does not accept a repair reference.");
        if (action == FindingResolutionAction.MOVE_TO_CORRECT_CONTAINER) {
            UUID correct = requirements
                    .findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(
                            finding.getOrganizationId(), asset.getId())
                    .map(io.kellermann.tarpeisto.model.PackingRequirement::getContainerAssetId)
                    .orElse(null);
            if (correct == null || !correct.equals(targetContainerId))
                throw new ValidationFailedException(
                        "Move to correct container must use the asset's exact-required destination.");
        }
        if (action == FindingResolutionAction.MARK_LOST && finding.getType() != AuditFindingType.MISSING)
            throw new ValidationFailedException("Only a missing finding can mark an asset lost.");
        if (action == FindingResolutionAction.MARK_DESTROYED && finding.getType() != AuditFindingType.DAMAGED)
            throw new ValidationFailedException("Only a damaged finding can mark an asset destroyed.");
        if (action == FindingResolutionAction.CREATE_REPAIR
                && (finding.getType() != AuditFindingType.DAMAGED
                        || repairReference == null
                        || repairReference.isBlank()))
            throw new ValidationFailedException(
                    "A damage finding and repair reference are required to create a repair.");
        if ((action == FindingResolutionAction.MOVE_TO_CORRECT_CONTAINER
                        || action == FindingResolutionAction.REASSIGN_CURRENT_CONTAINER)
                && targetContainerId == null) throw new ValidationFailedException("A target container is required.");
        if (action != FindingResolutionAction.DISMISS
                && asset == null
                && action != FindingResolutionAction.REPLACE_LABEL)
            throw new ValidationFailedException("This finding has no physical asset to resolve.");
        if (asset != null
                && asset.getLifecycleState() == LifecycleState.DESTROYED
                && action == FindingResolutionAction.FOUND_AND_RETURNED)
            throw new ValidationFailedException("A destroyed asset cannot be restored.");
    }

    private List<FindingResolutionAction> applicableActions(AuditFinding finding, Asset asset) {
        if (asset == null || asset.getLifecycleState() == LifecycleState.DESTROYED)
            return List.of(FindingResolutionAction.DISMISS);
        List<FindingResolutionAction> actions = switch (finding.getType()) {
            case MISSING ->
                List.of(
                        FindingResolutionAction.FOUND_AND_RETURNED,
                        FindingResolutionAction.MARK_LOST,
                        FindingResolutionAction.DISMISS);
            case DAMAGED ->
                List.of(
                        FindingResolutionAction.MARK_DAMAGED,
                        FindingResolutionAction.CREATE_REPAIR,
                        FindingResolutionAction.MARK_DESTROYED,
                        FindingResolutionAction.DISMISS);
            case MISPLACED, UNEXPECTED ->
                requirements
                                .findByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(
                                        finding.getOrganizationId(), asset.getId())
                                .isPresent()
                        ? List.of(
                                FindingResolutionAction.MOVE_TO_CORRECT_CONTAINER,
                                FindingResolutionAction.REASSIGN_CURRENT_CONTAINER,
                                FindingResolutionAction.DISMISS)
                        : List.of(FindingResolutionAction.REASSIGN_CURRENT_CONTAINER, FindingResolutionAction.DISMISS);
            case UNREADABLE_LABEL -> List.of(FindingResolutionAction.REPLACE_LABEL, FindingResolutionAction.DISMISS);
            case UNKNOWN_CODE -> List.of(FindingResolutionAction.DISMISS);
        };
        return actions.stream()
                .filter(action -> action == FindingResolutionAction.DISMISS
                        || action == FindingResolutionAction.MARK_LOST
                        || action == FindingResolutionAction.MARK_DESTROYED
                        || (action == FindingResolutionAction.FOUND_AND_RETURNED
                                && (asset.isActive() || asset.getLifecycleState() == LifecycleState.LOST))
                        || (asset.isActive()
                                && (action != FindingResolutionAction.CREATE_REPAIR
                                        || !repairs.existsByOrganizationIdAndAssetIdAndClosedAtIsNull(
                                                finding.getOrganizationId(), asset.getId()))))
                .toList();
    }

    private void validateContainer(UUID org, Asset asset, UUID containerId) {
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        Asset parent = assets.findWithLockByIdAndOrganizationId(containerId, org)
                .orElseThrow(() -> new NotFoundException("Target container not found."));
        if (!parent.isActive()
                || !models.findByIdAndOrganizationId(parent.getAssetModelId(), org)
                        .orElseThrow()
                        .isCanContainAssets())
            throw new ValidationFailedException("Choose an active container-capable asset.");
        for (Asset at = parent; at != null; ) {
            if (!visited.add(at.getId()) || at.getId().equals(asset.getId()))
                throw new ValidationFailedException("A container cannot contain itself or a descendant.");
            UUID next = at.getParentContainerAssetId();
            at = next == null
                    ? null
                    : assets.findByIdAndOrganizationId(next, org).orElseThrow();
        }
    }

    private void changeLifecycle(
            TarpeistoPrincipal principal, Asset asset, LifecycleState next, String reason, Instant now) {
        if (asset.getLifecycleState() == next) return;
        LifecycleState prior = asset.changeLifecycleState(next, now);
        stateHistory.save(new AssetStateChange(
                UUID.randomUUID(),
                principal.organizationId(),
                asset.getId(),
                AssetStateChangeType.LIFECYCLE,
                prior.name(),
                next.name(),
                reason,
                principal.userId(),
                now));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_LIFECYCLE_CHANGED",
                "ASSET",
                asset.getId(),
                Map.of("previous", prior.name(), "new", next.name()));
    }

    private void changeCondition(
            TarpeistoPrincipal principal, Asset asset, Condition next, String reason, Instant now) {
        if (asset.getCondition() == next) return;
        Condition prior = asset.changeCondition(next, now);
        stateHistory.save(new AssetStateChange(
                UUID.randomUUID(),
                principal.organizationId(),
                asset.getId(),
                AssetStateChangeType.CONDITION,
                prior.name(),
                next.name(),
                reason,
                principal.userId(),
                now));
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_CONDITION_CHANGED",
                "ASSET",
                asset.getId(),
                Map.of("previous", prior.name(), "new", next.name()));
    }

    private void formallyAccountForOpenCustody(
            TarpeistoPrincipal principal,
            UUID assetId,
            FindingResolution resolution,
            FindingResolutionAction action,
            Instant now) {
        LifecycleState state =
                action == FindingResolutionAction.MARK_LOST ? LifecycleState.LOST : LifecycleState.DESTROYED;
        for (var item : manifestAssets.findAllByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                principal.organizationId(), assetId)) {
            if (item.getReturnedAt() != null) continue;
            accounting.saveAndFlush(new ManifestAssetAccounting(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    item.getId(),
                    resolution.getId(),
                    state,
                    principal.userId(),
                    now));
            item.releaseThroughFormalAccounting(now);
        }
    }

    private Asset requireFindingAsset(UUID organizationId, AuditFinding finding, FindingResolutionAction action) {
        if (finding.getAssetId() == null) return null;
        return assets.findWithLockByIdAndOrganizationId(finding.getAssetId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Asset not found."));
    }

    private AuditFinding finding(UUID org, UUID findingId) {
        return findings.findByIdAndOrganizationId(findingId, org)
                .orElseThrow(() -> new NotFoundException("Finding not found."));
    }

    private FindingReviewView toView(UUID org, AuditFinding finding) {
        var resolution = resolutions.findByOrganizationIdAndFindingId(org, finding.getId());
        return new FindingReviewView(
                finding.getId(),
                finding.getAuditId(),
                finding.getAssetId(),
                finding.getType(),
                finding.getNote(),
                finding.getDetail(),
                finding.getRecordedAt(),
                resolution.isPresent(),
                resolution.map(FindingResolution::getAction).orElse(null),
                resolution.map(FindingResolution::getResolvedAt).orElse(null),
                applicableActions(
                        finding,
                        finding.getAssetId() == null
                                ? null
                                : assets.findByIdAndOrganizationId(finding.getAssetId(), org)
                                        .orElse(null)),
                audits.findByOrganizationIdAndId(org, finding.getAuditId())
                        .orElseThrow()
                        .getAuditTaskId(),
                audits.findByOrganizationIdAndId(org, finding.getAuditId())
                        .orElseThrow()
                        .getContainerAssetId());
    }

    private RepairView repairView(AssetRepair repair) {
        return new RepairView(
                repair.getId(),
                repair.getAssetId(),
                repair.getSourceFindingId(),
                repair.getReferenceOrDescription(),
                repair.getOpenedAt(),
                repair.getClosedAt(),
                repair.getResultingCondition());
    }

    private void requireReviewer(TarpeistoPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update((byte) (value == null ? 0 : 1));
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
