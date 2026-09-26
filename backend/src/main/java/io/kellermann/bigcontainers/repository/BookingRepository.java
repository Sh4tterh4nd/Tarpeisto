package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Booking;
import io.kellermann.bigcontainers.model.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Optional<Booking> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Booking> findByOrganizationIdAndCreationMutationId(UUID organizationId, UUID mutationId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Booking> findWithLockByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Booking> findAllByOrganizationIdAndStatus(UUID organizationId, BookingStatus status);

    @Query(
            "select b from Booking b where b.organizationId = :organizationId and b.endsAt > :from and b.startsAt < :until and (:cursor is null or b.id > :cursor) order by b.id")
    List<Booking> listWindow(UUID organizationId, Instant from, Instant until, UUID cursor, Pageable page);
}
