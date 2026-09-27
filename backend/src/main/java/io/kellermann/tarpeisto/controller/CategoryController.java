package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category catalog administration (specification section 5). Every endpoint here is reachable by
 * any authenticated user (the default {@code anyRequest().authenticated()} rule in {@code
 * SecurityConfiguration}); the actual "must be Owner or Deputy to mutate" decision is enforced in
 * {@link CategoryService}, not here - this controller carries no authorization shortcut, per
 * docs/DEVELOPMENT_POLICIES.md section 5.1.
 *
 * <p>There is no {@code organizationId} request parameter, path variable, or body field anywhere
 * below: every operation is scoped to {@code principal.organizationId()}, the server-side
 * active-organization context, which a client cannot influence.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryResponse> list(@AuthenticationPrincipal TarpeistoPrincipal principal) {
        return categoryService.list(principal).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @Valid @RequestBody CreateCategoryRequest request) {
        var created = categoryService.create(principal, request.name(), request.color());
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(created));
    }

    @PutMapping("/{categoryId}")
    public CategoryResponse rename(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID categoryId,
            @Valid @RequestBody RenameCategoryRequest request) {
        return CategoryResponse.from(categoryService.rename(principal, categoryId, request.name(), request.color()));
    }

    @PostMapping("/{categoryId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID categoryId) {
        categoryService.archive(principal, categoryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{categoryId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID categoryId) {
        categoryService.restore(principal, categoryId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID categoryId) {
        categoryService.delete(principal, categoryId);
        return ResponseEntity.noContent().build();
    }
}
