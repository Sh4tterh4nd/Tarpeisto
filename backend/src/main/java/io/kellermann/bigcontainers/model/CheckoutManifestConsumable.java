package io.kellermann.bigcontainers.model;

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

@Entity
@Table(name = "checkout_manifest_consumable")
public class CheckoutManifestConsumable {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "checkout_manifest_id")
    private UUID manifestId;

    @Column(name = "consumable_stock_balance_id")
    private UUID stockId;

    @Column(name = "source_booking_line_id")
    private UUID sourceBookingLineId;

    @Column(name = "container_asset_id")
    private UUID containerAssetId;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "returned_quantity")
    private BigDecimal returnedQuantity;

    @Column(name = "accounted_at")
    private java.time.Instant accountedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "semantics")
    private CheckoutConsumableSemantics semantics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consumable_snapshot", columnDefinition = "jsonb")
    private String snapshot;

    protected CheckoutManifestConsumable() {}

    public CheckoutManifestConsumable(
            UUID id,
            UUID org,
            UUID manifest,
            UUID stock,
            UUID line,
            UUID container,
            BigDecimal quantity,
            CheckoutConsumableSemantics semantics,
            String snapshot) {
        this.id = id;
        organizationId = org;
        manifestId = manifest;
        stockId = stock;
        sourceBookingLineId = line;
        containerAssetId = container;
        this.quantity = quantity;
        returnedQuantity = BigDecimal.ZERO;
        this.semantics = semantics;
        this.snapshot = snapshot;
    }

    public void addReturned(BigDecimal amount) {
        if (accountedAt != null || amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("This line is closed or the return is not positive.");
        if (returnedQuantity.add(amount).compareTo(quantity) > 0)
            throw new IllegalArgumentException("Return exceeds issued amount.");
        returnedQuantity = returnedQuantity.add(amount);
    }

    public void account(java.time.Instant at) {
        if (accountedAt == null) accountedAt = at;
    }

    public java.time.Instant getAccountedAt() {
        return accountedAt;
    }

    public BigDecimal getConsumedQuantity() {
        return semantics == CheckoutConsumableSemantics.SEPARATELY_ISSUED
                ? quantity.subtract(returnedQuantity)
                : BigDecimal.ZERO;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getManifestId() {
        return manifestId;
    }

    public UUID getStockId() {
        return stockId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getReturnedQuantity() {
        return returnedQuantity;
    }

    public CheckoutConsumableSemantics getSemantics() {
        return semantics;
    }

    public String getSnapshot() {
        return snapshot;
    }
}
