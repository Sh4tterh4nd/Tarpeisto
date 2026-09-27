package io.kellermann.tarpeisto.model;

/** Final, append-only Deputy/Owner decision for one immutable audit finding. */
public enum FindingResolutionAction {
    FOUND_AND_RETURNED,
    MOVE_TO_CORRECT_CONTAINER,
    REASSIGN_CURRENT_CONTAINER,
    MARK_LOST,
    MARK_DAMAGED,
    CREATE_REPAIR,
    MARK_DESTROYED,
    REPLACE_LABEL,
    DISMISS
}
