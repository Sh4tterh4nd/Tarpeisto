package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditTaskTests {
    @Test
    void blockedTaskBecomesReadyThenCompletes() {
        AuditTask task = new AuditTask(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AuditTaskState.BLOCKED,
                Instant.EPOCH);

        task.markReady();
        task.complete();

        assertThat(task.getState()).isEqualTo(AuditTaskState.COMPLETED);
    }

    @Test
    void blockedTaskCannotCompleteBeforeItsDependenciesClear() {
        AuditTask task = new AuditTask(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AuditTaskState.BLOCKED,
                Instant.EPOCH);

        assertThatThrownBy(task::complete).isInstanceOf(IllegalStateException.class);
    }
}
