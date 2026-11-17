package edu.academic.rag.accesscontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.academic.rag.accesscontrol.dataset.SyntheticDatasetExporter;
import edu.academic.rag.accesscontrol.dataset.SyntheticDatasetGenerator;
import edu.academic.rag.accesscontrol.experiment.RepeatedExperimentLogger;
import edu.academic.rag.accesscontrol.experiment.RepeatedExperimentRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class RepeatedExperimentRunnerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void runsAllScenariosForEachDatasetAndKeepsWarmupsOutOfAggregates() throws Exception {
        SyntheticDatasetGenerator generator = new SyntheticDatasetGenerator();
        Path datasetDirectory = tempDirectory.resolve("datasets");
        new SyntheticDatasetExporter().exportAll(generator.generateAll(), datasetDirectory);

        RepeatedExperimentRunner.Results results = new RepeatedExperimentRunner()
                .run(datasetDirectory, 1, 1, SyntheticDatasetGenerator.DEFAULT_SEED);

        assertEquals(56, results.trials().size());
        assertEquals(80, results.requests().size());
        assertEquals(56, results.revocationEvents().size());
        assertEquals(4, results.datasetSummaries().size());
        assertTrue(results.trials().stream().allMatch(RepeatedExperimentRunner.TrialObservation::passed));
        assertEquals(Set.of("authorized-retrieval", "unauthorized-retrieval",
                        "fresh-retrieval-revocation", "cached-retrieval-revocation",
                        "multi-document-cache-revalidation", "revocation-event-processing", "precision-at-k"),
                results.trials().stream().map(RepeatedExperimentRunner.TrialObservation::experiment)
                        .collect(Collectors.toSet()));
        assertEquals(28L, results.trials().stream()
                .filter(RepeatedExperimentRunner.TrialObservation::warmup).count());
        for (RepeatedExperimentRunner.DatasetSummary summary : results.datasetSummaries()) {
            assertEquals(7, summary.trialCount());
            assertEquals(0, summary.failedTrials());
            assertEquals(10, summary.totalRetrievalRequests());
            assertEquals(7, summary.generatedRevocationEvents());
            assertEquals(5, summary.revocationEnforcementLatency().sampleCount());
            assertEquals(6, summary.freshRetrievalLatency().sampleCount());
            assertEquals(1, summary.cachedRetrievalLatency().sampleCount());
            assertEquals(3, summary.postRevocationRetrievalLatency().sampleCount());
            assertEquals(7, summary.revocationEventProcessingLatency().sampleCount());
            assertNotNull(summary.freshRetrievalLatency().meanMs());
            assertTrue(summary.freshRetrievalLatency().p99Ms() >= summary.freshRetrievalLatency().p95Ms());
            assertEquals(1.0, summary.meanPrecisionAtK());
            assertEquals(1.0, summary.meanAuthorizedRelevantPrecisionAtK());
        }
        assertTrue(results.requests().stream().allMatch(request -> request.latencyNs() > 0));
        assertTrue(results.requests().stream().anyMatch(RepeatedExperimentRunner.RequestObservation::warmup));
        assertTrue(results.requests().stream().anyMatch(request -> !request.warmup()));
    }

    @Test
    void exportsRawAggregatedAndReproducibilityFilesWithEveryDatasetSize() throws Exception {
        Path datasetDirectory = tempDirectory.resolve("datasets");
        new SyntheticDatasetExporter().exportAll(new SyntheticDatasetGenerator().generateAll(), datasetDirectory);
        RepeatedExperimentRunner.Results results = new RepeatedExperimentRunner()
                .run(datasetDirectory, 1, 1, SyntheticDatasetGenerator.DEFAULT_SEED);
        Path outputDirectory = tempDirectory.resolve("results");
        new RepeatedExperimentLogger().export(results, outputDirectory);

        Path rawJson = outputDirectory.resolve("repeated_experiment_raw.json");
        Path rawCsv = outputDirectory.resolve("repeated_experiment_raw.csv");
        Path summaryJson = outputDirectory.resolve("repeated_experiment_summary.json");
        Path summaryCsv = outputDirectory.resolve("repeated_experiment_summary.csv");
        Path metadataJson = outputDirectory.resolve("reproducibility_metadata.json");
        for (Path path : new Path[]{rawJson, rawCsv, summaryJson, summaryCsv, metadataJson}) {
            assertTrue(Files.size(path) > 0, "Missing export " + path);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode raw = mapper.readTree(rawJson.toFile());
        JsonNode metadata = mapper.readTree(metadataJson.toFile());
        assertEquals(56, raw.path("trials").size());
        assertEquals(80, raw.path("requests").size());
        assertEquals(56, raw.path("revocationEvents").size());
        assertEquals(1, metadata.path("repetitions").asInt());
        assertEquals(1, metadata.path("warmupRuns").asInt());
        assertEquals(4, mapper.readTree(summaryJson.toFile()).size());
        assertTrue(Files.readString(rawCsv).contains("precision"));
        assertTrue(Files.readString(summaryCsv).contains("postRevocationRetrieval.p99Ms"));
        assertEquals(SyntheticDatasetGenerator.DEFAULT_SEED, metadata.path("seed").asLong());
        assertTrue(metadata.path("configuration").path("warmupPolicy").asText().contains("excluded"));
        assertTrue(metadata.path("configuration").path("eventOutcomePolicy").asText().contains("Duplicate"));

        JsonNode events = raw.path("revocationEvents");
        assertTrue(events.elements().hasNext());
        for (JsonNode event : events) {
            assertFalse(event.path("generatedAt").asText().isEmpty());
            assertFalse(event.path("permissionCommittedAt").asText().isEmpty());
            assertFalse(event.path("processedAt").asText().isEmpty());
            assertTrue(event.path("eventProcessingLatencyMs").asDouble() > 0);
            if (!event.path("firstDeniedAt").isMissingNode() && !event.path("firstDeniedAt").isNull()) {
                assertTrue(event.path("revocationEnforcementLatencyMs").asDouble() >= 0);
            }
        }
    }
}
