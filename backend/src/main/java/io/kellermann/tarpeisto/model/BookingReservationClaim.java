package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable expansion claim associated with one reservation revision. */
@Entity
@Table(name = "event_booking_reservation_claim")
public class BookingReservationClaim {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "reservation_revision_id")
    private UUID revisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type")
    private BookingClaimType claimType;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "asset_model_id")
    private UUID assetModelId;

    @Column(name = "consumable_stock_id")
    private UUID consumableStockId;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "source_booking_line_id")
    private UUID sourceBookingLineId;

    @Column(name = "container_asset_id")
    private UUID containerId;

    @Column(name = "packing_requirement_id")
    private UUID requirementId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "jsonb")
    private String snapshot;

    public UUID getContainerId() {
        return containerId;
    }

    public UUID getRequirementId() {
        return requirementId;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public BookingReservationClaim(
            UUID id,
            UUID org,
            UUID revision,
            BookingClaimType type,
            UUID asset,
            UUID model,
            UUID stock,
            BigDecimal quantity,
            UUID line,
            UUID containerId,
            UUID requirementId,
            String snapshot) {
        this(id, org, revision, type, asset, model, stock, quantity, line);
        this.containerId = containerId;
        this.requirementId = requirementId;
        this.snapshot = snapshot;
    }

    protected BookingReservationClaim() {}

    public BookingReservationClaim(
            UUID id,
            UUID org,
            UUID revision,
            BookingClaimType type,
            UUID asset,
            UUID model,
            UUID stock,
            BigDecimal quantity,
            UUID line) {
        this.id = id;
        organizationId = org;
        revisionId = revision;
        claimType = type;
        assetId = asset;
        assetModelId = model;
        consumableStockId = stock;
        this.quantity = quantity;
        sourceBookingLineId = line;
        snapshot = "{}";
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public BookingClaimType getClaimType() {
        return claimType;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getAssetModelId() {
        return assetModelId;
    }

    public UUID getConsumableStockId() {
        return consumableStockId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public UUID getSourceBookingLineId() {
        return sourceBookingLineId;
    }
}
