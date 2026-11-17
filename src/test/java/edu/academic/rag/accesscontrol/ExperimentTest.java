package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.experiment.ExperimentRunner;
import edu.academic.rag.accesscontrol.model.ExperimentResult;
import edu.academic.rag.accesscontrol.model.ReproducibilitySnapshot;
import edu.academic.rag.accesscontrol.model.RevocationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Experimental Evaluation Scenarios (TC-1 through TC-6)")
class ExperimentTest {
    private ExperimentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ExperimentRunner();
    }

    @Test
    @DisplayName("TC-1: Baseline Authorized Retrieval")
    void testCase1_BaselineAuthorizedRetrieval() {
        ExperimentResult result = runner.runTestCase1_BaselineAuthorizedRetrieval();
        assertTrue(result.isPassed(), "TC-1 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-2: Direct Permission Revocation Awareness")
    void testCase2_DirectPermissionRevocation() {
        ExperimentResult result = runner.runTestCase2_DirectPermissionRevocation();
        assertTrue(result.isPassed(), "TC-2 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-3: Dynamic Role Demotion and Clearance Downgrade")
    void testCase3_DynamicRoleDemotion() {
        ExperimentResult result = runner.runTestCase3_DynamicRoleDemotion();
        assertTrue(result.isPassed(), "TC-3 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-4: Cached Retrieval with Dynamic Authorization Revalidation")
    void testCase4_CachedRetrievalWithRevalidation() {
        ExperimentResult result = runner.runTestCase4_CachedRetrievalWithRevalidation();
        assertTrue(result.isPassed(), "TC-4 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-5: Multi-Attribute Metadata Filtering (ABAC Boundary Enforcement)")
    void testCase5_MultiAttributeMetadataFiltering() {
        ExperimentResult result = runner.runTestCase5_MultiAttributeMetadataFiltering();
        assertTrue(result.isPassed(), "TC-5 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-6: Zero-Privilege Unauthorized Denial")
    void testCase6_UnauthorizedAccessDenial() {
        ExperimentResult result = runner.runTestCase6_UnauthorizedAccessDenial();
        assertTrue(result.isPassed(), "TC-6 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("RC-1: Fresh Retrieval Revocation Enforcement")
    void testScenario1_FreshRetrievalRevocation() {
        ExperimentResult result = runner.runScenario1_FreshRetrievalRevocation();
        assertTrue(result.isPassed(), "RC-1 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("RC-2: Cached Retrieval Revocation Enforcement")
    void testScenario2_CachedRetrievalRevocation() {
        ExperimentResult result = runner.runScenario2_CachedRetrievalRevocation();
        assertTrue(result.isPassed(), "RC-2 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("RC-3: Cache Revalidation Keeps Authorized Documents While Removing Revoked Ones")
    void testScenario3_CacheRevalidation() {
        ExperimentResult result = runner.runScenario3_CacheRevalidation();
        assertTrue(result.isPassed(), "RC-3 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("Should generate reproducibility snapshot recording metadata and revocation history")
    void testReproducibilitySnapshotAndExportFlow() throws Exception {
        List<ExperimentResult> results = runner.runAllExperiments();
        assertEquals(9, results.size());
        assertTrue(results.stream().allMatch(ExperimentResult::isPassed));

        ReproducibilitySnapshot snapshot = runner.getLastSnapshot();
        assertNotNull(snapshot, "Reproducibility snapshot must not be null");
        assertNotNull(snapshot.getSnapshotId());
        assertNotNull(snapshot.getTimestamp());

        // 1. Verify experiment metadata
        Map<String, Object> metadata = snapshot.getExperimentMetadata();
        assertNotNull(metadata, "Experiment metadata must be present");
        assertEquals(9, ((Number) metadata.get("totalTests")).intValue());
        assertEquals(9, ((Number) metadata.get("passedTests")).intValue());
        assertEquals(0, ((Number) metadata.get("failedTests")).intValue());
        assertNotNull(metadata.get("javaVersion"));
        assertNotNull(metadata.get("osName"));
        assertTrue(((Number) metadata.get("meanLatencyMs")).doubleValue() >= 0.0);

        // 2. Verify revocation history recorded for this run
        List<RevocationRecord> revocations = snapshot.getRevocationHistory();
        assertNotNull(revocations);
        assertTrue(revocations.size() >= 2, "Run should capture revocations from the experiment scenarios");

        boolean hasTc2Revocation = revocations.stream().anyMatch(r ->
                "alice".equals(r.principal()) &&
                "DOC-FIN-01".equals(r.documentId()) &&
                "USER".equals(r.targetType()));
        boolean hasTc4Revocation = revocations.stream().anyMatch(r ->
                "alice".equals(r.principal()) &&
                "DOC-FIN-02".equals(r.documentId()) &&
                "USER".equals(r.targetType()));
        assertTrue(hasTc2Revocation, "Snapshot must record TC-2 revocation on DOC-FIN-01");
        assertTrue(hasTc4Revocation, "Snapshot must record TC-4 revocation on DOC-FIN-02");

        // 3. Verify existing CSV and JSON export files are generated and non-empty
        Path csvPath = Paths.get("results/experiment_results.csv");
        Path jsonPath = Paths.get("results/experiment_results.json");
        Path snapshotPath = Paths.get("results/reproducibility_snapshot.json");

        assertTrue(Files.exists(csvPath), "experiment_results.csv must exist");
        assertTrue(Files.exists(jsonPath), "experiment_results.json must exist");
        assertTrue(Files.exists(snapshotPath), "reproducibility_snapshot.json must exist");
        assertTrue(Files.size(csvPath) > 0, "CSV file must not be empty");
        assertTrue(Files.size(jsonPath) > 0, "JSON file must not be empty");
        assertTrue(Files.size(snapshotPath) > 0, "Snapshot file must not be empty");

        // 4. Verify deserialization of snapshot file
        ReproducibilitySnapshot loaded = runner.getLogger().loadReproducibilitySnapshot("results/reproducibility_snapshot.json");
        assertNotNull(loaded);
        assertEquals(snapshot.getSnapshotId(), loaded.getSnapshotId());
        assertTrue(loaded.getRevocationHistory().size() >= 2);
        assertEquals(9, loaded.getResults().size());
        assertTrue(runner.getRevocationMetrics().size() >= 3, "Revocation metrics should be collected for all three scenarios");

        assertTrue(Files.exists(Paths.get("results/revocation_metrics.json")), "Raw revocation metrics JSON should exist");
        assertTrue(Files.exists(Paths.get("results/revocation_summary.json")), "Revocation summary JSON should exist");

        var researchMetrics = runner.getResearchMetrics();
        assertEquals(17, researchMetrics.getRetrievalObservations().size());
        assertEquals(17, researchMetrics.getPrecisionObservations().size());
        assertEquals(5, researchMetrics.getRevocationObservations().size());
        assertTrue(researchMetrics.getRevocationObservations().stream()
                .allMatch(event -> event.firstDeniedAt() != null && event.revocationEnforcementLatencyMs() != null));
        assertEquals(0L, researchMetrics.summarize().get("requestsReturningUnauthorizedDocuments"));
        assertEquals(0L, researchMetrics.summarize().get("unauthorizedDocumentsReturned"));
        assertTrue(researchMetrics.getLatencyObservations().stream().anyMatch(sample -> "FRESH".equals(sample.category())));
        assertTrue(researchMetrics.getLatencyObservations().stream().anyMatch(sample -> "CACHED".equals(sample.category())));
        assertTrue(researchMetrics.getLatencyObservations().stream().anyMatch(sample -> "AFTER_REVOCATION".equals(sample.category())));
        assertEquals(9L, researchMetrics.getLatencyObservations().stream()
                .filter(sample -> "FRESH".equals(sample.category())).count());
        assertEquals(3L, researchMetrics.getLatencyObservations().stream()
                .filter(sample -> "CACHED".equals(sample.category())).count());
        assertEquals(5L, researchMetrics.getLatencyObservations().stream()
                .filter(sample -> "AFTER_REVOCATION".equals(sample.category())).count());

        Path rawResearchJson = Paths.get("results/research_metrics_raw.json");
        Path rawResearchCsv = Paths.get("results/research_metrics_raw.csv");
        Path summaryResearchJson = Paths.get("results/research_metrics_summary.json");
        Path summaryResearchCsv = Paths.get("results/research_metrics_summary.csv");
        assertTrue(Files.size(rawResearchJson) > 0);
        assertTrue(Files.size(rawResearchCsv) > 0);
        assertTrue(Files.size(summaryResearchJson) > 0);
        assertTrue(Files.size(summaryResearchCsv) > 0);
        assertTrue(Files.readString(rawResearchJson).contains("SIMULATED_QUERY_DOCUMENT_GROUND_TRUTH"));
        String rawCsvContent = Files.readString(rawResearchCsv);
        String summaryJsonContent = Files.readString(summaryResearchJson);
        assertTrue(rawCsvContent.contains("retrieval"));
        assertTrue(summaryJsonContent.contains("requestUnauthorizedRetrievalRate"));
        assertTrue(summaryJsonContent.contains("p95"));
        assertTrue(summaryJsonContent.contains("p99"));
    }
}
