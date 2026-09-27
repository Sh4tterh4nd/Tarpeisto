package io.kellermann.tarpeisto.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. Covers specification sections 8.1-8.4. */
class AssetTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorDefaultsConditionGoodAndLifecycleActive() {
        Asset asset = asset("7K3MXY", 1, null);
        assertThat(asset.getCondition()).isEqualTo(Condition.GOOD);
        assertThat(asset.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(asset.isActive()).isTrue();
        assertThat(asset.isArchived()).isFalse();
    }

    @Test
    void constructorRejectsAnInvalidPublicCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> asset("NOTACODE", 1, null));
    }

    @Test
    void constructorRejectsANonPositiveUnitNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> asset("7K3MXY", 0, null));
    }

    @Test
    void constructorTrimsBlankIndividualNameToNull() {
        Asset asset = asset("7K3MXY", 1, "   ");
        assertThat(asset.getIndividualName()).isNull();
    }

    @Test
    void renameSetsAndClearsIndividualName() {
        Asset asset = asset("7K3MXY", 1, null);
        asset.rename("Mobile Network Box", NOW.plusSeconds(1));
        assertThat(asset.getIndividualName()).isEqualTo("Mobile Network Box");
        asset.rename(null, NOW.plusSeconds(2));
        assertThat(asset.getIndividualName()).isNull();
    }

    @Test
    void changeConditionReturnsThePreviousValue() {
        Asset asset = asset("7K3MXY", 1, null);
        Condition previous = asset.changeCondition(Condition.DAMAGED, NOW.plusSeconds(1));
        assertThat(previous).isEqualTo(Condition.GOOD);
        assertThat(asset.getCondition()).isEqualTo(Condition.DAMAGED);
    }

    @Test
    void changeConditionToTheSameValueIsRejected() {
        Asset asset = asset("7K3MXY", 1, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset.changeCondition(Condition.GOOD, NOW.plusSeconds(1)));
    }

    @Test
    void changeLifecycleStateReturnsThePreviousValueAndIsExcludedFromActive() {
        Asset asset = asset("7K3MXY", 1, null);
        LifecycleState previous = asset.changeLifecycleState(LifecycleState.LOST, NOW.plusSeconds(1));
        assertThat(previous).isEqualTo(LifecycleState.ACTIVE);
        assertThat(asset.getLifecycleState()).isEqualTo(LifecycleState.LOST);
        assertThat(asset.isActive()).isFalse();
    }

    @Test
    void changeLifecycleStateToTheSameValueIsRejected() {
        Asset asset = asset("7K3MXY", 1, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset.changeLifecycleState(LifecycleState.ACTIVE, NOW.plusSeconds(1)));
    }

    @Test
    void restoringALostAssetSetsLifecycleBackToActive() {
        Asset asset = asset("7K3MXY", 1, null);
        asset.changeLifecycleState(LifecycleState.LOST, NOW.plusSeconds(1));
        asset.changeLifecycleState(LifecycleState.ACTIVE, NOW.plusSeconds(2));
        assertThat(asset.isActive()).isTrue();
    }

    @Test
    void archiveAndRestoreToggleArchivedState() {
        Asset asset = asset("7K3MXY", 1, null);
        asset.archive(NOW.plusSeconds(1));
        assertThat(asset.isArchived()).isTrue();
        assertThat(asset.isActive()).isFalse();
        asset.restore(NOW.plusSeconds(2));
        assertThat(asset.isArchived()).isFalse();
        assertThat(asset.isActive()).isTrue();
    }

    @Test
    void sealProjectionRequiresSealabilityAndTracksBreakage() {
        Asset asset = asset("7K3MXY", 1, "Container");
        assertThatIllegalStateException().isThrownBy(() -> asset.applySeal(NOW));

        asset.setSealable(true, NOW);
        asset.applySeal(NOW.plusSeconds(1));
        assertThat(asset.getSealState()).isEqualTo(SealState.APPLIED);
        asset.breakSeal(NOW.plusSeconds(2));
        assertThat(asset.getSealState()).isEqualTo(SealState.BROKEN);
        assertThat(asset.getSealVerifiedAt()).isNull();
    }

    @Test
    void verificationAndReplacementPredecessorAreExplicitProjections() {
        Asset asset = asset("7K3MXY", 1, null);
        UUID auditId = UUID.randomUUID();
        UUID predecessorId = UUID.randomUUID();
        asset.recordVerification(auditId, NOW.plusSeconds(1));
        asset.setReplacementPredecessor(predecessorId, NOW.plusSeconds(2));
        assertThat(asset.getLastVerifiedAuditId()).isEqualTo(auditId);
        assertThat(asset.getReplacesAssetId()).isEqualTo(predecessorId);
    }

    private static Asset asset(String publicCode, int unitNumber, String individualName) {
        return new Asset(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                publicCode,
                unitNumber,
                individualName,
                LocalDate.of(2025, 1, 1),
                NOW);
    }
}
