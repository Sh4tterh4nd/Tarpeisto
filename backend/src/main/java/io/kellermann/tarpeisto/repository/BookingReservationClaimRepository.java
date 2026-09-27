package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.BookingReservationClaim;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BookingReservationClaimRepository extends JpaRepository<BookingReservationClaim, UUID> {
    @Query(
            "select c from BookingReservationClaim c join Booking b on c.revisionId = b.currentRevisionId where b.organizationId = :organizationId and b.status = io.kellermann.tarpeisto.model.BookingStatus.RESERVED")
    List<BookingReservationClaim> findActiveClaims(UUID organizationId);

    List<BookingReservationClaim> findAllByOrganizationId(UUID organizationId);

    List<BookingReservationClaim> findAllByOrganizationIdAndRevisionId(UUID organizationId, UUID revisionId);
}
