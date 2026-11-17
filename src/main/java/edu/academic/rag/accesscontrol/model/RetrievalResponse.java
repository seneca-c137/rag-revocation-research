package edu.academic.rag.accesscontrol.model;

import java.util.Collections;
import java.util.List;

/**
 * Result of the RAG retrieval pipeline including retrieved documents,
 * cache hit telemetry, latency measurements, and audit decisions.
 */
public class RetrievalResponse {
    private final String query;
    private final String userId;
    private final List<Document> documents;
    private final int candidateCount;
    private final int authorizedCount;
    private final boolean cacheHit;
    private final long latencyNs;
    private final List<AuthorizationDecision> auditDecisions;

    public RetrievalResponse(String query, String userId, List<Document> documents,
                             int candidateCount, int authorizedCount, boolean cacheHit,
                             long latencyNs, List<AuthorizationDecision> auditDecisions) {
        this.query = query;
        this.userId = userId;
        this.documents = documents != null ? documents : Collections.emptyList();
        this.candidateCount = candidateCount;
        this.authorizedCount = authorizedCount;
        this.cacheHit = cacheHit;
        this.latencyNs = latencyNs;
        this.auditDecisions = auditDecisions != null ? auditDecisions : Collections.emptyList();
    }

    public String getQuery() {
        return query;
    }

    public String getUserId() {
        return userId;
    }

    public List<Document> getDocuments() {
        return documents;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getAuthorizedCount() {
        return authorizedCount;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public long getLatencyNs() {
        return latencyNs;
    }

    public double getLatencyMs() {
        return latencyNs / 1_000_000.0;
    }

    public List<AuthorizationDecision> getAuditDecisions() {
        return auditDecisions;
    }

    @Override
    public String toString() {
        return "RetrievalResponse{" +
                "query='" + query + '\'' +
                ", userId='" + userId + '\'' +
                ", returned=" + documents.size() +
                ", candidateCount=" + candidateCount +
                ", cacheHit=" + cacheHit +
                ", latencyMs=" + String.format("%.3f", getLatencyMs()) +
                '}';
    }
}
