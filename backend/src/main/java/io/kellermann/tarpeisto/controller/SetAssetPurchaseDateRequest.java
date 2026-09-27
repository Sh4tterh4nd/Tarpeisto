package io.kellermann.tarpeisto.controller;

import java.time.LocalDate;

/** Request body for {@code PUT /api/v1/assets/{assetId}/purchase-date}. */
public record SetAssetPurchaseDateRequest(LocalDate purchaseDate) {}
