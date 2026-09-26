package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class OrganizationMembershipTests {

    @Test
    void changeRoleUpdatesRoleAndTimestamp() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        OrganizationMembership membership = new OrganizationMembership(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), OrganizationRole.VIEWER, createdAt);

        Instant changedAt = Instant.parse("2026-02-01T00:00:00Z");
        membership.changeRole(OrganizationRole.DEPUTY, changedAt);

        assertThat(membership.getRole()).isEqualTo(OrganizationRole.DEPUTY);
        assertThat(membership.getUpdatedAt()).isEqualTo(changedAt);
        assertThat(membership.getCreatedAt()).isEqualTo(createdAt);
    }
}
