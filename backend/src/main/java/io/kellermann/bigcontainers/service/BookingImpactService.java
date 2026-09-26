package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Downward reservation-impact seam used by inventory mutation services. */
@Service
public class BookingImpactService {
    private final BookingReservationService reservations;

    public BookingImpactService(BookingReservationService reservations) {
        this.reservations = reservations;
    }

    public void changed(BigContainersPrincipal principal) {
        reservations.recalculate(principal);
    }

    public List<UUID> affectedBookings(UUID organizationId, UUID containerId) {
        return reservations.affectedBookings(organizationId, containerId);
    }
}
