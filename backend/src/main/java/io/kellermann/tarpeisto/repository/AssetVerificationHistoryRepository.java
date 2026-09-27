package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AssetVerificationHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetVerificationHistoryRepository extends JpaRepository<AssetVerificationHistory, UUID> {}
