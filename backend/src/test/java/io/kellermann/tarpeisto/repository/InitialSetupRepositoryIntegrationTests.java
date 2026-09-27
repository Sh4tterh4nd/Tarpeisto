package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class InitialSetupRepositoryIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private InitialSetupRepository repository;

    @Test
    @Transactional
    void transactionScopedSetupLockCanBeAcquiredReentrantly() {
        repository.lock();
        repository.lock();
    }
}
