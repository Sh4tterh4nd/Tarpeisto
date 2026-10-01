package io.kellermann.tarpeisto.model;

/** Physical direct contents retain problematic/inactive units instead of hiding them. */
public enum PackingContentStatus {
    MATCHED,
    EXTRA,
    MISPLACED,
    INACTIVE
}
