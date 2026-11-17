package edu.academic.rag.accesscontrol.model;

/**
 * Canonical permission state model representing the explicit or implicit access state
 * of a principal (user or role) with respect to a target document.
 *
 * States:
 * - GRANTED: Explicitly permitted to access the document.
 * - REVOKED: Explicitly denied / blacklisted from accessing the document.
 * - UNSPECIFIED: No explicit grant or revocation recorded for the principal and document.
 */
public enum PermissionState {
    GRANTED,
    REVOKED,
    UNSPECIFIED;

    /**
     * Case-insensitive lookup returning UNSPECIFIED if null or unparseable.
     */
    public static PermissionState fromString(String val) {
        if (val == null) {
            return UNSPECIFIED;
        }
        try {
            return PermissionState.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNSPECIFIED;
        }
    }
}
