package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.StaleBookingVersionException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.Booking;
import io.kellermann.bigcontainers.model.BookingClaimType;
import io.kellermann.bigcontainers.model.BookingLine;
import io.kellermann.bigcontainers.model.BookingLineType;
import io.kellermann.bigcontainers.model.BookingReservationClaim;
import io.kellermann.bigcontainers.model.BookingReservationRevision;
import io.kellermann.bigcontainers.model.BookingStatus;
import io.kellermann.bigcontainers.model.ConsumableStock;
import io.kellermann.bigcontainers.model.PackingRequirement;
import io.kellermann.bigcontainers.model.PackingRequirementType;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.BookingLineRepository;
import io.kellermann.bigcontainers.repository.BookingRepository;
import io.kellermann.bigcontainers.repository.BookingReservationClaimRepository;
import io.kellermann.bigcontainers.repository.BookingReservationRevisionRepository;
import io.kellermann.bigcontainers.repository.CheckoutManifestAssetRepository;
import io.kellermann.bigcontainers.repository.ConsumableStockRepository;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import io.kellermann.bigcontainers.repository.PackingRequirementRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingReservationService {
    private final BookingRepository bookings;
    private final BookingLineRepository lines;
    private final BookingReservationRevisionRepository revisions;
    private final BookingReservationClaimRepository claims;
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final ConsumableStockRepository stocks;
    private final CheckoutManifestAssetRepository checkoutManifestAssets;
    private final PackingRequirementRepository requirements;
    private final OrganizationRepository organizations;
    private final BookingReservationCalculator calculator;
    private final BookingCapacityCalculator capacity;
    private final ActivityLogService activity;
    private final BookingHistoryService history;
    private final Clock clock;
    private final ObjectMapper mapper;

    public BookingReservationService(
            BookingRepository bookings,
            BookingLineRepository lines,
            BookingReservationRevisionRepository revisions,
            BookingReservationClaimRepository claims,
            AssetRepository assets,
            AssetModelRepository models,
            ConsumableStockRepository stocks,
            CheckoutManifestAssetRepository checkoutManifestAssets,
            PackingRequirementRepository requirements,
            OrganizationRepository organizations,
            BookingReservationCalculator calculator,
            BookingCapacityCalculator capacity,
            ActivityLogService activity,
            BookingHistoryService history,
            Clock clock,
            ObjectMapper mapper) {
        this.bookings = bookings;
        this.lines = lines;
        this.revisions = revisions;
        this.claims = claims;
        this.assets = assets;
        this.models = models;
        this.stocks = stocks;
        this.checkoutManifestAssets = checkoutManifestAssets;
        this.requirements = requirements;
        this.organizations = organizations;
        this.calculator = calculator;
        this.capacity = capacity;
        this.activity = activity;
        this.history = history;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public BookingReservationPreviewView preview(BigContainersPrincipal p, UUID id) {
        BookingService.auth(p);
        return evaluate(p.organizationId(), requireBooking(p.organizationId(), id), load(p.organizationId()));
    }

    @Transactional
    public BookingReservationPreviewView reserve(BigContainersPrincipal p, UUID id, long version) {
        BookingService.admin(p);
        lock(p.organizationId());
        Booking b = locked(p, id, version);
        if (b.getStatus() != BookingStatus.DRAFT)
            throw new ValidationFailedException("Only draft bookings can be reserved.");
        Inventory inventory = load(p.organizationId());
        BookingReservationPreviewView result = evaluate(p.organizationId(), b, inventory);
        if (!result.reservable()) return result;
        Map<String, Object> before = history.snapshot(b);
        b.reserve(clock.instant());
        appendRevision(p, b, "RESERVED", expand(p.organizationId(), b, inventory), result);
        bookings.flush();
        history.record(p, b, "RESERVED", before);
        activity.record(
                p.organizationId(),
                p.userId(),
                "BOOKING_RESERVED",
                "BOOKING",
                id,
                Map.of("revisionId", b.getCurrentRevisionId()));
        return new BookingReservationPreviewView(true, result.conflicts(), result.warnings(), b.getVersion());
    }

    @Transactional
    public void cancel(BigContainersPrincipal p, UUID id, long version) {
        BookingService.admin(p);
        lock(p.organizationId());
        Booking b = locked(p, id, version);
        if (b.getStatus() != BookingStatus.DRAFT && b.getStatus() != BookingStatus.RESERVED)
            throw new ValidationFailedException("Only draft or reserved bookings can be cancelled.");
        Map<String, Object> before = history.snapshot(b);
        appendRevision(p, b, "CANCELLED", List.of(), new BookingReservationPreviewView(true, List.of()));
        b.cancel(clock.instant());
        bookings.flush();
        history.record(p, b, "CANCELLED", before);
        activity.record(
                p.organizationId(),
                p.userId(),
                "BOOKING_CANCELLED",
                "BOOKING",
                id,
                Map.of("revisionId", b.getCurrentRevisionId()));
        recalculate(p);
    }
    /** Called below inventory workflows; it never calls those workflows back. Caller holds the organization lock. */
    @Transactional
    public void recalculate(BigContainersPrincipal p) {
        BookingService.admin(p);
        lock(p.organizationId());
        assets.flush();
        requirements.flush();
        Inventory inventory = load(p.organizationId());
        for (Booking b : bookings.findAllByOrganizationIdAndStatus(p.organizationId(), BookingStatus.RESERVED))
            if (b.getEndsAt().isAfter(clock.instant())) {
                Map<String, Object> before = history.snapshot(b);
                BookingReservationPreviewView result = evaluate(p.organizationId(), b, inventory);
                appendRevision(p, b, "RECALCULATED", expand(p.organizationId(), b, inventory), result);
                bookings.flush();
                history.record(p, b, "RECALCULATED", before);
                activity.record(
                        p.organizationId(),
                        p.userId(),
                        "BOOKING_RECALCULATED",
                        "BOOKING",
                        b.getId(),
                        Map.of("reservationStatus", b.getReservationStatus(), "conflicts", result.conflicts()));
            }
    }

    @Transactional(readOnly = true)
    public List<UUID> affectedBookings(UUID org, UUID containerId) {
        Set<UUID> result = new HashSet<>();
        Map<UUID, Booking> active = new HashMap<>();
        for (Booking b : bookings.findAllByOrganizationIdAndStatus(org, BookingStatus.RESERVED))
            if (b.getEndsAt().isAfter(clock.instant())) active.put(b.getCurrentRevisionId(), b);
        for (BookingReservationClaim c : claims.findActiveClaims(org))
            if (containerId.equals(c.getAssetId()) && active.containsKey(c.getRevisionId()))
                result.add(active.get(c.getRevisionId()).getId());
        return result.stream().sorted().toList();
    }

    private void appendRevision(
            BigContainersPrincipal p,
            Booking b,
            String action,
            List<BookingClaimCandidate> expanded,
            BookingReservationPreviewView result) {
        int number = revisions
                        .findAllByOrganizationIdAndBookingIdOrderByRevisionNumberAsc(p.organizationId(), b.getId())
                        .size()
                + 1;
        UUID revisionId = UUID.randomUUID();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("booking", history.snapshot(b));
        details.put("conflicts", result.conflicts());
        details.put("warnings", result.warnings());
        revisions.saveAndFlush(new BookingReservationRevision(
                revisionId,
                p.organizationId(),
                b.getId(),
                number,
                action,
                p.userId(),
                clock.instant(),
                mapper.writeValueAsString(details)));
        claims.saveAllAndFlush(expanded.stream()
                .map(c -> new BookingReservationClaim(
                        UUID.randomUUID(),
                        p.organizationId(),
                        revisionId,
                        c.type(),
                        c.assetId(),
                        c.assetModelId(),
                        c.consumableStockId(),
                        c.quantity(),
                        c.sourceBookingLineId(),
                        c.containerId(),
                        c.requirementId(),
                        mapper.writeValueAsString(c.snapshot())))
                .toList());
        b.applyRevision(revisionId, result.reservable(), clock.instant());
    }

    private BookingReservationPreviewView evaluate(UUID org, Booking b, Inventory inventory) {
        List<BookingClaimCandidate> candidates = expand(org, b, inventory);
        Map<UUID, Booking> otherByRevision = new HashMap<>();
        for (Booking other : bookings.findAllByOrganizationIdAndStatus(org, BookingStatus.RESERVED))
            if (!other.getId().equals(b.getId())) otherByRevision.put(other.getCurrentRevisionId(), other);
        List<BookingReservationClaim> active = claims.findActiveClaims(org).stream()
                .filter(c -> otherByRevision.containsKey(c.getRevisionId()))
                .toList();
        List<BookingConflictView> conflicts = new ArrayList<>(), warnings = new ArrayList<>();
        for (BookingLine line : activeLines(org, b.getId()))
            if (line.getAssetId() != null) {
                Asset selected = inventory.assets().get(line.getAssetId());
                AssetModel model = selected == null ? null : inventory.models().get(selected.getAssetModelId());
                if (model != null && (line.getLineType() == BookingLineType.CONTAINER) != model.isCanContainAssets())
                    conflicts.add(conflict(
                            "ASSET_TYPE_CHANGED",
                            "The asset's container capability changed; update this booking line.",
                            null,
                            line.getAssetId(),
                            null,
                            model.getId(),
                            null,
                            null,
                            BigDecimal.ONE,
                            BigDecimal.ZERO));
            }

        Set<UUID> hardIds = new HashSet<>(), containerIds = new HashSet<>();
        for (BookingClaimCandidate c : candidates)
            if (c.type() == BookingClaimType.ASSET || c.type() == BookingClaimType.FLEXIBLE_ASSET) {
                Asset a = inventory.assets().get(c.assetId());
                AssetModel m = a == null ? null : inventory.models().get(a.getAssetModelId());
                if (a == null
                        || !a.isActive()
                        || m == null
                        || m.isArchived()
                        || checkoutManifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                                org, c.assetId()))
                    conflicts.add(conflict(
                            "ASSET_UNAVAILABLE",
                            "A selected or required asset is inactive.",
                            null,
                            c.assetId(),
                            null,
                            a == null ? null : a.getAssetModelId(),
                            c.containerId(),
                            c.requirementId(),
                            BigDecimal.ONE,
                            BigDecimal.ZERO));
                if (c.type() == BookingClaimType.ASSET) hardIds.add(c.assetId());
                if (m != null && m.isCanContainAssets()) containerIds.add(c.assetId());
            }
        for (BookingReservationClaim c : active)
            if (c.getClaimType() == BookingClaimType.ASSET
                    && hardIds.contains(c.getAssetId())
                    && overlaps(b, otherByRevision.get(c.getRevisionId())))
                conflicts.add(conflict(
                        "ASSET_OVERLAP",
                        "This asset is held by "
                                + otherByRevision.get(c.getRevisionId()).getName() + ".",
                        otherByRevision.get(c.getRevisionId()).getId(),
                        c.getAssetId(),
                        null,
                        null,
                        c.getContainerId(),
                        c.getRequirementId(),
                        BigDecimal.ONE,
                        BigDecimal.ZERO));
        Map<UUID, BigDecimal> candidateDemand = modelDemand(candidates, inventory);
        Map<UUID, List<BookingCapacityCalculator.DemandWindow>> existingDemand = new HashMap<>();
        for (Booking other : otherByRevision.values()) {
            List<BookingClaimCandidate> otherClaims = active.stream()
                    .filter(c -> c.getRevisionId().equals(other.getCurrentRevisionId()))
                    .map(this::candidate)
                    .toList();
            for (Map.Entry<UUID, BigDecimal> demand :
                    modelDemand(otherClaims, inventory).entrySet())
                existingDemand
                        .computeIfAbsent(demand.getKey(), k -> new ArrayList<>())
                        .add(new BookingCapacityCalculator.DemandWindow(
                                other.getStartsAt(), other.getEndsAt(), demand.getValue()));
        }
        Map<UUID, BigDecimal> pool = new HashMap<>();
        for (Asset a : inventory.assets().values()) {
            AssetModel m = inventory.models().get(a.getAssetModelId());
            if (a.isActive()
                    && m != null
                    && !m.isArchived()
                    && !checkoutManifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(org, a.getId())
                    && !inventory.pins().containsKey(a.getId()))
                pool.merge(a.getAssetModelId(), BigDecimal.ONE, BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> demand : candidateDemand.entrySet()) {
            BigDecimal peak = capacity.peak(
                    b.getStartsAt(),
                    b.getEndsAt(),
                    demand.getValue(),
                    existingDemand.getOrDefault(demand.getKey(), List.of()));
            BigDecimal available = pool.getOrDefault(demand.getKey(), BigDecimal.ZERO);
            if (peak.compareTo(available) > 0)
                conflicts.add(conflict(
                        "MODEL_CAPACITY",
                        "Insufficient eligible capacity for "
                                + inventory.models().get(demand.getKey()).getName() + ".",
                        null,
                        null,
                        null,
                        demand.getKey(),
                        null,
                        null,
                        peak,
                        available));
        }
        Map<UUID, BigDecimal> requested =
                sum(candidates, BookingClaimType.CONSUMABLE, BookingClaimCandidate::consumableStockId);
        Map<UUID, BigDecimal> planned = new HashMap<>();
        for (BookingReservationClaim c : active)
            if (c.getClaimType() == BookingClaimType.CONSUMABLE)
                planned.merge(c.getConsumableStockId(), c.getQuantity(), BigDecimal::add);
        for (Map.Entry<UUID, BigDecimal> request : requested.entrySet()) {
            ConsumableStock stock = inventory.stocks().get(request.getKey());
            BigDecimal available = stock == null ? BigDecimal.ZERO : stock.getQuantity();
            BigDecimal required = request.getValue().add(planned.getOrDefault(request.getKey(), BigDecimal.ZERO));
            if (required.compareTo(available) > 0)
                conflicts.add(conflict(
                        "CONSUMABLE_ATP",
                        "Planned issues exceed stock at this source.",
                        null,
                        null,
                        request.getKey(),
                        stock == null ? null : stock.getAssetModelId(),
                        stock == null ? null : stock.getContainerAssetId(),
                        null,
                        required,
                        available));
            if (stock != null && inventory.models().get(stock.getAssetModelId()).isArchived())
                conflicts.add(conflict(
                        "STOCK_MODEL_UNAVAILABLE",
                        "The consumable model is archived.",
                        null,
                        null,
                        stock.getId(),
                        stock.getAssetModelId(),
                        stock.getContainerAssetId(),
                        null,
                        request.getValue(),
                        BigDecimal.ZERO));
            if (stock != null && stock.getContainerAssetId() != null) {
                UUID source = stock.getContainerAssetId();
                if (hasActiveCustodyAtOrAbove(org, inventory, source))
                    conflicts.add(conflict(
                            "SOURCE_CONTAINER_UNAVAILABLE",
                            "The stock source container is checked out or awaiting audit.",
                            null,
                            source,
                            stock.getId(),
                            stock.getAssetModelId(),
                            source,
                            null,
                            request.getValue(),
                            BigDecimal.ZERO));
                if (containerIds.contains(source))
                    conflicts.add(conflict(
                            "CONSUMABLE_ALREADY_CARRIED",
                            "This source is already carried inside the booked container.",
                            null,
                            null,
                            stock.getId(),
                            stock.getAssetModelId(),
                            source,
                            null,
                            request.getValue(),
                            available));
                for (BookingReservationClaim held : active)
                    if (held.getClaimType() == BookingClaimType.ASSET
                            && ancestorOrSame(inventory, source, held.getAssetId())
                            && overlaps(b, otherByRevision.get(held.getRevisionId())))
                        conflicts.add(conflict(
                                "SOURCE_CONTAINER_UNAVAILABLE",
                                "The stock source container is reserved by "
                                        + otherByRevision
                                                .get(held.getRevisionId())
                                                .getName() + ".",
                                otherByRevision.get(held.getRevisionId()).getId(),
                                held.getAssetId(),
                                stock.getId(),
                                stock.getAssetModelId(),
                                source,
                                null,
                                request.getValue(),
                                BigDecimal.ZERO));
                Asset sourceAsset = inventory.assets().get(source);
                if (sourceAsset == null
                        || !sourceAsset.isActive()
                        || inventory.models().get(sourceAsset.getAssetModelId()).isArchived())
                    conflicts.add(conflict(
                            "SOURCE_CONTAINER_UNAVAILABLE",
                            "The source container is inactive.",
                            null,
                            source,
                            stock.getId(),
                            stock.getAssetModelId(),
                            source,
                            null,
                            request.getValue(),
                            BigDecimal.ZERO));
            }
        }
        for (BookingReservationClaim c : active)
            if (c.getClaimType() == BookingClaimType.CONSUMABLE
                    && overlaps(b, otherByRevision.get(c.getRevisionId()))) {
                ConsumableStock stock = inventory.stocks().get(c.getConsumableStockId());
                if (stock != null && containerIds.contains(stock.getContainerAssetId()))
                    conflicts.add(conflict(
                            "SOURCE_CONTAINER_UNAVAILABLE",
                            "This container supplies consumables to "
                                    + otherByRevision.get(c.getRevisionId()).getName() + ".",
                            otherByRevision.get(c.getRevisionId()).getId(),
                            stock.getContainerAssetId(),
                            stock.getId(),
                            stock.getAssetModelId(),
                            stock.getContainerAssetId(),
                            null,
                            c.getQuantity(),
                            BigDecimal.ZERO));
            }
        for (PackingRequirement r : inventory.requirements())
            if (containerIds.contains(r.getContainerAssetId())
                    && r.getRequirementType() == PackingRequirementType.CONSUMABLE_QUANTITY) {
                ConsumableStock stock = inventory.stocks().values().stream()
                        .filter(s -> r.getContainerAssetId().equals(s.getContainerAssetId())
                                && r.getAssetModelId().equals(s.getAssetModelId()))
                        .findFirst()
                        .orElse(null);
                BigDecimal available = stock == null
                        ? BigDecimal.ZERO
                        : stock.getQuantity().subtract(planned.getOrDefault(stock.getId(), BigDecimal.ZERO));
                if (available.compareTo(r.getRequiredQuantity()) < 0)
                    conflicts.add(conflict(
                            "CONSUMABLE_REQUIREMENT",
                            "The container does not have its required consumable amount.",
                            null,
                            null,
                            stock == null ? null : stock.getId(),
                            r.getAssetModelId(),
                            r.getContainerAssetId(),
                            r.getId(),
                            r.getRequiredQuantity(),
                            available.max(BigDecimal.ZERO)));
            }
        for (BookingLine line : activeLines(org, b.getId()))
            if (line.getLineType() == BookingLineType.ASSET && inventory.pins().containsKey(line.getAssetId())) {
                PackingRequirement pin = inventory.pins().get(line.getAssetId());
                warnings.add(conflict(
                        "CONTAINER_INCOMPLETE",
                        "Booking this exact-required asset separately makes its required container incomplete.",
                        null,
                        line.getAssetId(),
                        null,
                        null,
                        pin.getContainerAssetId(),
                        pin.getId(),
                        BigDecimal.ONE,
                        BigDecimal.ZERO));
            }
        return new BookingReservationPreviewView(
                conflicts.isEmpty(), List.copyOf(conflicts), List.copyOf(warnings), b.getVersion());
    }

    private Map<UUID, BigDecimal> modelDemand(List<BookingClaimCandidate> list, Inventory inventory) {
        Map<UUID, BigDecimal> demand = new HashMap<>();
        for (BookingClaimCandidate c : list) {
            if (c.type() == BookingClaimType.MODEL_CAPACITY)
                demand.merge(c.assetModelId(), c.quantity(), BigDecimal::add);
            else if (c.type() == BookingClaimType.ASSET || c.type() == BookingClaimType.FLEXIBLE_ASSET) {
                Asset a = inventory.assets().get(c.assetId());
                AssetModel model = a == null ? null : inventory.models().get(a.getAssetModelId());
                if (a != null
                        && (c.type() == BookingClaimType.FLEXIBLE_ASSET
                                || a.isActive()
                                        && model != null
                                        && !model.isArchived()
                                        && !inventory.pins().containsKey(a.getId())))
                    demand.merge(a.getAssetModelId(), BigDecimal.ONE, BigDecimal::add);
            }
        }
        return demand;
    }

    private BookingClaimCandidate candidate(BookingReservationClaim c) {
        return new BookingClaimCandidate(
                c.getClaimType(),
                c.getAssetId(),
                c.getAssetModelId(),
                c.getConsumableStockId(),
                c.getQuantity(),
                c.getSourceBookingLineId());
    }

    private static Map<UUID, BigDecimal> sum(
            List<BookingClaimCandidate> list, BookingClaimType type, Function<BookingClaimCandidate, UUID> id) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (BookingClaimCandidate c : list)
            if (c.type() == type) result.merge(id.apply(c), c.quantity(), BigDecimal::add);
        return result;
    }

    private boolean ancestorOrSame(Inventory i, UUID child, UUID parent) {
        Set<UUID> seen = new HashSet<>();
        UUID next = child;
        while (next != null && seen.add(next)) {
            if (next.equals(parent)) return true;
            Asset a = i.assets().get(next);
            next = a == null ? null : a.getParentContainerAssetId();
        }
        return false;
    }

    private boolean hasActiveCustodyAtOrAbove(UUID organizationId, Inventory inventory, UUID containerAssetId) {
        Set<UUID> seen = new HashSet<>();
        UUID current = containerAssetId;
        while (current != null && seen.add(current)) {
            if (checkoutManifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                    organizationId, current)) {
                return true;
            }
            Asset asset = inventory.assets().get(current);
            current = asset == null ? null : asset.getParentContainerAssetId();
        }
        return false;
    }

    private List<BookingClaimCandidate> expand(UUID org, Booking b, Inventory i) {
        return calculator.expand(
                activeLines(org, b.getId()),
                i.assets().values(),
                i.requirements(),
                i.stocks().values(),
                i.models().values());
    }

    private List<BookingLine> activeLines(UUID org, UUID id) {
        return lines.findAllByOrganizationIdAndBookingIdAndArchivedAtIsNullOrderByCreatedAtAsc(org, id);
    }

    private Inventory load(UUID org) {
        Map<UUID, Asset> assetMap = new HashMap<>();
        for (Asset a : assets.findAllByOrganizationId(org)) assetMap.put(a.getId(), a);
        Map<UUID, AssetModel> modelMap = new HashMap<>();
        for (AssetModel m : models.findAllByOrganizationIdOrderByNameAsc(org)) modelMap.put(m.getId(), m);
        Map<UUID, ConsumableStock> stockMap = new HashMap<>();
        for (ConsumableStock s : stocks.findAllByOrganizationIdOrderByCreatedAtAsc(org)) stockMap.put(s.getId(), s);
        List<PackingRequirement> req = requirements.findAllByOrganizationIdAndArchivedAtIsNull(org);
        Map<UUID, PackingRequirement> pins = new HashMap<>();
        for (PackingRequirement r : req) if (r.getSpecificAssetId() != null) pins.put(r.getSpecificAssetId(), r);
        return new Inventory(assetMap, modelMap, stockMap, req, pins);
    }

    private static BookingConflictView conflict(
            String type,
            String message,
            UUID booking,
            UUID asset,
            UUID stock,
            UUID model,
            UUID container,
            UUID requirement,
            BigDecimal required,
            BigDecimal available) {
        return new BookingConflictView(
                type, message, booking, asset, stock, model, container, requirement, required, available);
    }

    private static boolean overlaps(Booking a, Booking b) {
        return a.getStartsAt().isBefore(b.getEndsAt()) && b.getStartsAt().isBefore(a.getEndsAt());
    }

    private Booking locked(BigContainersPrincipal p, UUID id, long version) {
        Booking b = bookings.findWithLockByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Booking not found."));
        if (b.getVersion() != version) throw new StaleBookingVersionException();
        return b;
    }

    private Booking requireBooking(UUID org, UUID id) {
        return bookings.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new NotFoundException("Booking not found."));
    }

    private void lock(UUID org) {
        organizations.findWithLockById(org).orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private record Inventory(
            Map<UUID, Asset> assets,
            Map<UUID, AssetModel> models,
            Map<UUID, ConsumableStock> stocks,
            List<PackingRequirement> requirements,
            Map<UUID, PackingRequirement> pins) {}
}
