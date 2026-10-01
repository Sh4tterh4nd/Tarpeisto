package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.AuditScanOutcome;
import io.kellermann.tarpeisto.model.FindingResolution;
import io.kellermann.tarpeisto.model.FindingResolutionAction;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.AuditExpectedRequirementRepository;
import io.kellermann.tarpeisto.repository.AuditScanRepository;
import io.kellermann.tarpeisto.repository.FindingResolutionRepository;
import io.kellermann.tarpeisto.repository.JdbcPackingFindingReconciliationRepository;
import io.kellermann.tarpeisto.repository.JdbcPackingFindingReconciliationRepository.Candidate;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Appends only proven obsolete serialized packing resolutions; never grants verification or custody. */
@Service
public class PackingFindingReconciliationService {
    private static final int PAGE_SIZE = 100;
    private static final String REASON =
            "Automatically dismissed: current direct contents fully match current packing requirements.";
    private final JdbcPackingFindingReconciliationRepository candidates;
    private final PackingEvaluationService evaluation;
    private final AssetRepository assets;
    private final AuditExpectedRequirementRepository expected;
    private final AuditScanRepository scans;
    private final FindingResolutionRepository resolutions;
    private final OrganizationRepository organizations;
    private final BookingReturnStateService returnStates;
    private final BookingImpactService bookingImpact;
    private final ActivityLogService activity;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PackingFindingReconciliationService(
            JdbcPackingFindingReconciliationRepository candidates,
            PackingEvaluationService evaluation,
            AssetRepository assets,
            AuditExpectedRequirementRepository expected,
            AuditScanRepository scans,
            FindingResolutionRepository resolutions,
            OrganizationRepository organizations,
            BookingReturnStateService returnStates,
            BookingImpactService bookingImpact,
            ActivityLogService activity,
            EntityManager entityManager,
            ObjectMapper mapper,
            Clock clock) {
        this.candidates = candidates;
        this.evaluation = evaluation;
        this.assets = assets;
        this.expected = expected;
        this.scans = scans;
        this.resolutions = resolutions;
        this.organizations = organizations;
        this.returnStates = returnStates;
        this.bookingImpact = bookingImpact;
        this.activity = activity;
        this.entityManager = entityManager;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public Sweep sweep(TarpeistoPrincipal principal, String cursor) {
        requireManager(principal);
        UUID after = decode(principal.organizationId(), cursor);
        lock(principal.organizationId());
        entityManager.flush();
        List<Candidate> page = candidates.page(principal.organizationId(), null, after, PAGE_SIZE + 1);
        List<Candidate> inspected = page.subList(0, Math.min(PAGE_SIZE, page.size()));
        int dismissed = reconcile(principal, inspected, new HashMap<>());
        String next = page.size() > PAGE_SIZE
                ? encode(principal.organizationId(), inspected.getLast().findingId())
                : null;
        return new Sweep(inspected.size(), dismissed, next);
    }

    /** Collect mutations and evaluate once at the final outer transaction state, never nested intermediate states. */
    public void schedule(TarpeistoPrincipal principal, UUID containerId, UUID... exactAssetIds) {
        requireManager(principal);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Packing reconciliation requires the mutation transaction.");
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(this);
        if (pending != null && pending.running) return;
        if (pending == null) {
            pending = new Pending();
            TransactionSynchronizationManager.bindResource(this, pending);
            Pending registered = pending;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (readOnly || registered.running) return;
                    registered.running = true;
                    for (var entry : registered.containers.entrySet())
                        reconcileContainers(entry.getKey(), entry.getValue());
                    entityManager.flush();
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(
                            PackingFindingReconciliationService.this);
                }
            });
        }
        Set<UUID> containers = pending.containers.computeIfAbsent(principal, ignored -> new LinkedHashSet<>());
        if (containerId != null) containers.add(containerId);
        for (UUID assetId : exactAssetIds) {
            if (assetId != null)
                assets.findByIdAndOrganizationId(assetId, principal.organizationId())
                        .map(asset -> asset.getParentContainerAssetId())
                        .ifPresent(containers::add);
        }
    }

    private void reconcileContainers(TarpeistoPrincipal principal, Set<UUID> containers) {
        lock(principal.organizationId());
        entityManager.flush();
        Map<UUID, PackingEvaluationService.Evaluation> evaluations = new HashMap<>();
        for (UUID container : containers) {
            UUID after = null;
            while (true) {
                List<Candidate> page = candidates.page(principal.organizationId(), container, after, PAGE_SIZE);
                if (page.isEmpty()) break;
                reconcile(principal, page, evaluations);
                after = page.getLast().findingId();
                if (page.size() < PAGE_SIZE) break;
            }
        }
    }

    private int reconcile(
            TarpeistoPrincipal principal,
            List<Candidate> page,
            Map<UUID, PackingEvaluationService.Evaluation> evaluations) {
        Set<UUID> batches = new LinkedHashSet<>();
        int dismissed = 0;
        for (Candidate candidate : page) {
            if (candidate.sourceOperationId() != null
                    || !candidates.eligibleSource(principal.organizationId(), candidate.containerId())
                    || candidates.inProgress(principal.organizationId(), candidate.containerId())) continue;
            PackingEvaluationService.Evaluation current = evaluations.computeIfAbsent(
                    candidate.containerId(), container -> evaluation.evaluate(principal, container, null));
            if (!current.preview().complete()
                    || current.inactiveDirectContents()
                    || !provenObsolete(principal, candidate, current)) continue;
            if (resolutions
                    .findByOrganizationIdAndFindingId(principal.organizationId(), candidate.findingId())
                    .isPresent()) continue;
            UUID operation = UUID.nameUUIDFromBytes(
                    ("packing-reconciliation:v1:" + candidate.findingId()).getBytes(StandardCharsets.UTF_8));
            resolutions.save(new FindingResolution(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    candidate.findingId(),
                    FindingResolutionAction.DISMISS,
                    operation,
                    operation.toString(),
                    REASON,
                    null,
                    null,
                    principal.userId(),
                    clock.instant()));
            activity.record(
                    principal.organizationId(),
                    principal.userId(),
                    "FINDING_RESOLVED",
                    "AUDIT_FINDING",
                    candidate.findingId(),
                    Map.of("action", "DISMISS", "note", REASON, "operationId", operation));
            batches.add(candidate.batchId());
            dismissed++;
        }
        if (dismissed > 0) {
            entityManager.flush();
            batches.forEach(batch -> returnStates.recalculateForBatch(principal.organizationId(), batch, false));
            bookingImpact.changed(principal);
        }
        return dismissed;
    }

    private boolean provenObsolete(
            TarpeistoPrincipal principal, Candidate candidate, PackingEvaluationService.Evaluation current) {
        JsonNode detail;
        UUID expectedId;
        UUID scanId;
        try {
            detail = mapper.readTree(candidate.detail());
            if (!detail.isObject() || detail.has("eventManifest")) return false;
            expectedId = linkedId(detail, "expectedId");
            scanId = linkedId(detail, "scanId");
        } catch (JacksonException | IllegalArgumentException malformed) {
            return false;
        }
        if (candidate.type() == AuditFindingType.MISSING) {
            if (expectedId == null) return false;
            return expected.findByIdAndOrganizationIdAndAuditId(
                            expectedId, principal.organizationId(), candidate.auditId())
                    .filter(row -> row.getType() == PackingRequirementType.SPECIFIC_ASSET
                            || row.getType() == PackingRequirementType.MODEL_QUANTITY)
                    .isPresent();
        }
        if (scanId == null || candidate.assetId() == null) return false;
        AuditScanOutcome outcome =
                candidate.type() == AuditFindingType.UNEXPECTED ? AuditScanOutcome.EXTRA : AuditScanOutcome.MISPLACED;
        return scans.findByIdAndOrganizationIdAndAuditId(scanId, principal.organizationId(), candidate.auditId())
                .filter(scan -> scan.getUndoneAt() == null
                        && scan.getOutcome() == outcome
                        && Objects.equals(scan.getAssetId(), candidate.assetId()))
                .flatMap(scan -> assets.findByIdAndOrganizationId(scan.getAssetId(), principal.organizationId()))
                .filter(asset -> !Objects.equals(asset.getParentContainerAssetId(), candidate.containerId())
                        || (asset.isActive() && current.matchedAssetIds().contains(asset.getId())))
                .isPresent();
    }

    private static UUID linkedId(JsonNode detail, String field) {
        JsonNode value = detail.get(field);
        return value != null && value.isString() ? UUID.fromString(value.asString()) : null;
    }

    private static String encode(UUID organizationId, UUID after) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ("packing-findings:v1:" + organizationId + ":" + after).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID decode(UUID organizationId, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = "packing-findings:v1:" + organizationId + ":";
            if (!decoded.startsWith(prefix)) throw new IllegalArgumentException();
            UUID after = UUID.fromString(decoded.substring(prefix.length()));
            if (!encode(organizationId, after).equals(cursor)) throw new IllegalArgumentException();
            return after;
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException("Invalid reconciliation cursor.");
        }
    }

    private void lock(UUID organizationId) {
        organizations
                .findWithLockById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private static void requireManager(TarpeistoPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
        principal.requirePermanent();
        if (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)
            throw new AccessDeniedException("Owner or Deputy role required.");
    }

    private static final class Pending {
        private final Map<TarpeistoPrincipal, Set<UUID>> containers = new LinkedHashMap<>();
        private boolean running;
    }

    public record Sweep(int inspectedCount, int dismissedCount, String nextCursor) {}
}
