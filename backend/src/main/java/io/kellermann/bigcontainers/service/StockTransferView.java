package io.kellermann.bigcontainers.service;

/** Both sides of one completed transfer (specification section 6.4: "one linked movement operation"). */
public record StockTransferView(ConsumableStockView source, ConsumableStockView destination) {}
