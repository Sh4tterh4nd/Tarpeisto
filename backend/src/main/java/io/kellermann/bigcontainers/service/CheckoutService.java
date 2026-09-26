package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.document.CheckoutManifestDocument;
import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.StaleBookingVersionException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.AuditBatch;
import io.kellermann.bigcontainers.model.AuditCompletionOutcome;
import io.kellermann.bigcontainers.model.AuditTask;
import io.kellermann.bigcontainers.model.AuditTaskDependency;
import io.kellermann.bigcontainers.model.AuditTaskState;
import io.kellermann.bigcontainers.model.Booking;
import io.kellermann.bigcontainers.model.BookingClaimType;
import io.kellermann.bigcontainers.model.BookingReservationClaim;
import io.kellermann.bigcontainers.model.BookingStatus;
import io.kellermann.bigcontainers.model.CheckoutConsumableSemantics;
import io.kellermann.bigcontainers.model.CheckoutManifest;
import io.kellermann.bigcontainers.model.CheckoutManifestAsset;
import io.kellermann.bigcontainers.model.CheckoutManifestConsumable;
import io.kellermann.bigcontainers.model.CheckoutManifestOverride;
import io.kellermann.bigcontainers.model.CheckoutReturnOperation;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.AuditBatchRepository;
import io.kellermann.bigcontainers.repository.AuditTaskDependencyRepository;
import io.kellermann.bigcontainers.repository.AuditTaskRepository;
import io.kellermann.bigcontainers.repository.BookingLineRepository;
import io.kellermann.bigcontainers.repository.BookingRepository;
import io.kellermann.bigcontainers.repository.BookingReservationClaimRepository;
import io.kellermann.bigcontainers.repository.CheckoutManifestAssetRepository;
import io.kellermann.bigcontainers.repository.CheckoutManifestConsumableRepository;
import io.kellermann.bigcontainers.repository.CheckoutManifestOverrideRepository;
import io.kellermann.bigcontainers.repository.CheckoutManifestRepository;
import io.kellermann.bigcontainers.repository.CheckoutReturnOperationRepository;
import io.kellermann.bigcontainers.repository.ConsumableStockRepository;
import io.kellermann.bigcontainers.repository.ContainerAuditRepository;
import io.kellermann.bigcontainers.repository.LocationRepository;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import io.kellermann.bigcontainers.repository.PackingRequirementRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Checkout/check-in workflow. Immutable manifest values are never regenerated from mutable inventory. */
@Service
public class CheckoutService {
    private final BookingRepository bookings;
    private final BookingReservationClaimRepository claims;
    private final CheckoutManifestRepository manifests;
    private final CheckoutManifestAssetRepository manifestAssets;
    private final CheckoutManifestConsumableRepository manifestConsumables;
    private final CheckoutManifestOverrideRepository overrides;
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final OrganizationRepository organizations;
    private final AuditBatchRepository batches;
    private final AuditTaskRepository tasks;
    private final AuditTaskDependencyRepository dependencies;
    private final BookingReservationService reservations;
    private final ConsumableStockService stock;
    private final ActivityLogService activity;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final CheckoutReturnOperationRepository returnOperations;
    private final PackingRequirementRepository packingRequirements;
    private final BookingLineRepository bookingLines;
    private final ConsumableStockRepository stockBalances;
    private final LocationRepository locations;
    private final ContainerAuditRepository audits;

