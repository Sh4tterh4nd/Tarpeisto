package io.kellermann.tarpeisto.model;

import java.util.UUID;

public record AssetSearchFilter(
        boolean includeArchived,
        UUID locationId,
        Condition condition,
        LifecycleState lifecycle,
        UUID bookingId,
        BookingStatus bookingStatus,
        Boolean openRepair,
        String auditStatus,
        Boolean metadataIncomplete,
        String availability) {
    public static AssetSearchFilter defaults(boolean includeInactive) {
        return new AssetSearchFilter(includeInactive, null, null, null, null, null, null, null, null, null);
    }
}
