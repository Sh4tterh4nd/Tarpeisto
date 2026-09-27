package io.kellermann.tarpeisto.controller;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Optional, non-persistent consumable observations for one packing evaluation. */
public record PackingPreviewRequest(Map<UUID, BigDecimal> observedConsumableQuantities) {}
