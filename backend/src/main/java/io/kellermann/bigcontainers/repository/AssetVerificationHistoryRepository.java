package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.AssetVerificationHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetVerificationHistoryRepository extends JpaRepository<AssetVerificationHistory, UUID> {}
