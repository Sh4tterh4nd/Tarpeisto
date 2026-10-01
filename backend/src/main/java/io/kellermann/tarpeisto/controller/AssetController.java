package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.AssetCustomFieldValueInput;
import io.kellermann.tarpeisto.service.AssetService;
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
    private final io.kellermann.tarpeisto.service.AssetPlacementService assetPlacementService;
    private final io.kellermann.tarpeisto.service.AssetSealService assetSealService;

    public AssetController(
            AssetService assetService,
            io.kellermann.tarpeisto.service.AssetPlacementService assetPlacementService,
            io.kellermann.tarpeisto.service.AssetSealService assetSealService) {
        this.assetService = assetService;
        this.assetPlacementService = assetPlacementService;
        this.assetSealService = assetSealService;
    }

    @GetMapping("/asset-models/{assetModelId}/assets")
    public List<AssetResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return assetService.list(principal, assetModelId, includeInactive).stream()
                .map(AssetResponse::from)
                .toList();
    }

    @GetMapping("/assets")
    public AssetSearchPageResponse search(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean containerOnly,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) io.kellermann.tarpeisto.model.Condition condition,
            @RequestParam(required = false) io.kellermann.tarpeisto.model.LifecycleState lifecycle,
            @RequestParam(required = false) UUID bookingId,
            @RequestParam(required = false) io.kellermann.tarpeisto.model.BookingStatus bookingStatus,
            @RequestParam(required = false) Boolean openRepair,
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) Boolean metadataIncomplete,
            @RequestParam(required = false) String availability) {
        return AssetSearchPageResponse.from(assetService.search(
                principal,
                query,
                category,
                containerOnly,
                includeInactive,
                sort,
                direction,
                limit,
                cursor,
                new io.kellermann.tarpeisto.model.AssetSearchFilter(
                        includeArchived,
                        locationId,
                        condition,
                        lifecycle,
                        bookingId,
                        bookingStatus,
                        openRepair,
                        auditStatus,
                        metadataIncomplete,
                        availability)));
    }

    @PostMapping("/asset-models/{assetModelId}/assets")
    public ResponseEntity<AssetResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody CreateAssetRequest request) {
        var created = assetService.create(
                principal, assetModelId, request.individualName(), request.purchaseDate(), toInputs(request.values()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AssetResponse.from(created));
    }

    @PostMapping("/asset-models/{assetModelId}/assets/bulk")
    public ResponseEntity<List<AssetResponse>> createBulk(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody BulkCreateAssetsRequest request) {
        var created = assetService.createBulk(principal, assetModelId, request.count(), request.purchaseDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(created.stream().map(AssetResponse::from).toList());
    }

    @PostMapping("/assets/{assetId}/replacement")
    public ResponseEntity<AssetResponse> createReplacement(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody CreateReplacementAssetRequest request) {
        var created = assetService.createReplacement(
                principal, assetId, request.individualName(), request.purchaseDate(), toInputs(request.values()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AssetResponse.from(created));
    }

    @GetMapping("/assets/{assetId}")
    public AssetResponse get(@AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return AssetResponse.from(assetService.get(principal, assetId));
    }

    @GetMapping("/assets/{assetId}/placement")
    public AssetPlacementResponse placement(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return AssetPlacementResponse.from(assetPlacementService.get(principal, assetId));
    }

    @PutMapping("/assets/{assetId}/placement")
    public AssetPlacementResponse move(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody AssetPlacementRequest request) {
        return AssetPlacementResponse.from(assetPlacementService.move(
                principal, assetId, request.locationId(), request.parentContainerAssetId(), request.expectedVersion()));
    }

    @GetMapping("/assets/{assetId}/contents")
    public List<AssetPlacementResponse> contents(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return assetPlacementService.contents(principal, assetId).stream()
                .map(AssetPlacementResponse::from)
                .toList();
    }

    @GetMapping("/assets/by-code/{rawCode}")
    public AssetResponse getByCode(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable String rawCode) {
        return AssetResponse.from(assetService.getByCode(principal, rawCode));
    }

    @PutMapping("/assets/{assetId}/name")
    public AssetResponse rename(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody RenameAssetRequest request) {
        return AssetResponse.from(assetService.rename(principal, assetId, request.individualName()));
    }

    @PutMapping("/assets/{assetId}/purchase-date")
    public AssetResponse changePurchaseDate(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody SetAssetPurchaseDateRequest request) {
        return AssetResponse.from(assetService.changePurchaseDate(principal, assetId, request.purchaseDate()));
    }

    @PutMapping("/assets/{assetId}/values")
    public AssetResponse setValues(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody List<AssetCustomFieldValueRequest> values) {
        return AssetResponse.from(assetService.setValues(principal, assetId, toInputs(values)));
    }

    @PutMapping("/assets/{assetId}/condition")
    public AssetResponse changeCondition(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @Valid @RequestBody ChangeAssetConditionRequest request) {
        return AssetResponse.from(
                assetService.changeCondition(principal, assetId, request.condition(), request.reason()));
    }

    @PutMapping("/assets/{assetId}/lifecycle")
    public AssetResponse changeLifecycleState(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @Valid @RequestBody ChangeAssetLifecycleStateRequest request) {
        return AssetResponse.from(
                assetService.changeLifecycleState(principal, assetId, request.lifecycleState(), request.reason()));
    }

    @GetMapping("/assets/{assetId}/history")
    public List<AssetStateChangeResponse> history(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return assetService.history(principal, assetId).stream()
                .map(AssetStateChangeResponse::from)
                .toList();
    }

    @PostMapping("/assets/{assetId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        assetService.archive(principal, assetId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets/{assetId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        assetService.restore(principal, assetId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/assets/{assetId}/sealable")
    public ResponseEntity<Void> setSealable(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody SetAssetSealableRequest request) {
        assetSealService.setSealable(principal, assetId, request.sealable());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets/{assetId}/seal/apply")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> applySeal(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @Valid @RequestBody ApplySealRequest request) {
        assetSealService.applySeal(principal, assetId, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets/{assetId}/seal/break")
    public ResponseEntity<Void> breakSeal(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestBody(required = false) BreakSealRequest request) {
        assetSealService.breakSeal(principal, assetId, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assets/{assetId}/seal/history")
    public List<io.kellermann.tarpeisto.service.SealHistoryView> sealHistory(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return assetSealService.history(principal, assetId);
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
