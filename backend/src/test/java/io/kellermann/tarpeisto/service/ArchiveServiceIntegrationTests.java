package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import io.kellermann.tarpeisto.model.ArchiveKind;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class ArchiveServiceIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private ArchiveService archives;

    @Autowired
    private UserService users;

    @Autowired
    private ExternalIdentityService identities;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    void permanentUserArchivePreservesIdentityRestoresDisabledAndRejectsStaleCycles() {
        var owner = owner();
        var user = users.createUser(
                owner,
                "archive-" + UUID.randomUUID(),
                "long-test-password",
                "Archive operator",
                null,
                OrganizationRole.OPERATOR_AUDITOR);
        var archived = archives.change(owner, ArchiveKind.USER, user.id(), true, user.version());
        assertThat(archived.archived()).isTrue();
        assertThat(users.listUsers(owner)).extracting(UserSummaryView::id).doesNotContain(user.id());
        assertThat(users.listUsers(owner, true))
                .filteredOn(u -> u.id().equals(user.id()))
                .singleElement()
                .satisfies(u -> {
                    assertThat(u.enabled()).isFalse();
                    assertThat(u.archived()).isTrue();
                });
        assertThatThrownBy(() -> users.setEnabled(owner, user.id(), true)).hasMessageContaining("Restore");
        assertThatThrownBy(() -> identities.createLink(owner, user.id(), "https://example.invalid", "test-subject"))
                .hasMessageContaining("Restore");
        assertThat(archives.change(owner, ArchiveKind.USER, user.id(), true, user.version()))
                .isEqualTo(archived);
        var restored = archives.change(owner, ArchiveKind.USER, user.id(), false, archived.version());
        assertThat(users.listUsers(owner))
                .filteredOn(u -> u.id().equals(user.id()))
                .singleElement()
                .satisfies(u -> assertThat(u.enabled()).isFalse());
        archives.change(owner, ArchiveKind.USER, user.id(), true, restored.version());
        assertThatThrownBy(() -> archives.change(owner, ArchiveKind.USER, user.id(), true, user.version()))
                .isInstanceOf(ArchiveConflictException.class);
    }

    @Test
    @Transactional
    void oidcOnlyOwnerDoesNotPermitRemovalOfLastLocalRecoveryOwner() {
        var local = owner();
        UUID other = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO app_user(id,username,display_name,enabled,created_at,updated_at,version) VALUES(:id,:name,'External owner',true,now(),now(),0)")
                .param("id", other)
                .param("name", "external-" + other)
                .update();
        entityManager.flush();
        jdbc.sql(
                        "INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version) VALUES(:id,:org,:user,'OWNER',now(),now(),0)")
                .param("id", UUID.randomUUID())
                .param("org", local.organizationId())
                .param("user", other)
                .update();
        assertThatThrownBy(() -> archives.change(local, ArchiveKind.USER, local.userId(), true, 0))
                .isInstanceOf(ArchiveConflictException.class)
                .hasMessageContaining("local Owner");
        assertThatThrownBy(() -> users.setEnabled(local, local.userId(), false))
                .isInstanceOf(ArchiveConflictException.class)
                .hasMessageContaining("local Owner");
        assertThatThrownBy(() -> users.changeRole(local, local.userId(), OrganizationRole.DEPUTY))
                .isInstanceOf(ArchiveConflictException.class)
                .hasMessageContaining("local Owner");
    }

    @Test
    @Transactional
    void membershipInAnotherTenantPreventsGlobalUserArchival() {
        var owner = owner();
        var other = owner();
        var target = users.createUser(
                owner, "shared-" + UUID.randomUUID(), "long-test-password", "Shared", null, OrganizationRole.VIEWER);
        entityManager.flush();
        jdbc.sql(
                        "INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version) VALUES(:id,:org,:user,'OWNER',now(),now(),0)")
                .param("id", UUID.randomUUID())
                .param("org", other.organizationId())
                .param("user", target.id())
                .update();
        assertThatThrownBy(() -> archives.change(owner, ArchiveKind.USER, target.id(), true, target.version()))
                .isInstanceOf(ArchiveConflictException.class)
                .hasMessageContaining("another organization");
        assertThat(users.listUsers(owner))
                .filteredOn(u -> u.id().equals(target.id()))
                .singleElement()
                .satisfies(u -> assertThat(u.enabled()).isTrue());
    }

    private TarpeistoPrincipal owner() {
        UUID org = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization(id,name,created_at,updated_at,version) VALUES(:id,:name,now(),now(),0)")
                .param("id", org)
                .param("name", "Archive " + org)
                .update();
        String name = "owner-" + id;
        jdbc.sql(
                        "INSERT INTO app_user(id,username,display_name,password_hash,enabled,created_at,updated_at,version) VALUES(:id,:name,'Owner','test-hash',true,now(),now(),0)")
                .param("id", id)
                .param("name", name)
                .update();
        jdbc.sql(
                        "INSERT INTO organization_membership(id,organization_id,user_id,role,created_at,updated_at,version) VALUES(:id,:org,:user,'OWNER',now(),now(),0)")
                .param("id", UUID.randomUUID())
                .param("org", org)
                .param("user", id)
                .update();
        return new TarpeistoPrincipal(id, name, "Owner", org, OrganizationRole.OWNER);
    }
}