    public CheckoutService(
            BookingRepository bookings,
            BookingReservationClaimRepository claims,
            CheckoutManifestRepository manifests,
            CheckoutManifestAssetRepository manifestAssets,
            CheckoutManifestConsumableRepository manifestConsumables,
            CheckoutManifestOverrideRepository overrides,
            AssetRepository assets,
            AssetModelRepository models,
            OrganizationRepository organizations,
            AuditBatchRepository batches,
            AuditTaskRepository tasks,
            AuditTaskDependencyRepository dependencies,
            BookingReservationService reservations,
            ConsumableStockService stock,
            ActivityLogService activity,
            Clock clock,
            ObjectMapper mapper,
            CheckoutReturnOperationRepository returnOperations,
            PackingRequirementRepository packingRequirements,
            BookingLineRepository bookingLines,
            ConsumableStockRepository stockBalances,
            LocationRepository locations,
            ContainerAuditRepository audits) {
        this.bookings = bookings;
        this.claims = claims;
        this.manifests = manifests;
        this.manifestAssets = manifestAssets;
        this.manifestConsumables = manifestConsumables;
        this.overrides = overrides;
        this.assets = assets;
        this.models = models;
        this.organizations = organizations;
        this.batches = batches;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.reservations = reservations;
        this.stock = stock;
        this.activity = activity;
        this.clock = clock;
        this.mapper = mapper;
        this.returnOperations = returnOperations;
        this.packingRequirements = packingRequirements;
        this.bookingLines = bookingLines;
        this.stockBalances = stockBalances;
        this.locations = locations;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public CheckoutManifestView get(BigContainersPrincipal principal, UUID bookingId) {
        authorizeRead(principal);
        CheckoutManifest manifest = requireManifest(principal.organizationId(), bookingId);
        return view(principal.organizationId(), manifest);
    }

    @Transactional
    public CheckoutManifestView checkout(
            BigContainersPrincipal principal,
            UUID bookingId,
            long expectedVersion,
            UUID mutationId,
            String overrideReason,
            List<UUID> selectedAssetIds) {
        authorizeOperation(principal);
        UUID commandId = requireMutation(mutationId);
        organizations
                .findWithLockById(principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Organization not found."));
        Booking booking = bookings.findWithLockByIdAndOrganizationId(bookingId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Booking not found."));
        List<BookingReservationClaim> reserved =
                claims.findAllByOrganizationIdAndRevisionId(principal.organizationId(), booking.getCurrentRevisionId());
        List<UUID> selections = selectedAssetIds == null ? List.of() : selectedAssetIds;
        if (selections.stream().anyMatch(java.util.Objects::isNull))
            throw new ValidationFailedException("Selected assets cannot contain null identifiers.");
        String commandFingerprint =
                hash(bookingId + "|" + expectedVersion + "|" + (overrideReason == null ? "" : overrideReason.trim())
                        + "|" + selections.stream().map(UUID::toString).sorted().toList());
        CheckoutManifest replay = manifests
                .findByOrganizationIdAndMutationId(principal.organizationId(), commandId)
                .orElse(null);
        if (replay != null) {
            if (!replay.getBookingId().equals(bookingId)
                    || !replay.getFingerprint().equals(commandFingerprint)) {
                throw new ValidationFailedException(
                        "This checkout mutation id was already used for a different command.");
            }
            return view(principal.organizationId(), replay);
        }
        if (booking.getVersion() != expectedVersion) throw new StaleBookingVersionException();
        if (booking.getStatus() != BookingStatus.RESERVED)
            throw new ValidationFailedException("Only a reserved booking can be checked out.");
        var preview = reservations.preview(principal, bookingId);
        boolean hasOverride = overrideReason != null && !overrideReason.isBlank();
        if (hasOverride && principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)
            throw new AccessDeniedException("Only Owners and Deputies may record checkout overrides.");
        Map<UUID, List<Asset>> selectedByClaim = resolveSelections(principal, booking, reserved, selections);
        if (preview.conflicts().stream()
                .anyMatch(c -> !c.type().equals("CONSUMABLE_REQUIREMENT")
                        && !(c.type().equals("ASSET_UNAVAILABLE")
                                && reserved.stream()
                                        .anyMatch(r -> r.getClaimType() == BookingClaimType.FLEXIBLE_ASSET
                                                && java.util.Objects.equals(r.getAssetId(), c.assetId())
                                                && selectedByClaim.containsKey(r.getId())))))
            throw new ValidationFailedException("Checkout has hard availability conflicts that cannot be overridden.");
        if (preview.conflicts().stream().anyMatch(c -> c.type().equals("CONSUMABLE_REQUIREMENT")) && !hasOverride)
            throw new ValidationFailedException("A packing exception requires an Owner/Deputy override reason.");
        boolean missingExactPlacement = reserved.stream()
                .anyMatch(c -> c.getClaimType() == BookingClaimType.ASSET
                        && c.getRequirementId() != null
                        && !java.util.Objects.equals(
                                c.getContainerId(),
                                assets.findByIdAndOrganizationId(c.getAssetId(), principal.organizationId())
                                        .orElseThrow()
                                        .getParentContainerAssetId()));
        if (missingExactPlacement && !hasOverride)
            throw new ValidationFailedException(
                    "Recorded contents do not fulfill an exact packing requirement; an Owner/Deputy reason is required.");
        Instant now = clock.instant();
        Map<String, Object> frozenBooking = bookingSnapshot(booking);
        frozenBooking.put("checkoutActorName", principal.displayName());
        CheckoutManifest manifest = manifests.saveAndFlush(new CheckoutManifest(
                UUID.randomUUID(),
                principal.organizationId(),
                bookingId,
                booking.getCurrentRevisionId(),
                commandId,
                commandFingerprint,
                json(frozenBooking),
                principal.userId(),
                now));
        for (BookingReservationClaim claim : reserved) {
            if (claim.getClaimType() == BookingClaimType.ASSET
                    || claim.getClaimType() == BookingClaimType.FLEXIBLE_ASSET) {
                Asset asset = selectedByClaim.containsKey(claim.getId())
                        ? selectedByClaim.get(claim.getId()).getFirst()
                        : assets.findByIdAndOrganizationId(claim.getAssetId(), principal.organizationId())
                                .orElseThrow(() ->
                                        new ValidationFailedException("A reserved asset is no longer available."));
                if (selectedByClaim.containsKey(claim.getId())) {
                    Asset replaced = assets.findByIdAndOrganizationId(claim.getAssetId(), principal.organizationId())
                            .orElseThrow();
                    if (java.util.Objects.equals(replaced.getParentContainerAssetId(), claim.getContainerId())
                            && !manifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                                    principal.organizationId(), replaced.getId())) {
                        replaced.moveTo(null, null, now);
                        activity.record(
                                principal.organizationId(),
                                principal.userId(),
                                "CHECKOUT_ASSET_REPLACED",
                                "ASSET",
                                replaced.getId(),
                                Map.of("replacementAssetId", asset.getId()));
                    }
                    placeSelectedAsset(principal, asset, claim.getContainerId(), now);
                }
                if (!asset.isActive()
                        || manifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                                principal.organizationId(), asset.getId()))
                    throw new ValidationFailedException("A reserved asset is not available for checkout.");
                assertNoOverlappingExactHold(principal, booking, asset);
                manifestAssets.save(new CheckoutManifestAsset(
                        UUID.randomUUID(),
                        principal.organizationId(),
                        manifest.getId(),
                        asset.getId(),
                        claim.getSourceBookingLineId(),
                        claim.getContainerId(),
                        asset.getParentContainerAssetId(),
                        models.findByIdAndOrganizationId(asset.getAssetModelId(), principal.organizationId())
                                .orElseThrow()
                                .isCanContainAssets(),
                        json(checkoutAssetSnapshot(principal.organizationId(), asset, claim)),
                        json(containerSnapshot(principal.organizationId(), claim.getContainerId()))));
            } else if (claim.getClaimType() == BookingClaimType.CONSUMABLE
                    || claim.getClaimType() == BookingClaimType.CARRIED_CONSUMABLE) {
                CheckoutConsumableSemantics semantics = claim.getClaimType() == BookingClaimType.CONSUMABLE
                        ? CheckoutConsumableSemantics.SEPARATELY_ISSUED
                        : CheckoutConsumableSemantics.CARRIED_IN_CONTAINER;
                manifestConsumables.save(new CheckoutManifestConsumable(
                        UUID.randomUUID(),
                        principal.organizationId(),
                        manifest.getId(),
                        claim.getConsumableStockId(),
                        claim.getSourceBookingLineId(),
                        claim.getContainerId(),
                        claim.getQuantity(),
                        semantics,
                        json(stockSnapshot(principal.organizationId(), claim))));
                if (semantics == CheckoutConsumableSemantics.SEPARATELY_ISSUED)
                    stock.issueForCheckout(principal, claim.getConsumableStockId(), claim.getQuantity(), bookingId);
            } else if (claim.getClaimType() == BookingClaimType.MODEL_CAPACITY) {
                for (Asset asset : selectedByClaim.getOrDefault(claim.getId(), List.of())) {
                    placeSelectedAsset(principal, asset, claim.getContainerId(), now);
                    manifestAssets.save(new CheckoutManifestAsset(
                            UUID.randomUUID(),
                            principal.organizationId(),
                            manifest.getId(),
                            asset.getId(),
                            claim.getSourceBookingLineId(),
                            claim.getContainerId(),
                            asset.getParentContainerAssetId(),
                            false,
                            json(checkoutAssetSnapshot(principal.organizationId(), asset, claim)),
                            json(containerSnapshot(principal.organizationId(), claim.getContainerId()))));
                }
            }
        }
        if (overrideReason != null && !overrideReason.isBlank())
            overrides.save(new CheckoutManifestOverride(
                    UUID.randomUUID(),
                    principal.organizationId(),
                    manifest.getId(),
                    overrideReason,
                    principal.userId(),
                    now));
        booking.checkOut(now);
        bookings.flush();
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "BOOKING_CHECKED_OUT",
                "BOOKING",
                bookingId,
                Map.of("checkoutManifestId", manifest.getId()));
        return view(principal.organizationId(), manifest);
    }

