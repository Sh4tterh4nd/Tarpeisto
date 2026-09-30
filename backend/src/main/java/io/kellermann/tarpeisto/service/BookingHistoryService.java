package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Booking;
import io.kellermann.tarpeisto.repository.BookingLineRepository;
import io.kellermann.tarpeisto.repository.BookingRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingHistoryService {
    private final JdbcClient jdbc;
    private final BookingRepository bookings;
    private final BookingLineRepository lines;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BookingHistoryService(
            JdbcClient jdbc,
            BookingRepository bookings,
            BookingLineRepository lines,
            ObjectMapper mapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.lines = lines;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BookingHistoryView> list(TarpeistoPrincipal p, UUID id, UUID cursor, int limit) {
        if (p != null) p.requirePermanent();
        if (p == null) throw new org.springframework.security.access.AccessDeniedException("Authentication required.");
        bookings.findByIdAndOrganizationId(id, p.organizationId())
                .orElseThrow(() -> new NotFoundException("Booking not found."));
        if (limit < 1 || limit > 100) throw new ValidationFailedException("History limit must be from 1 to 100.");
        return jdbc.sql(
                        "SELECT id,action,actor_user_id,occurred_at,snapshot::text AS snapshot FROM event_booking_history WHERE organization_id=:org AND event_booking_id=:booking"
                                + (cursor == null
                                        ? ""
                                        : " AND (occurred_at,id) > (SELECT occurred_at,id FROM event_booking_history WHERE id=:cursor AND organization_id=:org AND event_booking_id=:booking)")
                                + " ORDER BY occurred_at,id LIMIT :limit")
                .param("org", p.organizationId())
                .param("booking", id)
                .param("cursor", cursor)
                .param("limit", limit)
                .query((row, index) -> new BookingHistoryView(
                        row.getObject("id", UUID.class),
                        row.getString("action"),
                        row.getObject("actor_user_id", UUID.class),
                        row.getTimestamp("occurred_at").toInstant(),
                        mapper.readValue(row.getString("snapshot"), new TypeReference<Map<String, Object>>() {})))
                .list();
    }

    public String creationFingerprint(Booking b) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("actor", b.getCreatedByUserId());
        values.put("name", b.getName());
        values.put("clientText", b.getClientText());
        values.put("venueText", b.getVenueText());
        values.put("notes", b.getNotes());
        values.put("startsAt", b.getStartsAt());
        values.put("endsAt", b.getEndsAt());
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsString(values)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    public Map<String, Object> snapshot(Booking b) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", b.getId());
        s.put("creationMutationId", b.getCreationMutationId());
        s.put("name", b.getName());
        s.put("clientText", b.getClientText());
        s.put("venueText", b.getVenueText());
        s.put("notes", b.getNotes());
        s.put("startsAt", b.getStartsAt());
        s.put("endsAt", b.getEndsAt());
        s.put("status", b.getStatus());
        s.put("reservationStatus", b.getReservationStatus());
        s.put("currentRevisionId", b.getCurrentRevisionId());
        s.put("createdByUserId", b.getCreatedByUserId());
        s.put(
                "lines",
                lines
                        .findAllByOrganizationIdAndBookingIdAndArchivedAtIsNullOrderByCreatedAtAsc(
                                b.getOrganizationId(), b.getId())
                        .stream()
                        .map(l -> {
                            Map<String, Object> v = new LinkedHashMap<>();
                            v.put("id", l.getId());
                            v.put("type", l.getLineType());
                            v.put("assetId", l.getAssetId());
                            v.put("consumableStockId", l.getConsumableStockId());
                            v.put("quantity", l.getQuantity());
                            return v;
                        })
                        .toList());
        return s;
    }

    public void record(TarpeistoPrincipal p, Booking b, String action, Map<String, Object> before) {
        if (p != null) p.requirePermanent();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", snapshot(b));
        jdbc.sql(
                        "INSERT INTO event_booking_history (id,organization_id,event_booking_id,action,actor_user_id,occurred_at,snapshot) VALUES (:id,:org,:booking,:action,:actor,:now,CAST(:snapshot AS jsonb))")
                .param("id", UUID.randomUUID())
                .param("org", p.organizationId())
                .param("booking", b.getId())
                .param("action", action)
                .param("actor", p.userId())
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("snapshot", mapper.writeValueAsString(change))
                .update();
    }
}
