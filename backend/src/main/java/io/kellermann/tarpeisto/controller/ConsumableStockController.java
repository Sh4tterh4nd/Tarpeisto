package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.StockMovementReason;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.ConsumableStockService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumable stock balances and the immutable stock-movement ledger (specification section 6.4).
 * Authorization ("must be Owner or Deputy to change a balance") is enforced in {@link
 * ConsumableStockService}, not here, per docs/DEVELOPMENT_POLICIES.md section 5.1.
 *
 * <p>There is no {@code organizationId} anywhere below: every operation is scoped to {@code
 * principal.organizationId()}, which a client cannot influence.
 */
@RestController
@RequestMapping("/api/v1")
public class ConsumableStockController {

    private final ConsumableStockService consumableStockService;

    public ConsumableStockController(ConsumableStockService consumableStockService) {
        this.consumableStockService = consumableStockService;
    }

    @GetMapping("/asset-models/{assetModelId}/consumable-stock")
    public List<ConsumableStockResponse> listByModel(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetModelId) {
        return consumableStockService.listByModel(principal, assetModelId).stream()
                .map(ConsumableStockResponse::from)
                .toList();
    }

    @GetMapping("/assets/{assetId}/consumable-stock")
    public List<ConsumableStockResponse> listAtContainer(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return consumableStockService.listAtContainer(principal, assetId).stream()
                .map(ConsumableStockResponse::from)
                .toList();
    }

    @GetMapping("/locations/{locationId}/consumable-stock")
    public List<ConsumableStockResponse> listAtLocation(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID locationId) {
        return consumableStockService.listAtLocation(principal, locationId).stream()
                .map(ConsumableStockResponse::from)
                .toList();
    }

    @GetMapping("/asset-models/{assetModelId}/consumable-stock/summary")
    public AssetModelStockSummaryResponse summary(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetModelId) {
        return AssetModelStockSummaryResponse.from(consumableStockService.stockSummary(principal, assetModelId));
    }

    @GetMapping("/consumable-stock/low-stock")
    public List<AssetModelStockSummaryResponse> lowStock(@AuthenticationPrincipal TarpeistoPrincipal principal) {
        return consumableStockService.lowStockSummaries(principal).stream()
                .map(AssetModelStockSummaryResponse::from)
                .toList();
    }

    @GetMapping("/consumable-stock/{balanceId}")
    public ConsumableStockResponse get(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID balanceId) {
        return ConsumableStockResponse.from(consumableStockService.get(principal, balanceId));
    }

    @GetMapping("/consumable-stock/{balanceId}/movements")
    public List<StockMovementResponse> movements(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID balanceId) {
        return consumableStockService.ledger(principal, balanceId).stream()
                .map(StockMovementResponse::from)
                .toList();
    }

    @PostMapping("/asset-models/{assetModelId}/consumable-stock/receive")
    public ConsumableStockResponse receive(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody ReceiveStockRequest request) {
        return ConsumableStockResponse.from(consumableStockService.changeAtPlace(
                principal,
                assetModelId,
                request.containerAssetId(),
                request.locationId(),
                request.quantity(),
                StockMovementReason.RECEIPT,
                request.note(),
                null,
                null));
    }

    @PostMapping("/asset-models/{assetModelId}/consumable-stock/issue")
    public ConsumableStockResponse issue(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody IssueStockRequest request) {
        return ConsumableStockResponse.from(consumableStockService.changeAtPlace(
                principal,
                assetModelId,
                request.containerAssetId(),
                request.locationId(),
                request.quantity().negate(),
                StockMovementReason.EVENT_ISSUE,
                request.note(),
                request.eventReferenceId(),
                null));
    }

    @PostMapping("/asset-models/{assetModelId}/consumable-stock/return")
    public ConsumableStockResponse returnStock(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody ReturnStockRequest request) {
        return ConsumableStockResponse.from(consumableStockService.changeAtPlace(
                principal,
                assetModelId,
                request.containerAssetId(),
                request.locationId(),
                request.quantity(),
                StockMovementReason.EVENT_RETURN,
                request.note(),
                request.eventReferenceId(),
                null));
    }

    @PostMapping("/asset-models/{assetModelId}/consumable-stock/consume")
    public ConsumableStockResponse consume(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody ConsumeStockRequest request) {
        return ConsumableStockResponse.from(consumableStockService.changeAtPlace(
                principal,
                assetModelId,
                request.containerAssetId(),
                request.locationId(),
                request.quantity().negate(),
                StockMovementReason.CONSUMPTION,
                request.note(),
                null,
                null));
    }

    @PostMapping("/asset-models/{assetModelId}/consumable-stock/transfer")
    public StockTransferResponse transfer(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody TransferStockRequest request) {
        return StockTransferResponse.from(consumableStockService.transferBetweenPlaces(
                principal,
                assetModelId,
                request.sourceContainerAssetId(),
                request.sourceLocationId(),
                request.destinationContainerAssetId(),
                request.destinationLocationId(),
                request.quantity(),
                request.note()));
    }

    @PostMapping("/asset-models/{assetModelId}/consumable-stock/adjust")
    public ConsumableStockResponse adjust(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody AdjustStockRequest request) {
        return ConsumableStockResponse.from(consumableStockService.adjustAtPlace(
                principal,
                assetModelId,
                request.containerAssetId(),
                request.locationId(),
                request.delta(),
                request.type(),
                request.reason(),
                request.type() == StockMovementReason.AUDIT_ADJUSTMENT ? request.auditReferenceId() : null));
    }
}
