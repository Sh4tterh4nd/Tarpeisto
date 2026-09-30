package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.StaleBookingVersionException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.Booking;
import io.kellermann.tarpeisto.model.BookingLine;
import io.kellermann.tarpeisto.model.BookingLineType;
import io.kellermann.tarpeisto.model.BookingStatus;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.BookingLineRepository;
import io.kellermann.tarpeisto.repository.BookingRepository;
import io.kellermann.tarpeisto.repository.ConsumableStockRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final BookingRepository bookings;
    private final BookingLineRepository lines;
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final ConsumableStockRepository stocks;
    private final OrganizationRepository organizations;
    private final ActivityLogService activity;
    private final BookingHistoryService history;
    private final Clock clock;

    public BookingService(
            BookingRepository bookings,
            BookingLineRepository lines,
            AssetRepository assets,
            AssetModelRepository models,
            ConsumableStockRepository stocks,
            OrganizationRepository organizations,
            ActivityLogService activity,
            BookingHistoryService history,
            Clock clock) {
        this.bookings = bookings;
        this.lines = lines;
        this.assets = assets;
        this.models = models;
        this.stocks = stocks;
        this.organizations = organizations;
        this.activity = activity;
        this.history = history;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BookingView> list(TarpeistoPrincipal p, Instant from, Instant until, UUID cursor, int limit) {
        return list(p, from, until, cursor, limit, false);
    }

    @Transactional(readOnly = true)
    public List<BookingView> list(
            TarpeistoPrincipal p, Instant from, Instant until, UUID cursor, int limit, boolean includeArchived) {
        if (p != null) p.requirePermanent();
        auth(p);
        if (limit < 1 || limit > 100 || !from.isBefore(until))
            throw new ValidationFailedException("Select a valid date window and a limit from 1 to 100.");
        return bookings
                .listWindow(p.organizationId(), from, until, cursor, includeArchived, PageRequest.of(0, limit))
                .stream()
                .map(b -> view(p.organizationId(), b))
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingView get(TarpeistoPrincipal p, UUID id) {
        if (p != null) p.requirePermanent();
        auth(p);
        return view(p.organizationId(), requireBooking(p.organizationId(), id));
    }

    @Transactional
    public BookingView create(
            TarpeistoPrincipal p,
            UUID mutationId,
            String name,
            String client,
            String venue,
            String notes,
            Instant start,
            Instant end) {
        if (p != null) p.requirePermanent();
        admin(p);
        lock(p.organizationId());
        UUID commandId = mutationId == null ? UUID.randomUUID() : mutationId;
        Booking b;
        try {
            b = new Booking(
                    UUID.randomUUID(),
                    p.organizationId(),
                    name,
                    client,
                    venue,
                    notes,
                    start,
                    end,
                    p.userId(),
                    clock.instant());
            b.identifyCreation(commandId, history.creationFingerprint(b));
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(e.getMessage());
        }
        Booking replay = bookings.findByOrganizationIdAndCreationMutationId(p.organizationId(), commandId)
                .orElse(null);
        if (replay != null) {
            if (!replay.getCreationFingerprint().equals(b.getCreationFingerprint()))
                throw new io.kellermann.tarpeisto.exception.BookingMutationConflictException();
            return view(p.organizationId(), replay);
        }
        b = bookings.saveAndFlush(b);
        history.record(p, b, "CREATED", null);
        activity.record(
                p.organizationId(), p.userId(), "BOOKING_CREATED", "BOOKING", b.getId(), Map.of("name", b.getName()));
        return view(p.organizationId(), b);
    }

    @Transactional
    public BookingView update(
            TarpeistoPrincipal p,
            UUID id,
            long expectedVersion,
            String name,
            String client,
            String venue,
            String notes,
            Instant start,
            Instant end) {
        if (p != null) p.requirePermanent();
        admin(p);
        Booking b = lockedDraft(p, id, expectedVersion);
        Map<String, Object> before = history.snapshot(b);
        try {
            b.change(name, client, venue, notes, start, end, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(e.getMessage());
        }
        bookings.flush();
        history.record(p, b, "UPDATED", before);
        activity.record(
                p.organizationId(),
                p.userId(),
                "BOOKING_UPDATED",
                "BOOKING",
                id,
                Map.of("before", before, "after", history.snapshot(b)));
        return view(p.organizationId(), b);
    }

    @Transactional
    public BookingView addLine(
            TarpeistoPrincipal p,
            UUID id,
            long expectedVersion,
            BookingLineType type,
            UUID assetId,
            UUID stockId,
            BigDecimal quantity) {
        if (p != null) p.requirePermanent();
        admin(p);
        Booking b = lockedDraft(p, id, expectedVersion);
        validateLine(p.organizationId(), type, assetId, stockId, quantity);
        List<BookingLine> existing = activeLines(p.organizationId(), id);
        for (BookingLine l : existing) {
            if (assetId != null
                    && (assetId.equals(l.getAssetId())
                            || l.getAssetId() != null
                                    && (ancestor(p.organizationId(), assetId, l.getAssetId())
                                            || ancestor(p.organizationId(), l.getAssetId(), assetId))))
                throw new ValidationFailedException(
                        "This asset is already selected or included through a selected container.");
            if (stockId != null && stockId.equals(l.getConsumableStockId()))
                throw new ValidationFailedException(
                        "This stock source is already selected; remove its line before changing the amount.");
        }
        Map<String, Object> before = history.snapshot(b);
        lines.save(new BookingLine(
                UUID.randomUUID(), p.organizationId(), id, type, assetId, stockId, quantity, clock.instant()));
        b.touch(clock.instant());
        lines.flush();
        bookings.flush();
        history.record(p, b, "LINE_ADDED", before);
        activity.record(
                p.organizationId(), p.userId(), "BOOKING_LINE_ADDED", "BOOKING", id, Map.of("type", type.name()));
        return view(p.organizationId(), b);
    }

    @Transactional
    public BookingView removeLine(TarpeistoPrincipal p, UUID id, UUID lineId, long expectedVersion) {
        if (p != null) p.requirePermanent();
        admin(p);
        Booking b = lockedDraft(p, id, expectedVersion);
        BookingLine l = lines.findByIdAndOrganizationId(lineId, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Booking line not found."));
        if (!l.getBookingId().equals(id) || l.isArchived()) throw new NotFoundException("Booking line not found.");
        Map<String, Object> before = history.snapshot(b);
        l.archive(clock.instant());
        b.touch(clock.instant());
        lines.flush();
        bookings.flush();
        history.record(p, b, "LINE_REMOVED", before);
        activity.record(
                p.organizationId(),
                p.userId(),
                "BOOKING_LINE_REMOVED",
                "BOOKING",
                id,
                Map.of("lineId", lineId.toString()));
        return view(p.organizationId(), b);
    }

    private boolean ancestor(UUID org, UUID child, UUID parent) {
        Set<UUID> seen = new HashSet<>();
        Asset a = assets.findByIdAndOrganizationId(child, org).orElse(null);
        while (a != null && a.getParentContainerAssetId() != null && seen.add(a.getId())) {
            if (a.getParentContainerAssetId().equals(parent)) return true;
            a = assets.findByIdAndOrganizationId(a.getParentContainerAssetId(), org)
                    .orElse(null);
        }
        return false;
    }

    private Booking lockedDraft(TarpeistoPrincipal p, UUID id, long version) {
        lock(p.organizationId());
        Booking b = bookings.findWithLockByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Booking not found."));
        if (b.getVersion() != version) throw new StaleBookingVersionException();
        if (b.getStatus() != BookingStatus.DRAFT)
            throw new ValidationFailedException("Only draft bookings can be changed.");
        return b;
    }

    private void validateLine(UUID org, BookingLineType type, UUID assetId, UUID stockId, BigDecimal qty) {
        if (type == null
                || qty == null
                || qty.signum() <= 0
                || qty.scale() > 3
                || qty.compareTo(new BigDecimal("100000000000")) >= 0)
            throw new ValidationFailedException(
                    "Quantity must be positive, below 100000000000, with at most three decimals.");
        if (type == BookingLineType.CONSUMABLE) {
            if (assetId != null
                    || stockId == null
                    || stocks.findByIdAndOrganizationId(stockId, org).isEmpty())
                throw new ValidationFailedException("Select a consumable source in this organization.");
            var balance = stocks.findByIdAndOrganizationId(stockId, org).orElseThrow();
            var model = models.findByIdAndOrganizationId(balance.getAssetModelId(), org)
                    .orElseThrow();
            if (balance.isArchived() || model.isArchived())
                throw new ValidationFailedException(
                        "Restore archived consumable stock and its model before selecting it.");
            return;
        }
        if (stockId != null || assetId == null || qty.compareTo(BigDecimal.ONE) != 0)
            throw new ValidationFailedException("Equipment lines require exactly one asset.");
        Asset a = assets.findByIdAndOrganizationId(assetId, org)
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        AssetModel m = models.findByIdAndOrganizationId(a.getAssetModelId(), org)
                .orElseThrow(() -> new NotFoundException("Asset model not found."));
        if (!a.isActive()
                || m.isArchived()
                || m.getTrackingMode() != TrackingMode.SERIALIZED_ASSET
                || (type == BookingLineType.CONTAINER) != m.isCanContainAssets())
            throw new ValidationFailedException("Select active equipment; containers must be booked whole.");
    }

    private List<BookingLine> activeLines(UUID org, UUID id) {
        return lines.findAllByOrganizationIdAndBookingIdAndArchivedAtIsNullOrderByCreatedAtAsc(org, id);
    }

    public BookingView view(UUID org, Booking b) {
        return new BookingView(
                b.getId(),
                b.getName(),
                b.getClientText(),
                b.getVenueText(),
                b.getNotes(),
                b.getStartsAt(),
                b.getEndsAt(),
                b.getStatus(),
                b.getReservationStatus(),
                b.getCurrentRevisionId(),
                b.getCreatedByUserId(),
                b.getVersion(),
                activeLines(org, b.getId()).stream()
                        .map(l -> new BookingLineView(
                                l.getId(),
                                l.getLineType(),
                                l.getAssetId(),
                                l.getConsumableStockId(),
                                l.getQuantity(),
                                l.getVersion()))
                        .toList(),
                b.isArchived());
    }

    private Booking requireBooking(UUID org, UUID id) {
        return bookings.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new NotFoundException("Booking not found."));
    }

    private void lock(UUID org) {
        organizations.findWithLockById(org).orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    static void auth(TarpeistoPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
    }

    static void admin(TarpeistoPrincipal p) {
        auth(p);
        if (p.role() != OrganizationRole.OWNER && p.role() != OrganizationRole.DEPUTY)
            throw new AccessDeniedException("Owner or Deputy role required.");
    }
}
