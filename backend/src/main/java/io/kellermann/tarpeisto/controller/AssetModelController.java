package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.AssetModelService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asset model catalog administration (specification section 6). Every endpoint here is reachable
 * by any authenticated user; the "must be Owner or Deputy to mutate" decision is enforced in
 * {@link AssetModelService}, not here, per docs/DEVELOPMENT_POLICIES.md section 5.1.
 *
 * <p>There is no {@code organizationId} anywhere below: every operation is scoped to {@code
 * principal.organizationId()}, which a client cannot influence.
 */
@RestController
@RequestMapping("/api/v1/asset-models")
public class AssetModelController {

    private final AssetModelService assetModelService;

    public AssetModelController(AssetModelService assetModelService) {
        this.assetModelService = assetModelService;
    }

    @GetMapping
    public List<AssetModelResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeArchived) {
        return assetModelService.list(principal, includeArchived).stream()
                .map(AssetModelResponse::from)
                .toList();
    }

    @GetMapping("/{assetModelId}")
    public AssetModelResponse get(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetModelId) {
        return AssetModelResponse.from(assetModelService.get(principal, assetModelId));
    }

    @PostMapping
    public ResponseEntity<AssetModelResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @Valid @RequestBody CreateAssetModelRequest request) {
        var created = assetModelService.create(
                principal,
                request.name(),
                request.description(),
                request.categoryId(),
                request.replacementUrl(),
                request.trackingMode(),
                request.stockUnitLabel(),
                request.lowStockThreshold(),
                request.canContainAssets());
        return ResponseEntity.status(HttpStatus.CREATED).body(AssetModelResponse.from(created));
    }

    @PutMapping("/{assetModelId}")
    public AssetModelResponse rename(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody RenameAssetModelRequest request) {
        return AssetModelResponse.from(
                assetModelService.rename(principal, assetModelId, request.name(), request.description()));
    }

    @PutMapping("/{assetModelId}/category")
    public AssetModelResponse changeCategory(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody ChangeAssetModelCategoryRequest request) {
        return AssetModelResponse.from(assetModelService.changeCategory(principal, assetModelId, request.categoryId()));
    }

    @PutMapping("/{assetModelId}/replacement-url")
    public AssetModelResponse changeReplacementUrl(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @RequestBody ChangeReplacementUrlRequest request) {
        return AssetModelResponse.from(
                assetModelService.changeReplacementUrl(principal, assetModelId, request.replacementUrl()));
    }

    @PutMapping("/{assetModelId}/tracking-mode")
    public AssetModelResponse changeTrackingMode(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody ChangeTrackingModeRequest request) {
        return AssetModelResponse.from(assetModelService.changeTrackingMode(
                principal,
                assetModelId,
                request.trackingMode(),
                request.stockUnitLabel(),
                request.lowStockThreshold()));
    }

    @PutMapping("/{assetModelId}/can-contain-assets")
    public AssetModelResponse setCanContainAssets(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @RequestBody SetCanContainAssetsRequest request) {
        return AssetModelResponse.from(
                assetModelService.setCanContainAssets(principal, assetModelId, request.canContainAssets()));
    }

    @PostMapping("/{assetModelId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetModelId) {
        assetModelService.archive(principal, assetModelId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{assetModelId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetModelId) {
        assetModelService.restore(principal, assetModelId);
        return ResponseEntity.noContent().build();
    }
}
