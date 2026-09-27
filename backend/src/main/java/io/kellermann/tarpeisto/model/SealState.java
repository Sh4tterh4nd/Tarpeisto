package io.kellermann.tarpeisto.model;

/** Current projection; the authoritative chronology remains {@link AssetSealHistory}. */
public enum SealState {
    UNSEALED,
    APPLIED,
    BROKEN,
    VERIFIED,
    INVALIDATED
}
