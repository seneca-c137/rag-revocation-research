package edu.academic.rag.accesscontrol.model;

import java.time.Instant;

/**
 * Result of dynamic authorization validation for a user and document.
 */
public class AuthorizationDecision {
    private final boolean authorized;
    private final String reason;
    private final String userId;
    private final String documentId;
    private final Instant evaluationTimestamp;
    private final long evaluationDurationNs;

    public AuthorizationDecision(boolean authorized, String reason, String userId, String documentId,
                                 Instant evaluationTimestamp, long evaluationDurationNs) {
        this.authorized = authorized;
        this.reason = reason;
        this.userId = userId;
        this.documentId = documentId;
        this.evaluationTimestamp = evaluationTimestamp;
        this.evaluationDurationNs = evaluationDurationNs;
    }

    public static AuthorizationDecision allow(String userId, String documentId, String reason, long evaluationDurationNs) {
        return new AuthorizationDecision(true, reason, userId, documentId, Instant.now(), evaluationDurationNs);
    }

    public static AuthorizationDecision deny(String userId, String documentId, String reason, long evaluationDurationNs) {
        return new AuthorizationDecision(false, reason, userId, documentId, Instant.now(), evaluationDurationNs);
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public String getReason() {
        return reason;
    }

    public String getUserId() {
        return userId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public Instant getEvaluationTimestamp() {
        return evaluationTimestamp;
    }

    public long getEvaluationDurationNs() {
        return evaluationDurationNs;
    }

    public double getEvaluationDurationMs() {
        return evaluationDurationNs / 1_000_000.0;
    }

    @Override
    public String toString() {
        return (authorized ? "[ALLOWED] " : "[DENIED] ") +
                "User='" + userId + "', Doc='" + documentId +
                "', Reason='" + reason + "', Latency=" + String.format("%.3f ms", getEvaluationDurationMs());
    }
}
