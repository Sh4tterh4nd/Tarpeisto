package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.ManifestAssetAccounting;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManifestAssetAccountingRepository extends JpaRepository<ManifestAssetAccounting, UUID> {}
