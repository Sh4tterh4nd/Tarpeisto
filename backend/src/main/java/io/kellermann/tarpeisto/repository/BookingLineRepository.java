package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.BookingLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingLineRepository extends JpaRepository<BookingLine, UUID> {
    List<BookingLine> findAllByOrganizationIdAndBookingIdAndArchivedAtIsNullOrderByCreatedAtAsc(
            UUID organizationId, UUID bookingId);

    Optional<BookingLine> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
