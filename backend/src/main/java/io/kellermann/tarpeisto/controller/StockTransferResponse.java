package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.StockTransferView;

/** Response for {@code POST /api/v1/asset-models/{assetModelId}/consumable-stock/transfer}. */
public record StockTransferResponse(ConsumableStockResponse source, ConsumableStockResponse destination) {

    public static StockTransferResponse from(StockTransferView view) {
        return new StockTransferResponse(
                ConsumableStockResponse.from(view.source()), ConsumableStockResponse.from(view.destination()));
    }
}
