package edu.academic.rag.accesscontrol.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

/**
 * Encapsulates a reproducibility snapshot capturing experiment metadata,
 * execution results, and the exact revocation history applied during the run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReproducibilitySnapshot(
        @JsonProperty("snapshotId") String snapshotId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("experimentMetadata") Map<String, Object> experimentMetadata,
        @JsonProperty("revocationHistory") List<RevocationRecord> revocationHistory,
        @JsonProperty("results") List<ExperimentResult> results
) {
    @JsonCreator
    public ReproducibilitySnapshot {
        if (snapshotId == null) {
            snapshotId = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (experimentMetadata == null) {
            experimentMetadata = Collections.emptyMap();
        } else {
            experimentMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(experimentMetadata));
        }
        if (revocationHistory == null) {
            revocationHistory = Collections.emptyList();
        } else {
            revocationHistory = Collections.unmodifiableList(new ArrayList<>(revocationHistory));
        }
        if (results == null) {
            results = Collections.emptyList();
        } else {
            results = Collections.unmodifiableList(new ArrayList<>(results));
        }
    }

    public ReproducibilitySnapshot(Map<String, Object> experimentMetadata,
                                   List<RevocationRecord> revocationHistory,
                                   List<ExperimentResult> results) {
        this(UUID.randomUUID().toString(), Instant.now(), experimentMetadata, revocationHistory, results);
    }

    public ReproducibilitySnapshot(Map<String, Object> experimentMetadata,
                                   List<RevocationRecord> revocationHistory) {
        this(UUID.randomUUID().toString(), Instant.now(), experimentMetadata, revocationHistory, Collections.emptyList());
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getExperimentMetadata() {
        return experimentMetadata;
    }

    public Map<String, Object> getMetadata() {
        return experimentMetadata;
    }

    public List<RevocationRecord> getRevocationHistory() {
        return revocationHistory;
    }

    public List<ExperimentResult> getResults() {
        return results;
    }
}
