package io.kellermann.bigcontainers.exception;

import java.util.List;
import java.util.UUID;

public class PackingRemovalConfirmationException extends ApplicationException {
    private final List<UUID> affectedBookingIds;

    public PackingRemovalConfirmationException(List<UUID> ids) {
        super(
                "PACKING_REMOVAL_CONFIRMATION_REQUIRED",
                "Removing this requirement affects future reservations. Review and confirm the listed bookings.");
        affectedBookingIds = List.copyOf(ids);
    }

    public List<UUID> affectedBookingIds() {
        return affectedBookingIds;
    }
}
