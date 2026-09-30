package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Category;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.CategoryRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category administration (specification section 5). Every read is scoped to {@code
 * principal.organizationId()}; every mutation additionally requires Owner or Deputy
 * (specification section 4.1: "Model, category, custom-field, ... administration"), re-checked
 * here rather than in {@code SecurityConfiguration} or the controller, per
 * docs/DEVELOPMENT_POLICIES.md section 5.1 - the same pattern {@link UserService} follows.
 *
 * <p>A category id that exists but belongs to a different organization is reported as {@link
 * NotFoundException}, identical to one that does not exist at all (404-vs-403 policy).
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ActivityLogService activityLogService;
    private final AssetModelRepository assetModelRepository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    public CategoryService(
            CategoryRepository categoryRepository,
            ActivityLogService activityLogService,
            AssetModelRepository assetModelRepository,
            OrganizationRepository organizationRepository,
            Clock clock) {
        this.categoryRepository = categoryRepository;
        this.activityLogService = activityLogService;
        this.assetModelRepository = assetModelRepository;
        this.organizationRepository = organizationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> list(TarpeistoPrincipal principal) {
        if (principal != null) principal.requirePermanent();
        requireOrganizationMember(principal);
        return categoryRepository.findAllByOrganizationIdOrderByNameAsc(principal.organizationId()).stream()
                .map(CategoryView::from)
                .toList();
    }

    @Transactional
    public CategoryView create(TarpeistoPrincipal principal, String name, String color) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        requireNameAvailable(principal.organizationId(), name);

        var now = clock.instant();
        Category category;
        try {
            category = new Category(UUID.randomUUID(), principal.organizationId(), name, color, now);
        } catch (IllegalArgumentException invalid) {
            // Translates the entity's own #RRGGBB format check into the stable
            // ValidationFailedException/400 contract instead of an unmapped
            // IllegalArgumentException, which ApplicationExceptionHandler does not handle.
            throw new ValidationFailedException(invalid.getMessage());
        }
        categoryRepository.save(category);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "CATEGORY_CREATED",
                "CATEGORY",
                category.getId(),
                Map.of("name", category.getName()));
        return CategoryView.from(category);
    }

    @Transactional
    public CategoryView rename(TarpeistoPrincipal principal, UUID categoryId, String newName, String newColor) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        Category category = requireCategory(principal.organizationId(), categoryId);
        if (!category.getName().equalsIgnoreCase(newName)) {
            requireNameAvailable(principal.organizationId(), newName);
        }

        try {
            category.rename(newName, newColor, clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "CATEGORY_RENAMED",
                "CATEGORY",
                category.getId(),
                Map.of("name", category.getName(), "color", category.getColor()));
        return CategoryView.from(category);
    }

    @Transactional
    public void archive(TarpeistoPrincipal principal, UUID categoryId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        Category category = requireCategory(principal.organizationId(), categoryId);
        category.archive(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "CATEGORY_ARCHIVED",
                "CATEGORY",
                category.getId(),
                null);
    }

    @Transactional
    public void restore(TarpeistoPrincipal principal, UUID categoryId) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        Category category = requireCategory(principal.organizationId(), categoryId);
        category.restore(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "CATEGORY_RESTORED",
                "CATEGORY",
                category.getId(),
                null);
    }

    @Transactional
    public void delete(TarpeistoPrincipal principal, UUID categoryId) {
        if (principal != null) principal.requirePermanent();
        requireOwner(principal);
        lockOrganization(principal.organizationId());
        Category category = requireCategory(principal.organizationId(), categoryId);
        if (assetModelRepository.existsByOrganizationIdAndCategoryId(principal.organizationId(), categoryId)) {
            throw new ValidationFailedException("A category referenced by an asset model cannot be deleted.");
        }
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "CATEGORY_DELETED",
                "CATEGORY",
                category.getId(),
                Map.of("name", category.getName(), "color", category.getColor()));
        categoryRepository.delete(category);
        categoryRepository.flush();
    }

    /**
     * Resolves an active, organization-scoped category for {@code AssetModelService} to reference.
     * Rejects an archived category (specification section 5: "cannot be selected for new models")
     * and a cross-organization or unknown id identically (404-vs-403 policy).
     */
    @Transactional(readOnly = true)
    Category requireActiveCategoryForSelection(UUID organizationId, UUID categoryId) {
        Category category = categoryRepository
                .findByIdAndOrganizationId(categoryId, organizationId)
                .orElseThrow(CategoryService::categoryNotFound);
        if (category.isArchived()) {
            throw new ValidationFailedException("Category is archived and cannot be selected.");
        }
        return category;
    }

    private Category requireCategory(UUID organizationId, UUID categoryId) {
        return categoryRepository
                .findByIdAndOrganizationId(categoryId, organizationId)
                .orElseThrow(CategoryService::categoryNotFound);
    }

    private void requireNameAvailable(UUID organizationId, String name) {
        if (categoryRepository
                .findByOrganizationIdAndNameIgnoreCaseAndArchivedAtIsNull(
                        organizationId, name == null ? "" : name.trim())
                .isPresent()) {
            throw new ValidationFailedException("A category with this name already exists.");
        }
    }

    private static NotFoundException categoryNotFound() {
        return new NotFoundException("Category not found.");
    }

    private void requireOwnerOrDeputy(TarpeistoPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)) {
            throw new AccessDeniedException("Owner or Deputy role required.");
        }
    }

    private void requireOwner(TarpeistoPrincipal principal) {
        if (principal == null || principal.role() != OrganizationRole.OWNER) {
            throw new AccessDeniedException("Owner role required.");
        }
    }

    private void lockOrganization(UUID organizationId) {
        organizationRepository
                .findWithLockById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private void requireOrganizationMember(TarpeistoPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
    }
}
