package io.kellermann.bigcontainers.controller;

/** Request body for a display-order change on a custom field or dropdown option. */
public record ReorderRequest(int displayOrder) {}
