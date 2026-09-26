package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import io.kellermann.bigcontainers.service.AssetCustomFieldValueInput;
import io.kellermann.bigcontainers.service.AssetService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Physical asset administration (specification section 8) and model-defined custom field values
 * (specification section 7). Authorization ("must be Owner or Deputy to mutate") is enforced in
 * {@link AssetService}, not here, per docs/DEVELOPMENT_POLICIES.md section 5.1.
 *
 * <p>There is no {@code organizationId} anywhere below: every operation is scoped to {@code
 * principal.organizationId()}, which a client cannot influence.
 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/asset-models/{assetModelId}/assets")
    public List<AssetResponse> list(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetModelId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return assetService.list(principal, assetModelId, includeInactive).stream()
                .map(AssetResponse::from)
                .toList();
    }

    @PostMapping("/asset-models/{assetModelId}/assets")
    public ResponseEntity<AssetResponse> create(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody CreateAssetRequest request) {
        var created = assetService.create(
                principal, assetModelId, request.individualName(), request.purchaseDate(), toInputs(request.values()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AssetResponse.from(created));
    }

    @PostMapping("/asset-models/{assetModelId}/assets/bulk")
    public ResponseEntity<List<AssetResponse>> createBulk(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody BulkCreateAssetsRequest request) {
        var created = assetService.createBulk(principal, assetModelId, request.count(), request.purchaseDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(created.stream().map(AssetResponse::from).toList());
    }

    @GetMapping("/assets/{assetId}")
    public AssetResponse get(@AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID assetId) {
        return AssetResponse.from(assetService.get(principal, assetId));
    }

    @GetMapping("/assets/by-code/{rawCode}")
    public AssetResponse getByCode(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable String rawCode) {
        return AssetResponse.from(assetService.getByCode(principal, rawCode));
    }

    @PutMapping("/assets/{assetId}/name")
    public AssetResponse rename(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody RenameAssetRequest request) {
        return AssetResponse.from(assetService.rename(principal, assetId, request.individualName()));
    }

    @PutMapping("/assets/{assetId}/purchase-date")
    public AssetResponse changePurchaseDate(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody SetAssetPurchaseDateRequest request) {
        return AssetResponse.from(assetService.changePurchaseDate(principal, assetId, request.purchaseDate()));
    }

    @PutMapping("/assets/{assetId}/values")
    public AssetResponse setValues(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody List<AssetCustomFieldValueRequest> values) {
        return AssetResponse.from(assetService.setValues(principal, assetId, toInputs(values)));
    }

    @PutMapping("/assets/{assetId}/condition")
    public AssetResponse changeCondition(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetId,
            @Valid @RequestBody ChangeAssetConditionRequest request) {
        return AssetResponse.from(
                assetService.changeCondition(principal, assetId, request.condition(), request.reason()));
    }

    @PutMapping("/assets/{assetId}/lifecycle")
    public AssetResponse changeLifecycleState(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID assetId,
            @Valid @RequestBody ChangeAssetLifecycleStateRequest request) {
        return AssetResponse.from(
                assetService.changeLifecycleState(principal, assetId, request.lifecycleState(), request.reason()));
    }

    @GetMapping("/assets/{assetId}/history")
    public List<AssetStateChangeResponse> history(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID assetId) {
        return assetService.history(principal, assetId).stream()
                .map(AssetStateChangeResponse::from)
                .toList();
    }

    @PostMapping("/assets/{assetId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID assetId) {
        assetService.archive(principal, assetId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets/{assetId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID assetId) {
        assetService.restore(principal, assetId);
        return ResponseEntity.noContent().build();
    }

    private static List<AssetCustomFieldValueInput> toInputs(List<AssetCustomFieldValueRequest> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> new AssetCustomFieldValueInput(
                        value.fieldId(), value.stringValue(), value.dateValue(), value.optionId()))
                .toList();
    }
}
