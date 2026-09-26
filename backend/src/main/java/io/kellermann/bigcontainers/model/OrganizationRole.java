package io.kellermann.bigcontainers.model;

/**
 * The permanent organization roles from specification section 4.1, in descending order of
 * privilege. {@link #OPERATOR_AUDITOR} represents the combined "Operator/Auditor" role.
 */
public enum OrganizationRole {
    OWNER,
    DEPUTY,
    OPERATOR_AUDITOR,
    VIEWER
}
