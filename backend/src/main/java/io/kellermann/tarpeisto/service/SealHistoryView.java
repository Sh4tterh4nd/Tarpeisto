package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.SealHistoryAction;
import java.time.Instant;

public record SealHistoryView(SealHistoryAction action, Instant occurredAt) {}
