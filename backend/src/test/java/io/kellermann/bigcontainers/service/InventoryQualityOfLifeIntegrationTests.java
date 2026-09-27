package io.kellermann.bigcontainers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.document.AssetLabelFormat;
import io.kellermann.bigcontainers.document.PackingSheetDocument;
import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.StalePlacementVersionException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetCode;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.BookingLineType;
import io.kellermann.bigcontainers.model.Category;
import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.model.LifecycleState;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.PackingRequirementType;
import io.kellermann.bigcontainers.model.TrackingMode;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.CategoryRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/** Real PostgreSQL regressions for the QoL milestone, with independent transaction boundaries. */
class InventoryQualityOfLifeIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetModelService modelService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private PackingRequirementService packing;

    @Autowired
    private AssetPlacementService placement;

    @Autowired
    private AssetLabelService labels;

    @Autowired
    private PackingSheetSnapshotService sheets;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private AssetModelRepository models;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private BookingService bookings;

    @Autowired
    private BookingReservationService reservations;

    @Autowired
    private CheckoutService checkout;

    @Test
    void categoryDeletionAndConcurrentRenamePreserveTheLastCommittedSnapshot() throws Exception {
        var owner = owner();
        var category = category(owner, "Before");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var rename = executor.submit(() -> {
                start.await();
                try {
                    categoryService.rename(owner, category.getId(), "After", "#445566");
                    return "After";
                } catch (NotFoundException deleted) {
                    return "Before";
                }
            });
            var deletion = executor.submit(() -> {
                start.await();
                categoryService.delete(owner, category.getId());
                return true;
            });
            start.countDown();
            String lastName = rename.get(20, TimeUnit.SECONDS);
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(categories.findById(category.getId())).isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT detail FROM activity_log WHERE target_id = ? AND action = 'CATEGORY_DELETED'",
                            String.class,
                            category.getId()))
                    .contains(lastName);
        }
    }

    @ParameterizedTest
    @CsvSource({"code,asc", "code,desc", "condition,asc", "condition,desc", "lifecycle,asc", "lifecycle,desc"})
    void everySupportedSortTraversesPagesInBothDirections(String sort, String direction) {
        var owner = owner();
        var model = model(owner, "Item", null, false);
        var first = asset(owner, model, "A", 1);
        var second = asset(owner, model, "B", 2);
        var third = asset(owner, model, "C", 3);
        first.changeCondition(Condition.DAMAGED, clock.instant());
        second.changeLifecycleState(LifecycleState.LOST, clock.instant());
        third.changeLifecycleState(LifecycleState.RETIRED, clock.instant());
        assets.saveAllAndFlush(List.of(first, second, third));
        List<AssetSearchView> rows = new ArrayList<>();
        String cursor = null;
        do {
            var page = search(owner, null, null, null, true, sort, direction, 1, cursor);
            rows.addAll(page.items());
            cursor = page.nextCursor();
            assertThat(rows.size()).isLessThanOrEqualTo(3);
        } while (cursor != null);
        Comparator<AssetSearchView> order = Comparator.comparing(row -> switch (sort) {
            case "code" -> row.publicCode();
            case "condition" -> row.condition().name();
            default -> row.lifecycleState().name();
        });
        if (direction.equals("desc")) order = order.reversed();
        assertThat(rows)
                .hasSize(3)
                .isSortedAccordingTo(order.thenComparing(row -> row.id().toString()));
        assertThat(rows.stream().map(AssetSearchView::id).distinct().count()).isEqualTo(3);
    }

    @Test
    void categoryDeletionAndConcurrentModelCreationCannotLeaveDanglingReferences() throws Exception {
        var owner = owner();
        var category = category(owner, "Concurrent");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> {
                start.await();
                try {
                    modelService.create(
                            owner,
                            "Concurrent model",
                            null,
                            category.getId(),
                            null,
                            TrackingMode.SERIALIZED_ASSET,
                            null,
                            null,
                            false);
                    return "created";
                } catch (NotFoundException deleted) {
                    return "deleted";
                }
            });
            var deletion = executor.submit(() -> {
                start.await();
                try {
                    categoryService.delete(owner, category.getId());
                    return "deleted";
                } catch (ValidationFailedException referenced) {
                    return "created";
                }
            });
            start.countDown();
            String outcome = creation.get(20, TimeUnit.SECONDS);
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(outcome);
            assertThat(categories.findById(category.getId()).isPresent()).isEqualTo(outcome.equals("created"));
            assertThat(models.existsByOrganizationIdAndCategoryId(owner.organizationId(), category.getId()))
                    .isEqualTo(outcome.equals("created"));
        }
    }

    @Test
    void checkedOutExactAssetAssignmentRollsBackRequirementAndMovement() {
        var owner = owner();
        var box = asset(owner, model(owner, "Case", null, true), "Box", 1);
        var item = asset(owner, model(owner, "Item", null, false), "Item", 1);
        var now = clock.instant();
        var booking =
                bookings.create(owner, UUID.randomUUID(), "Custody", null, null, null, now, now.plusSeconds(3600));
        booking = bookings.addLine(
                owner, booking.id(), booking.version(), BookingLineType.ASSET, item.getId(), null, BigDecimal.ONE);
        reservations.reserve(owner, booking.id(), booking.version());
        booking = bookings.get(owner, booking.id());
        checkout.checkout(owner, booking.id(), booking.version(), UUID.randomUUID(), null, List.of());
        int logCount = count("activity_log", owner);
        assertThatThrownBy(() -> packing.add(
                        owner,
                        box.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        item.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        item.getVersion()))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(packing.list(owner, box.getId())).isEmpty();
        assertThat(placement.get(owner, item.getId()).parentContainerAssetId()).isNull();
        assertThat(count("activity_log", owner)).isEqualTo(logCount);
        assertThat(count("packing_requirement_history", owner)).isZero();
    }

    @Test
    void uncategorizedModelsRemainVisibleAndCanGainAndClearCategoryWithActivity() throws Exception {
        var owner = owner();
        var model = modelService.create(
                owner, "No category", null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        assertThat(model.categoryId()).isNull();
        var asset = assetService.create(owner, model.id(), "Unit", null, List.of());
        assertThat(assetService.get(owner, asset.id()).id()).isEqualTo(asset.id());
        var result = search(owner, null, "Default", null, false, "name", "asc", 10, null);
        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.categoryName()).isEqualTo("Default");
            assertThat(row.categoryColor()).isEqualTo("#5B6472");
        });
        assertThat(new String(labels.ptouchCsv(owner, List.of(asset.id())), StandardCharsets.UTF_8))
                .contains("Default", "#5B6472");
        try (var document =
                Loader.loadPDF(labels.labels(owner, List.of(asset.id()), AssetLabelFormat.A4_70X36_24, 0, null))) {
            assertThat(new PDFTextStripper().getText(document)).contains("Default");
        }
        var category = category(owner, "Networking");
        assertThat(modelService
                        .changeCategory(owner, model.id(), category.getId())
                        .categoryId())
                .isEqualTo(category.getId());
        assertThat(modelService.changeCategory(owner, model.id(), null).categoryId())
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT detail FROM activity_log WHERE target_id = ? AND action = 'ASSET_MODEL_CATEGORY_CHANGED' ORDER BY occurred_at DESC LIMIT 1",
                        String.class,
                        model.id()))
                .contains("Default");
    }

    @Test
    void categoryDeletePreservesSnapshotAndRejectsActiveAndArchivedModelReferences() {
        var owner = owner();
        var category = category(owner, "Kept");
        var model = model(owner, "Referenced", category.getId(), false);
        assertThatThrownBy(() -> categoryService.delete(owner, category.getId()))
                .isInstanceOf(ValidationFailedException.class);
        model.archive(clock.instant());
        models.saveAndFlush(model);
        assertThatThrownBy(() -> categoryService.delete(owner, category.getId()))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM activity_log WHERE action = 'CATEGORY_DELETED' AND target_id = ?",
                        Integer.class,
                        category.getId()))
                .isZero();
        var unused = category(owner, "Removable");
        categoryService.delete(owner, unused.getId());
        assertThat(categories.findById(unused.getId())).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT detail FROM activity_log WHERE action = 'CATEGORY_DELETED' AND target_id = ? AND actor_user_id = ? AND occurred_at IS NOT NULL",
                        String.class,
                        unused.getId(),
                        owner.userId()))
                .contains("Removable", "#112233");
        var foreign = category(owner(), "Foreign");
        assertThatThrownBy(() -> categoryService.delete(owner, foreign.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Category not found.");
        assertThatThrownBy(() -> categoryService.delete(owner, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Category not found.");
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"DEPUTY", "OPERATOR_AUDITOR", "VIEWER"})
    void categoryDeletionRequiresOwner(OrganizationRole role) {
        var owner = owner();
        var category = category(owner, "Protected");
        assertThatThrownBy(() -> categoryService.delete(withRole(owner, role), category.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(categories.findById(category.getId())).isPresent();
    }

    @Test
    void nullableCategoryRetainsTenantForeignKeyAndDatabaseReferenceProtection() {
        var owner = owner();
        var category = category(owner, "Referenced");
        var model = model(owner, "Valid reference", category.getId(), false);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM category WHERE id = ?", category.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var foreign = category(owner(), "Foreign");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE asset_model SET category_id = ? WHERE id = ?", foreign.getId(), model.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE asset_model SET category_id = NULL WHERE id = ?", model.getId()))
                .isOne();
    }

    @Test
    void allAssetPagesTraverseSemanticNamesInBothDirectionsWithDeterministicTies() {
        var owner = owner();
        var zebra = model(owner, "Zebra", null, false);
        var alpha = model(owner, "Alpha", null, false);
        var expected = List.of(
                asset(owner, zebra, "Zulu", 1),
                asset(owner, alpha, "Bravo", 1),
                asset(owner, zebra, "alpha", 2),
                asset(owner, alpha, "Bravo", 2),
                asset(owner, alpha, null, 3));
        var foreign = owner();
        asset(foreign, model(foreign, "Foreign", null, false), "Foreign", 1);
        for (String direction : List.of("asc", "desc")) {
            var first = search(owner, null, null, null, false, "name", direction, 2, null);
            assertThat(first.nextCursor()).isNotBlank();
            assertThat(search(owner, null, null, null, false, "name", direction, 2, null)
                            .nextCursor())
                    .isEqualTo(first.nextCursor());
            List<AssetSearchView> rows = pages(owner, direction, 2);
            assertThat(rows).hasSize(expected.size());
            assertThat(rows.stream().map(AssetSearchView::id).distinct().count())
                    .isEqualTo(expected.size());
            Comparator<AssetSearchView> name =
                    Comparator.comparing(row -> row.displayName().toLowerCase(java.util.Locale.ROOT));
            if (direction.equals("desc")) name = name.reversed();
            // PostgreSQL compares UUIDs as unsigned bytes, not Java UUID's signed high bits.
            assertThat(rows)
                    .isSortedAccordingTo(name.thenComparing(row -> row.id().toString()));
            assertThat(rows.stream().map(AssetSearchView::id))
                    .containsExactlyInAnyOrderElementsOf(
                            expected.stream().map(Asset::getId).toList());
        }
    }

    @Test
    void assetFiltersAndLiteralSearchRemainTenantScopedAndInactiveRequiresOptIn() {
        var owner = owner();
        var category = category(owner, "Category");
        var box = asset(owner, model(owner, "Case", category.getId(), true), "Box", 1);
        var equipment = asset(owner, model(owner, "Cable", null, false), "100% cable", 1);
        var retired = asset(owner, model(owner, "Retired", null, false), "Old", 1);
        retired.changeLifecycleState(LifecycleState.RETIRED, clock.instant());
        assets.saveAndFlush(retired);
        assertThat(search(owner, null, category.getId().toString(), true, false, "name", "asc", 10, null)
                        .items())
                .extracting(AssetSearchView::id)
                .containsExactly(box.getId());
        assertThat(search(owner, null, "Default", false, false, "name", "asc", 10, null)
                        .items())
                .extracting(AssetSearchView::id)
                .containsExactly(equipment.getId());
        assertThat(search(owner, "%", null, null, false, "name", "asc", 10, null)
                        .items())
                .extracting(AssetSearchView::id)
                .containsExactly(equipment.getId());
        assertThat(search(
                                owner,
                                equipment.getPublicCode().toLowerCase(java.util.Locale.ROOT),
                                null,
                                null,
                                false,
                                "code",
                                "desc",
                                10,
                                null)
                        .items())
                .extracting(AssetSearchView::id)
                .containsExactly(equipment.getId());
        assertThat(search(owner, null, null, null, true, "lifecycle", "asc", 10, null)
                        .items())
                .hasSize(3);
        assertThat(search(owner, null, UUID.randomUUID().toString(), null, true, "name", "asc", 10, null)
                        .items())
                .isEmpty();
    }

    @Test
    void searchRejectsInvalidOrForeignCursorsSortsDirectionsAndLimits() {
        var owner = owner();
        for (String cursor : List.of(
                "!",
                "",
                "MA",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(("v1:name:false:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)))) {
            assertThatThrownBy(() -> search(owner, null, null, null, false, "name", "asc", 2, cursor))
                    .isInstanceOf(ValidationFailedException.class);
        }
        assertThatThrownBy(() -> search(owner, null, null, null, false, "id", "asc", 2, null))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> search(owner, null, null, null, false, "name", "invalid", 2, null))
                .isInstanceOf(ValidationFailedException.class);
        for (int limit : List.of(0, -1, 101))
            assertThatThrownBy(() -> search(owner, null, null, null, false, "name", "asc", limit, null))
                    .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> search(owner, null, "not-id", null, false, "name", "asc", 2, null))
                .isInstanceOf(ValidationFailedException.class);
        var foreign = owner();
        var foreignModel = model(foreign, "Foreign", null, false);
        asset(foreign, foreignModel, "A", 1);
        asset(foreign, foreignModel, "B", 2);
        String foreignCursor =
                search(foreign, null, null, null, false, "name", "asc", 1, null).nextCursor();
        assertThatThrownBy(() -> search(owner, null, null, null, false, "name", "asc", 1, foreignCursor))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> search(foreign, null, null, null, false, "name", "desc", 1, foreignCursor))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void containerAssetsAreExcludedFromBothOrdinaryExportsIncludingMixedRequests() {
        var owner = owner();
        var box = asset(owner, model(owner, "Container", null, true), "Box", 1);
        var equipment = asset(owner, model(owner, "Equipment", null, false), "Item", 1);
        for (List<UUID> ids : List.of(
                List.of(box.getId()),
                List.of(equipment.getId(), box.getId()),
                List.of(equipment.getId(), UUID.randomUUID()))) {
            assertThatThrownBy(() -> labels.labels(owner, ids, AssetLabelFormat.A4_70X36_24, 0, null))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> labels.ptouchCsv(owner, ids)).isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    void exactRequirementAssignsAtomicallyAndReturnsRefreshablePlacementVersion() {
        var owner = owner();
        var box = asset(owner, model(owner, "Case", null, true), "Box", 1);
        var equipment = asset(owner, model(owner, "Router", null, false), "Router", 1);
        packing.add(
                owner,
                box.getId(),
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                equipment.getId(),
                null,
                BigDecimal.ONE,
                true,
                equipment.getVersion());
        assertThat(packing.list(owner, box.getId()))
                .singleElement()
                .satisfies(row -> assertThat(row.specificAssetId()).isEqualTo(equipment.getId()));
        var refreshed = placement.get(owner, equipment.getId());
        assertThat(refreshed.parentContainerAssetId()).isEqualTo(box.getId());
        assertThat(refreshed.version()).isGreaterThan(equipment.getVersion());
        assertThat(placement
                        .move(owner, equipment.getId(), null, null, refreshed.version())
                        .parentContainerAssetId())
                .isNull();
    }

    @Test
    void staleAssignmentRollsBackRequirementHistoryActivityAndPlacement() {
        var owner = owner();
        var box = asset(owner, model(owner, "Case", null, true), "Box", 1);
        var equipment = asset(owner, model(owner, "Router", null, false), "Router", 1);
        int logCount = count("activity_log", owner);
        assertThatThrownBy(() -> packing.add(
                        owner,
                        box.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        equipment.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        equipment.getVersion() + 1))
                .isInstanceOf(StalePlacementVersionException.class);
        assertThat(packing.list(owner, box.getId())).isEmpty();
        assertThat(placement.get(owner, equipment.getId()).parentContainerAssetId())
                .isNull();
        assertThat(count("packing_requirement_history", owner)).isZero();
        assertThat(count("activity_log", owner)).isEqualTo(logCount);
    }

    @Test
    void assignmentRejectsCycleSelfForeignInactiveWrongTypeAndMissingVersionWithoutMutation() {
        var owner = owner();
        var caseModel = model(owner, "Case", null, true);
        var parent = asset(owner, caseModel, "Parent", 1);
        var child = asset(owner, caseModel, "Child", 2);
        placement.move(owner, child.getId(), null, parent.getId(), child.getVersion());
        assertThatThrownBy(() -> packing.add(
                        owner,
                        child.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        parent.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        parent.getVersion()))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(packing.list(owner, child.getId())).isEmpty();
        assertThat(placement.get(owner, child.getId()).parentContainerAssetId()).isEqualTo(parent.getId());
        assertThatThrownBy(() -> packing.add(
                        owner,
                        parent.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        parent.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        parent.getVersion()))
                .isInstanceOf(ValidationFailedException.class);
        var equipment = asset(owner, model(owner, "Equipment", null, false), "Item", 1);
        assertThatThrownBy(() -> packing.add(
                        owner,
                        parent.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        equipment.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        null))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> packing.add(
                        owner,
                        parent.getId(),
                        PackingRequirementType.MODEL_QUANTITY,
                        equipment.getAssetModelId(),
                        null,
                        null,
                        BigDecimal.ONE,
                        true,
                        equipment.getVersion()))
                .isInstanceOf(ValidationFailedException.class);
        var foreign = owner();
        var foreignAsset = asset(foreign, model(foreign, "Foreign", null, false), "Foreign", 1);
        assertThatThrownBy(() -> packing.add(
                        owner,
                        parent.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        foreignAsset.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        0L))
                .isInstanceOf(NotFoundException.class);
        equipment.changeLifecycleState(LifecycleState.RETIRED, clock.instant());
        assets.saveAndFlush(equipment);
        assertThatThrownBy(() -> packing.add(
                        owner,
                        parent.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        equipment.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        equipment.getVersion()))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(packing.list(owner, parent.getId())).isEmpty();
        assertThat(placement.get(owner, equipment.getId()).parentContainerAssetId())
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = OrganizationRole.class,
            names = {"OPERATOR_AUDITOR", "VIEWER"})
    void unauthorizedAssignmentDoesNotAddOrMove(OrganizationRole role) {
        var owner = owner();
        var box = asset(owner, model(owner, "Case", null, true), "Box", 1);
        var item = asset(owner, model(owner, "Item", null, false), "Item", 1);
        assertThatThrownBy(() -> packing.add(
                        withRole(owner, role),
                        box.getId(),
                        PackingRequirementType.SPECIFIC_ASSET,
                        null,
                        item.getId(),
                        null,
                        BigDecimal.ONE,
                        true,
                        item.getVersion()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(packing.list(owner, box.getId())).isEmpty();
        assertThat(placement.get(owner, item.getId()).parentContainerAssetId()).isNull();
    }

    @Test
    void templateApplicationNeverMovesExactAssetsAndUncategorizedContainerSheetHasIdentity() throws Exception {
        var owner = owner();
        var box = asset(owner, model(owner, "Case", null, true), "Box", 1);
        var equipment = asset(owner, model(owner, "Equipment", null, false), "Item", 1);
        var template = packing.createTemplate(owner, "Template", null);
        packing.addTemplateRequirement(
                owner,
                template.id(),
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                equipment.getId().toString(),
                BigDecimal.ONE);
        packing.applyTemplate(owner, box.getId(), template.id());
        assertThat(placement.get(owner, equipment.getId()).parentContainerAssetId())
                .isNull();
        var snapshot = sheets.snapshot(owner.organizationId(), box.getId());
        assertThat(snapshot.categoryName()).isEqualTo("Default");
        assertThat(snapshot.categoryColor()).isEqualTo("#5B6472");
        try (var document = Loader.loadPDF(PackingSheetDocument.render(snapshot))) {
            var text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Box", "Default", equipment.getPublicCode());
            assertThat(text.split(equipment.getPublicCode(), -1).length - 1).isEqualTo(2);
        }
    }

    private AssetSearchPageView search(
            BigContainersPrincipal owner,
            String query,
            String category,
            Boolean container,
            boolean inactive,
            String sort,
            String direction,
            int limit,
            String cursor) {
        return assetService.search(owner, query, category, container, inactive, sort, direction, limit, cursor);
    }

    private List<AssetSearchView> pages(BigContainersPrincipal owner, String direction, int size) {
        List<AssetSearchView> result = new ArrayList<>();
        String cursor = null;
        do {
            var page = search(owner, null, null, null, false, "name", direction, size, cursor);
            result.addAll(page.items());
            cursor = page.nextCursor();
            assertThat(result.size()).isLessThan(20);
        } while (cursor != null);
        return result;
    }

    private int count(String table, BigContainersPrincipal owner) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id = ?", Integer.class, owner.organizationId());
    }

    private BigContainersPrincipal owner() {
        var org = organizations.ensureOrganizationExists("QoL " + UUID.randomUUID());
        var user = users.saveAndFlush(new User(
                UUID.randomUUID(), "qol-" + UUID.randomUUID(), null, "Owner", "{noop}unused", true, clock.instant()));
        return new BigContainersPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), org.getId(), OrganizationRole.OWNER);
    }

    private static BigContainersPrincipal withRole(BigContainersPrincipal owner, OrganizationRole role) {
        return new BigContainersPrincipal(
                owner.userId(), owner.username(), owner.displayName(), owner.organizationId(), role);
    }

    private Category category(BigContainersPrincipal owner, String name) {
        return categories.saveAndFlush(
                new Category(UUID.randomUUID(), owner.organizationId(), name, "#112233", clock.instant()));
    }

    private AssetModel model(BigContainersPrincipal owner, String name, UUID category, boolean container) {
        return models.saveAndFlush(new AssetModel(
                UUID.randomUUID(),
                owner.organizationId(),
                name,
                "Reference description",
                category,
                null,
                TrackingMode.SERIALIZED_ASSET,
                null,
                null,
                container,
                clock.instant()));
    }

    private Asset asset(BigContainersPrincipal owner, AssetModel model, String name, int number) {
        return assets.saveAndFlush(new Asset(
                UUID.randomUUID(),
                owner.organizationId(),
                model.getId(),
                AssetCode.format(String.format("%05d", count("physical_asset", owner)))
                        .value(),
                number,
                name,
                null,
                clock.instant()));
    }
}
