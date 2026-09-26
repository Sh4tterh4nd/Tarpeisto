package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.SealHistoryAction;
import java.time.Instant;

public record SealHistoryView(SealHistoryAction action, Instant occurredAt) {}
