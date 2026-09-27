package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UndoAuditScanRequest(@NotNull UUID operationId) {}
