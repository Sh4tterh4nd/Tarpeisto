package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "event_booking_line")
public class BookingLine {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "event_booking_id")
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type")
    private BookingLineType lineType;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "consumable_stock_id")
    private UUID consumableStockId;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public void archive(Instant now) {
        archivedAt = now;
        updatedAt = now;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    protected BookingLine() {}

    public BookingLine(
            UUID id,
            UUID organizationId,
            UUID bookingId,
            BookingLineType type,
            UUID assetId,
            UUID stockId,
            BigDecimal quantity,
            Instant now) {
        this.id = Objects.requireNonNull(id);
        this.organizationId = Objects.requireNonNull(organizationId);
        this.bookingId = Objects.requireNonNull(bookingId);
        lineType = Objects.requireNonNull(type);
        this.assetId = assetId;
        consumableStockId = stockId;
        this.quantity = Objects.requireNonNull(quantity);
        createdAt = now;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public BookingLineType getLineType() {
        return lineType;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getConsumableStockId() {
        return consumableStockId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public long getVersion() {
        return version;
    }
}
