package edu.academic.rag.accesscontrol.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Encapsulates the quantitative and qualitative outcome of an experimental test case.
 */
public class ExperimentResult {
    private final String testCaseId;
    private final String testCaseName;
    private final String scenario;
    private final String userId;
    private final boolean passed;
    private final double latencyMs;
    private final int documentsReturned;
    private final boolean cacheHit;
    private final String authorizationDecision;
    private final String details;
    private final Instant timestamp;

    @JsonCreator
    public ExperimentResult(
            @JsonProperty("testCaseId") String testCaseId,
            @JsonProperty("testCaseName") String testCaseName,
            @JsonProperty("scenario") String scenario,
            @JsonProperty("userId") String userId,
            @JsonProperty("passed") boolean passed,
            @JsonProperty("latencyMs") double latencyMs,
            @JsonProperty("documentsReturned") int documentsReturned,
            @JsonProperty("cacheHit") boolean cacheHit,
            @JsonProperty("authorizationDecision") String authorizationDecision,
            @JsonProperty("details") String details,
            @JsonProperty("timestamp") Instant timestamp) {
        this.testCaseId = testCaseId;
        this.testCaseName = testCaseName;
        this.scenario = scenario;
        this.userId = userId;
        this.passed = passed;
        this.latencyMs = latencyMs;
        this.documentsReturned = documentsReturned;
        this.cacheHit = cacheHit;
        this.authorizationDecision = authorizationDecision;
        this.details = details;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    public String getTestCaseId() {
        return testCaseId;
    }

    public String getTestCaseName() {
        return testCaseName;
    }

    public String getScenario() {
        return scenario;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isPassed() {
        return passed;
    }

    public double getLatencyMs() {
        return latencyMs;
    }

    public int getDocumentsReturned() {
        return documentsReturned;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public String getAuthorizationDecision() {
        return authorizationDecision;
    }

    public String getDetails() {
        return details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s | Passed=%s | Docs=%d | CacheHit=%s | Latency=%.3fms | %s",
                testCaseId, testCaseName, passed, documentsReturned, cacheHit, latencyMs, details);
    }
}
