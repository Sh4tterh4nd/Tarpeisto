package io.kellermann.tarpeisto.controller;

/** One bounded reconciliation page; cursor advances over both accepted and rejected candidates. */
public record PackingReconciliationResponse(int inspectedCount, int dismissedCount, String nextCursor) {}
