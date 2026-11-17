package edu.academic.rag.accesscontrol.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Canonical audit record capturing a permission transition event (grant or revocation).
 * Encapsulates the affected principal, target type (USER or ROLE),
 * target document identifier, transition action (e.g., GRANT or REVOKE),
 * previous permission state, updated permission state, rationale, and exact evaluation timestamp.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RevocationRecord(
        @JsonProperty("principal") String principal,
        @JsonProperty("targetType") String targetType,
        @JsonProperty("documentId") String documentId,
        @JsonProperty("action") String action,
        @JsonProperty("previousState") PermissionState previousState,
        @JsonProperty("updatedState") PermissionState updatedState,
        @JsonProperty("reason") String reason,
        @JsonProperty("timestamp") Instant timestamp
) {
    @JsonCreator
    public RevocationRecord {
        if (principal == null) principal = "UNKNOWN";
        if (targetType == null) targetType = "UNKNOWN";
        if (documentId == null) documentId = "UNKNOWN";
        if (action == null || action.isBlank()) {
            action = (updatedState == PermissionState.GRANTED) ? "GRANT" : "REVOKE";
        }
        if (previousState == null) previousState = PermissionState.UNSPECIFIED;
        if (updatedState == null) {
            updatedState = "GRANT".equalsIgnoreCase(action) ? PermissionState.GRANTED : PermissionState.REVOKED;
        }
        if (reason == null || reason.isBlank()) reason = "No reason specified";
        if (timestamp == null) timestamp = Instant.now();
    }

    /**
     * Backward-compatible constructor capturing revocation without explicit previous/updated states.
     */
    public RevocationRecord(String principal, String targetType, String documentId, String reason, Instant timestamp) {
        this(principal, targetType, documentId, "REVOKE", PermissionState.UNSPECIFIED, PermissionState.REVOKED, reason, timestamp);
    }

    /**
     * Compatibility constructor preserving previous argument order.
     */
    public RevocationRecord(String principal, String targetType, String documentId, Instant timestamp, String reason) {
        this(principal, targetType, documentId, "REVOKE", PermissionState.UNSPECIFIED, PermissionState.REVOKED, reason, timestamp);
    }

    /**
     * Convenience constructor defaulting to current timestamp and REVOKE action.
     */
    public RevocationRecord(String principal, String targetType, String documentId, String reason) {
        this(principal, targetType, documentId, "REVOKE", PermissionState.UNSPECIFIED, PermissionState.REVOKED, reason, Instant.now());
    }

    /**
     * Convenience constructor for state transitions with default timestamp.
     */
    public RevocationRecord(String principal, String targetType, String documentId,
                            String action, PermissionState previousState, PermissionState updatedState,
                            String reason) {
        this(principal, targetType, documentId, action, previousState, updatedState, reason, Instant.now());
    }

    public String getPrincipal() {
        return principal;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getAction() {
        return action;
    }

    public PermissionState getPreviousState() {
        return previousState;
    }

    public PermissionState getUpdatedState() {
        return updatedState;
    }

    public String getReason() {
        return reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