    @Transactional
    public CheckoutManifestView checkInAsset(
            BigContainersPrincipal principal, UUID bookingId, UUID assetId, UUID mutationId) {
        authorizeOperation(principal);
        lockOrganization(principal);
        CheckoutManifest manifest = requireManifest(principal.organizationId(), bookingId);
        String commandFingerprint = hash("ASSET|" + bookingId + "|" + assetId);
        if (replayed(principal, manifest, mutationId, commandFingerprint))
            return view(principal.organizationId(), manifest);
        CheckoutManifestAsset item = manifestAssets
                .findByOrganizationIdAndManifestIdAndAssetId(principal.organizationId(), manifest.getId(), assetId)
                .orElseThrow(() -> new NotFoundException("Asset is not on this checkout manifest."));
        if (item.getReturnedAt() == null) {
            List<CheckoutManifestAsset> all = manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(
                    principal.organizationId(), manifest.getId());
            java.util.Set<UUID> descendants = subtree(all, assetId);
            for (CheckoutManifestAsset returned : all)
                if (descendants.contains(returned.getAssetId())) {
                    returned.markReturned(principal.userId(), mutationId, clock.instant());
                    if (!returned.isContainer() && !hasContainerAncestor(all, returned))
                        returned.releaseAfterAudit(clock.instant());
                }
            manifestAssets.flush();
        }
        createAuditTasksForReturnedContainers(principal, manifest, item);
        updateReturnedState(principal, bookingId, manifest);
        recordOperation(principal, manifest, mutationId, commandFingerprint);
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "BOOKING_ASSET_CHECKED_IN",
                "BOOKING",
                bookingId,
                Map.of("assetId", assetId));
        return view(principal.organizationId(), manifest);
    }

    @Transactional
    public CheckoutManifestView returnConsumable(
            BigContainersPrincipal principal,
            UUID bookingId,
            UUID manifestConsumableId,
            BigDecimal quantity,
            UUID mutationId,
            UUID destinationContainerAssetId,
            UUID destinationLocationId) {
        authorizeOperation(principal);
        lockOrganization(principal);
        if (quantity == null
                || quantity.signum() <= 0
                || quantity.stripTrailingZeros().scale() > 3)
            throw new ValidationFailedException("Return quantity must be positive with at most three decimals.");
        CheckoutManifest manifest = requireManifest(principal.organizationId(), bookingId);
        String commandFingerprint = hash("STOCK|" + bookingId + "|" + manifestConsumableId + "|"
                + quantity.stripTrailingZeros().toPlainString() + "|" + destinationContainerAssetId + "|"
                + destinationLocationId);
        if (replayed(principal, manifest, mutationId, commandFingerprint))
            return view(principal.organizationId(), manifest);
        CheckoutManifestConsumable line = manifestConsumables
                .findByIdAndOrganizationIdAndManifestId(
                        manifestConsumableId, principal.organizationId(), manifest.getId())
                .orElseThrow(() -> new NotFoundException("Consumable is not on this checkout manifest."));
        if (line.getSemantics() != CheckoutConsumableSemantics.SEPARATELY_ISSUED)
            throw new ValidationFailedException(
                    "Consumables carried in a returned container do not create an event-return movement.");
        try {
            line.addReturned(quantity);
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(e.getMessage());
        }
        stock.returnFromCheckout(
                principal, line.getStockId(), quantity, bookingId, destinationContainerAssetId, destinationLocationId);
        manifestConsumables.saveAndFlush(line);
        recordOperation(principal, manifest, mutationId, commandFingerprint);
        return view(principal.organizationId(), manifest);
    }

    @Transactional
    public CheckoutManifestView completeReturn(BigContainersPrincipal principal, UUID bookingId, UUID mutationId) {
        authorizeOperation(principal);
        lockOrganization(principal);
        CheckoutManifest manifest = requireManifest(principal.organizationId(), bookingId);
        String commandFingerprint = hash("COMPLETE|" + bookingId);
        if (replayed(principal, manifest, mutationId, commandFingerprint))
            return view(principal.organizationId(), manifest);
        if (manifestAssets
                .findAllByOrganizationIdAndManifestIdOrderById(principal.organizationId(), manifest.getId())
                .stream()
                .anyMatch(a -> a.getReturnedAt() == null))
            throw new ValidationFailedException(
                    "All physical assets must be returned before completing return accounting.");
        for (CheckoutManifestConsumable line : manifestConsumables.findAllByOrganizationIdAndManifestIdOrderById(
                principal.organizationId(), manifest.getId())) line.account(clock.instant());
        manifestConsumables.flush();
        updateReturnedState(principal, bookingId, manifest);
        recordOperation(principal, manifest, mutationId, commandFingerprint);
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "BOOKING_RETURN_ACCOUNTED",
                "BOOKING",
                bookingId,
                Map.of("checkoutManifestId", manifest.getId()));
        return view(principal.organizationId(), manifest);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(BigContainersPrincipal principal, UUID bookingId) {
        CheckoutManifestView view = get(principal, bookingId);
        List<String> lines = new ArrayList<>();
        lines.add("BIGCONTAINERS CHECKOUT MANIFEST");
        lines.add("Checked out: " + view.checkedOutAt() + " by " + value(view.bookingSnapshot(), "checkoutActorName")
                + " (" + view.checkedOutByUserId() + ")");
        lines.add("Event: " + value(view.bookingSnapshot(), "name") + " | " + value(view.bookingSnapshot(), "startsAt")
                + " - " + value(view.bookingSnapshot(), "endsAt"));
        lines.add("Client: " + value(view.bookingSnapshot(), "clientText"));
        lines.add("Venue: " + value(view.bookingSnapshot(), "venueText"));
        lines.add("Notes: " + value(view.bookingSnapshot(), "notes"));
        lines.add("ASSETS");
        String group = null;
        for (CheckoutManifestAssetView asset : view.assets().stream()
                .sorted(java.util.Comparator.comparing(
                        a -> value(a.snapshot(), "bookedContainerName").toString()))
                .toList()) {
            String nextGroup = value(asset.snapshot(), "bookedContainerName").toString();
            if (!nextGroup.equals(group)) {
                group = nextGroup;
                lines.add("BOOKED CONTAINER: " + (group.equals("-") ? "Individual assets" : group));
            }
            lines.add("[" + value(asset.snapshot(), "assetCode") + "] " + value(asset.snapshot(), "modelName") + " "
                    + assetName(asset.snapshot()) + " context="
                    + value(asset.containerSnapshot(), "modelName") + " "
                    + value(asset.containerSnapshot(), "individualName"));
        }
        lines.add("CONSUMABLES");
        for (CheckoutManifestConsumableView consumable : view.consumables())
            lines.add(consumable.semantics() + " " + consumable.quantity() + " "
                    + value(consumable.snapshot(), "stockUnitLabel") + " " + value(consumable.snapshot(), "modelName")
                    + " source=" + value(consumable.snapshot(), "sourceName"));
        for (String override : view.overrides()) lines.add("OVERRIDE: " + override);
        return CheckoutManifestDocument.render(lines);
    }

    private void createAuditTasksForReturnedContainers(
            BigContainersPrincipal principal, CheckoutManifest manifest, CheckoutManifestAsset returned) {
        List<CheckoutManifestAsset> all = manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(
                principal.organizationId(), manifest.getId());
        Map<UUID, List<CheckoutManifestAsset>> children = new LinkedHashMap<>();
        for (CheckoutManifestAsset item : all)
            if (item.getPhysicalParentContainerAssetId() != null)
                children.computeIfAbsent(item.getPhysicalParentContainerAssetId(), ignored -> new ArrayList<>())
                        .add(item);
        LinkedHashSet<UUID> candidateIds = new LinkedHashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(returned.getAssetId());
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst();
            if (!candidateIds.add(id)) continue;
            for (CheckoutManifestAsset child : children.getOrDefault(id, List.of())) queue.add(child.getAssetId());
        }
        List<UUID> containers = all.stream()
                .filter(a -> candidateIds.contains(a.getAssetId()) && a.isContainer())
                .map(CheckoutManifestAsset::getAssetId)
                .toList();
        if (containers.isEmpty()) return;
        AuditBatch batch = batches.findByOrganizationIdAndBookingId(principal.organizationId(), manifest.getBookingId())
                .orElseGet(() -> batches.save(new AuditBatch(
                        UUID.randomUUID(),
                        principal.organizationId(),
                        manifest.getBookingId(),
                        manifest.getId(),
                        principal.userId(),
                        clock.instant())));
        Map<UUID, AuditTask> existing = new LinkedHashMap<>();
        for (AuditTask task :
                tasks.findAllByOrganizationIdAndAuditBatchIdOrderById(principal.organizationId(), batch.getId()))
            existing.put(task.getContainerAssetId(), task);
        for (UUID container : containers)
            existing.computeIfAbsent(
                    container,
                    id -> tasks.save(new AuditTask(
                            UUID.randomUUID(),
                            principal.organizationId(),
                            batch.getId(),
                            id,
                            children.getOrDefault(id, List.of()).stream().anyMatch(CheckoutManifestAsset::isContainer)
                                    ? AuditTaskState.BLOCKED
                                    : AuditTaskState.READY,
                            clock.instant())));
        tasks.flush();
        for (UUID parent : containers)
            for (CheckoutManifestAsset child : children.getOrDefault(parent, List.of()))
                if (existing.containsKey(child.getAssetId()))
                    if (!dependencies.existsById(new AuditTaskDependency.Key(
                            existing.get(parent).getId(),
                            existing.get(child.getAssetId()).getId())))
                        dependencies.save(new AuditTaskDependency(
                                principal.organizationId(),
                                existing.get(parent).getId(),
                                existing.get(child.getAssetId()).getId()));
    }

    private void updateReturnedState(BigContainersPrincipal principal, UUID bookingId, CheckoutManifest manifest) {
        if (manifestAssets
                .findAllByOrganizationIdAndManifestIdOrderById(principal.organizationId(), manifest.getId())
                .stream()
                .allMatch(item -> item.getReturnedAt() != null)) {
            Booking booking = bookings.findWithLockByIdAndOrganizationId(bookingId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Booking not found."));
            boolean hasAudits = batches.findByOrganizationIdAndBookingId(principal.organizationId(), bookingId)
                    .isPresent();
            boolean accountingComplete = manifestConsumables
                    .findAllByOrganizationIdAndManifestIdOrderById(principal.organizationId(), manifest.getId())
                    .stream()
                    .filter(c -> c.getSemantics() == CheckoutConsumableSemantics.SEPARATELY_ISSUED)
                    .allMatch(c -> c.getAccountedAt() != null);
            if (booking.getStatus() == BookingStatus.COMPLETED) return;
            if (hasAudits) {
                AuditBatch batch = batches.findByOrganizationIdAndBookingId(principal.organizationId(), bookingId)
                        .orElseThrow();
                List<io.kellermann.bigcontainers.model.ContainerAudit> rows =
                        audits.findAllByOrganizationIdAndAuditBatchIdOrderById(
                                principal.organizationId(), batch.getId());
                if (rows.stream().anyMatch(a -> a.getCompletionOutcome() == AuditCompletionOutcome.FINDINGS))
                    booking.markReviewRequired(clock.instant());
                else if (accountingComplete
                        && tasks
                                .findAllByOrganizationIdAndAuditBatchIdOrderById(
                                        principal.organizationId(), batch.getId())
                                .stream()
                                .allMatch(t -> t.getState() == AuditTaskState.COMPLETED))
                    booking.completeReturn(clock.instant());
                else booking.markReturnedAuditsPending(clock.instant());
            } else if (accountingComplete) booking.completeReturn(clock.instant());
            bookings.flush();
        }
    }

    private CheckoutManifestView view(UUID org, CheckoutManifest manifest) {
        Booking booking = bookings.findByIdAndOrganizationId(manifest.getBookingId(), org)
                .orElseThrow(() -> new NotFoundException("Booking not found."));
        AuditBatch batch = batches.findByOrganizationIdAndBookingId(org, manifest.getBookingId())
                .orElse(null);
        List<AuditTask> taskRows =
                batch == null ? List.of() : tasks.findAllByOrganizationIdAndAuditBatchIdOrderById(org, batch.getId());
        List<AuditTaskDependency> dependencyRows = taskRows.isEmpty()
                ? List.of()
                : dependencies.findAllByOrganizationIdAndIdTaskIdIn(
                        org, taskRows.stream().map(AuditTask::getId).toList());
        List<AuditTaskView> auditTasks = taskRows.stream()
                .map(t -> new AuditTaskView(
                        t.getId(),
                        t.getContainerAssetId(),
                        t.getState().name(),
                        dependencyRows.stream()
                                .filter(d -> d.getId().getTaskId().equals(t.getId()))
                                .map(d -> d.getId().getDependsOnTaskId())
                                .toList()))
                .toList();
        return new CheckoutManifestView(
                manifest.getId(),
                manifest.getBookingId(),
                booking.getStatus(),
                manifest.getCheckedOutAt(),
                manifest.getCheckedOutByUserId(),
                map(manifest.getBookingSnapshot()),
                manifestAssets.findAllByOrganizationIdAndManifestIdOrderById(org, manifest.getId()).stream()
                        .map(a -> new CheckoutManifestAssetView(
                                a.getAssetId(),
                                a.getContainerAssetId(),
                                a.getPhysicalParentContainerAssetId(),
                                a.isContainer(),
                                map(a.getAssetSnapshot()),
                                map(a.getContainerSnapshot()),
                                a.getReturnedAt()))
                        .toList(),
                manifestConsumables.findAllByOrganizationIdAndManifestIdOrderById(org, manifest.getId()).stream()
                        .map(c -> new CheckoutManifestConsumableView(
                                c.getId(),
                                c.getQuantity(),
                                c.getReturnedQuantity(),
                                c.getConsumedQuantity(),
                                c.getAccountedAt(),
                                c.getSemantics(),
                                map(c.getSnapshot())))
                        .toList(),
                overrides.findAllByOrganizationIdAndManifestIdOrderById(org, manifest.getId()).stream()
                        .map(CheckoutManifestOverride::getReason)
                        .toList(),
                auditTasks,
                batch == null ? null : batch.getId());
    }

    private CheckoutManifest requireManifest(UUID org, UUID bookingId) {
        return manifests
                .findByOrganizationIdAndBookingId(org, bookingId)
                .orElseThrow(() -> new NotFoundException("Checkout manifest not found."));
    }

    private Map<String, Object> bookingSnapshot(Booking b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", b.getName());
        map.put("clientText", b.getClientText());
        map.put("venueText", b.getVenueText());
        map.put("notes", b.getNotes());
        map.put("startsAt", b.getStartsAt());
        map.put("endsAt", b.getEndsAt());
        return map;
    }

    private Map<String, Object> assetSnapshot(Asset a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("assetCode", a.getPublicCode());
        map.put("individualName", a.getIndividualName());
        map.put("unitNumber", a.getUnitNumber());
        map.put(
                "isContainer",
                models.findByIdAndOrganizationId(a.getAssetModelId(), a.getOrganizationId())
                        .map(AssetModel::isCanContainAssets)
                        .orElse(false));
        models.findByIdAndOrganizationId(a.getAssetModelId(), a.getOrganizationId())
                .ifPresent(m -> map.put("modelName", m.getName()));
        return map;
    }

    private Map<String, Object> checkoutAssetSnapshot(UUID org, Asset asset, BookingReservationClaim claim) {
        Map<String, Object> result = assetSnapshot(asset);
        if (claim.getSourceBookingLineId() != null)
            bookingLines
                    .findByIdAndOrganizationId(claim.getSourceBookingLineId(), org)
                    .filter(l -> l.getLineType() == io.kellermann.bigcontainers.model.BookingLineType.CONTAINER)
                    .flatMap(l -> assets.findByIdAndOrganizationId(l.getAssetId(), org))
                    .ifPresent(container -> {
                        result.put("bookedContainerName", container.getIndividualName());
                        result.put("bookedContainerCode", container.getPublicCode());
                    });
        return result;
    }

    private Map<String, Object> containerSnapshot(UUID org, UUID id) {
        return id == null
                ? Map.of()
                : assets.findByIdAndOrganizationId(id, org)
                        .map(this::assetSnapshot)
                        .orElse(Map.of());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not persist checkout snapshot.", e);
        }
    }

    private Map<String, Object> map(String value) {
        return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private static String hash(String source) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID requireMutation(UUID id) {
        if (id == null) throw new ValidationFailedException("A stable mutation id is required.");
        return id;
    }

    private void lockOrganization(BigContainersPrincipal p) {
        organizations
                .findWithLockById(p.organizationId())
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private boolean replayed(BigContainersPrincipal p, CheckoutManifest manifest, UUID mutation, String fingerprint) {
        CheckoutReturnOperation operation = returnOperations
                .findByIdAndOrganizationId(requireMutation(mutation), p.organizationId())
                .orElse(null);
        if (operation == null) return false;
        if (!operation.getFingerprint().equals(fingerprint))
            throw new ValidationFailedException("This return mutation id was already used for a different command.");
        return true;
    }

    private void recordOperation(
            BigContainersPrincipal p, CheckoutManifest manifest, UUID mutation, String fingerprint) {
        returnOperations.saveAndFlush(new CheckoutReturnOperation(
                mutation, p.organizationId(), manifest.getId(), fingerprint, p.userId(), clock.instant()));
    }

    private static java.util.Set<UUID> subtree(List<CheckoutManifestAsset> all, UUID root) {
        java.util.Set<UUID> result = new LinkedHashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst();
            if (!result.add(id)) continue;
            for (CheckoutManifestAsset item : all)
                if (id.equals(item.getPhysicalParentContainerAssetId())) queue.add(item.getAssetId());
        }
        return result;
    }

    private static boolean hasContainerAncestor(List<CheckoutManifestAsset> all, CheckoutManifestAsset item) {
        UUID parent = item.getPhysicalParentContainerAssetId();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        while (parent != null && seen.add(parent)) {
            UUID id = parent;
            CheckoutManifestAsset ancestor = all.stream()
                    .filter(a -> id.equals(a.getAssetId()))
                    .findFirst()
                    .orElse(null);
            if (ancestor == null) return false;
            if (ancestor.isContainer()) return true;
            parent = ancestor.getPhysicalParentContainerAssetId();
        }
        return false;
    }

    private Map<String, Object> stockSnapshot(UUID org, BookingReservationClaim claim) {
        Map<String, Object> result = new LinkedHashMap<>(map(claim.getSnapshot()));
        var balance = stockBalances
                .findByIdAndOrganizationId(claim.getConsumableStockId(), org)
                .orElseThrow(() -> new NotFoundException("Stock source not found."));
        AssetModel frozenModel =
                models.findByIdAndOrganizationId(balance.getAssetModelId(), org).orElseThrow();
        result.put("modelName", frozenModel.getName());
        result.put("stockUnitLabel", frozenModel.getStockUnitLabel());
        result.put("sourceContainerId", balance.getContainerAssetId());
        result.put("sourceLocationId", balance.getLocationId());
        result.put(
                "sourceName",
                balance.getContainerAssetId() == null
                        ? locations
                                .findByIdAndOrganizationId(balance.getLocationId(), org)
                                .orElseThrow()
                                .getName()
                        : assets.findByIdAndOrganizationId(balance.getContainerAssetId(), org)
                                .orElseThrow()
                                .getIndividualName());
        if (claim.getClaimType() == BookingClaimType.CARRIED_CONSUMABLE
                && balance.getQuantity().compareTo(claim.getQuantity()) != 0)
            throw new ValidationFailedException("Carried stock changed; refresh the reservation before checkout.");
        return result;
    }

    private Map<UUID, List<Asset>> resolveSelections(
            BigContainersPrincipal p, Booking booking, List<BookingReservationClaim> reserved, List<UUID> selected) {
        if (new java.util.HashSet<>(selected).size() != selected.size())
            throw new ValidationFailedException("Selected assets must be distinct.");
        List<Asset> remaining = new ArrayList<>();
        java.util.Set<UUID> fixed = reserved.stream()
                .filter(c -> c.getAssetId() != null)
                .map(BookingReservationClaim::getAssetId)
                .collect(java.util.stream.Collectors.toSet());
        for (UUID id : selected) {
            Asset asset = assets.findByIdAndOrganizationId(id, p.organizationId())
                    .orElseThrow(() -> new NotFoundException("Selected asset not found."));
            AssetModel model = models.findByIdAndOrganizationId(asset.getAssetModelId(), p.organizationId())
                    .orElseThrow();
            if (!asset.isActive()
                    || model.isArchived()
                    || model.isCanContainAssets()
                    || fixed.contains(id)
                    || packingRequirements.existsByOrganizationIdAndSpecificAssetIdAndArchivedAtIsNull(
                            p.organizationId(), id)
                    || manifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(p.organizationId(), id))
                throw new ValidationFailedException("A selected replacement asset is unavailable or pinned.");
            for (BookingReservationClaim held : claims.findActiveClaims(p.organizationId())) {
                if (held.getRevisionId().equals(booking.getCurrentRevisionId())
                        || held.getClaimType() != BookingClaimType.ASSET) continue;
                Booking other =
                        bookings.findAllByOrganizationIdAndStatus(p.organizationId(), BookingStatus.RESERVED).stream()
                                .filter(b -> held.getRevisionId().equals(b.getCurrentRevisionId()))
                                .findFirst()
                                .orElse(null);
                if (other != null
                        && booking.getStartsAt().isBefore(other.getEndsAt())
                        && other.getStartsAt().isBefore(booking.getEndsAt())
                        && isAncestor(p.organizationId(), held.getAssetId(), id))
                    throw new ValidationFailedException("A replacement is held by an overlapping booking.");
            }
            remaining.add(asset);
        }
        Map<UUID, List<Asset>> result = new LinkedHashMap<>();
        for (BookingReservationClaim claim : reserved)
            if (claim.getClaimType() == BookingClaimType.FLEXIBLE_ASSET) {
                Asset original = assets.findByIdAndOrganizationId(claim.getAssetId(), p.organizationId())
                        .orElseThrow();
                Asset replacement = remaining.stream()
                        .filter(a -> a.getAssetModelId().equals(original.getAssetModelId()))
                        .findFirst()
                        .orElse(null);
                if (replacement != null) {
                    result.put(claim.getId(), List.of(replacement));
                    remaining.remove(replacement);
                }
            }
        for (BookingReservationClaim claim : reserved)
            if (claim.getClaimType() == BookingClaimType.MODEL_CAPACITY) {
                List<Asset> chosen = remaining.stream()
                        .filter(a -> a.getAssetModelId().equals(claim.getAssetModelId()))
                        .limit(claim.getQuantity().intValueExact())
                        .toList();
                if (chosen.size() != claim.getQuantity().intValueExact())
                    throw new ValidationFailedException(
                            "Select exact replacement assets for each unresolved model quantity.");
                result.put(claim.getId(), chosen);
                remaining.removeAll(chosen);
            }
        if (!remaining.isEmpty())
            throw new ValidationFailedException("Selected assets exceed unresolved model quantities.");
        return result;
    }

    private void placeSelectedAsset(BigContainersPrincipal p, Asset asset, UUID container, Instant at) {
        if (container == null) return;
        UUID previousParent = asset.getParentContainerAssetId();
        asset.moveTo(null, container, at);
        activity.record(
                p.organizationId(),
                p.userId(),
                "CHECKOUT_ASSET_SELECTED",
                "ASSET",
                asset.getId(),
                Map.of(
                        "containerAssetId",
                        container,
                        "previousParent",
                        previousParent == null ? "" : previousParent.toString()));
    }

    private void assertNoOverlappingExactHold(BigContainersPrincipal p, Booking booking, Asset asset) {
        Map<UUID, Booking> others = new LinkedHashMap<>();
        for (Booking other : bookings.findAllByOrganizationIdAndStatus(p.organizationId(), BookingStatus.RESERVED))
            if (!other.getId().equals(booking.getId())
                    && booking.getStartsAt().isBefore(other.getEndsAt())
                    && other.getStartsAt().isBefore(booking.getEndsAt()))
                others.put(other.getCurrentRevisionId(), other);
        if (claims.findActiveClaims(p.organizationId()).stream()
                .anyMatch(c -> c.getClaimType() == BookingClaimType.ASSET
                        && others.containsKey(c.getRevisionId())
                        && isAncestor(p.organizationId(), c.getAssetId(), asset.getId())))
            throw new ValidationFailedException(
                    "An exact manifest asset is held by another booking; select an eligible replacement.");
    }

    private boolean isAncestor(UUID org, UUID ancestor, UUID id) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        while (id != null && seen.add(id)) {
            if (id.equals(ancestor)) return true;
            id = assets.findByIdAndOrganizationId(id, org)
                    .map(Asset::getParentContainerAssetId)
                    .orElse(null);
        }
        return false;
    }

    private static Object value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "-" : value;
    }

    private static Object assetName(Map<String, Object> snapshot) {
        return snapshot.get("individualName") == null
                ? "#" + value(snapshot, "unitNumber")
                : value(snapshot, "individualName");
    }

    private static void authorizeRead(BigContainersPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
    }

    private static void authorizeOperation(BigContainersPrincipal p) {
        authorizeRead(p);
        if (p.role() == OrganizationRole.VIEWER)
            throw new AccessDeniedException("Operator, Deputy, or Owner role required.");
    }
}
