package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    /**
     * Candidate accounts for automatic email-based OIDC linking (ADR-0003 linking policy): every
     * account whose email matches case-insensitively, regardless of enabled state (the caller
     * decides what an empty/multiple/disabled result means).
     */
    List<User> findAllByEmailIgnoreCase(String email);
}
