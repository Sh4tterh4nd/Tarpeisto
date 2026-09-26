package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.BookingReservationRevision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingReservationRevisionRepository extends JpaRepository<BookingReservationRevision, UUID> {
    List<BookingReservationRevision> findAllByOrganizationIdAndBookingIdOrderByRevisionNumberAsc(
            UUID organizationId, UUID bookingId);
}
