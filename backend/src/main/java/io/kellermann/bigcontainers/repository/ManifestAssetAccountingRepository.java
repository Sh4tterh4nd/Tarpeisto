package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.ManifestAssetAccounting;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManifestAssetAccountingRepository extends JpaRepository<ManifestAssetAccounting, UUID> {}
