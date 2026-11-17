package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.experiment.ExperimentRunner;
import edu.academic.rag.accesscontrol.experiment.ResearchMetricsCollector;
import edu.academic.rag.accesscontrol.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResearchMetricsCollectorTest {
    @Test
    void latencyStatisticsUsePopulationDeviationAndNearestRankPercentiles() {
        List<Double> samples = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToDouble(value -> value).boxed().toList();

        Map<String, Double> statistics = ResearchMetricsCollector.latencyStatistics(samples);

        assertEquals(50.5, statistics.get("mean"), 0.0001);
        assertEquals(50.5, statistics.get("median"), 0.0001);
        assertEquals(Math.sqrt(833.25), statistics.get("standardDeviation"), 0.0001);
        assertEquals(95.0, statistics.get("p95"));
        assertEquals(99.0, statistics.get("p99"));
        assertEquals(0.0, ResearchMetricsCollector.latencyStatistics(List.of()).get("p95"));
    }

    @Test
    void recordsExposureSeparatelyFromSimulatedRelevanceAndAuthorization() {
        ExperimentRunner runner = new ExperimentRunner();
        ResearchMetricsCollector collector = runner.getResearchMetrics();
        User eve = runner.getUserManager().getUser("eve").orElseThrow();
        Document financeReport = runner.getDocumentRepository().getDocument("DOC-FIN-01").orElseThrow();
        RetrievalRequest request = new RetrievalRequest("eve", "financial earnings", 2);
        RetrievalResponse response = new RetrievalResponse(
                request.getQuery(), request.getUserId(), List.of(financeReport), 1, 1, false,
                2_000_000L, List.of());

        collector.recordRetrieval(request, response, eve, runner.getAuthorizationService(), null);

        ResearchMetricsCollector.RetrievalObservation retrieval = collector.getRetrievalObservations().getFirst();
        ResearchMetricsCollector.DocumentObservation document = collector.getDocumentObservations().getFirst();
        ResearchMetricsCollector.PrecisionObservation precision = collector.getPrecisionObservations().getFirst();
        Map<String, Object> summary = collector.summarize();
        assertEquals(1L, summary.get("requestsReturningUnauthorizedDocuments"));
        assertEquals(1L, summary.get("unauthorizedDocumentsReturned"));
        assertEquals(1.0, summary.get("requestUnauthorizedRetrievalRate"));
        assertEquals(1.0, summary.get("documentUnauthorizedExposureRate"));
        assertFalse(document.authorized());
        assertTrue(document.relevant(), "Ground-truth relevance must not be inferred from authorization");
        assertEquals("SIMULATED_QUERY_DOCUMENT_GROUND_TRUTH", document.relevanceLabelSource());
        assertEquals(0.5, precision.precisionAtK());
        assertEquals(0.0, precision.authorizedRelevantPrecisionAtK());
        assertTrue(retrieval.returnedUnauthorizedDocument());
    }

    @Test
    void recordsProcessingAndEnforcementLatencyFromTheirOwnTimestampPairs() {
        ResearchMetricsCollector collector = new ResearchMetricsCollector();
        ResearchMetricsCollector.RevocationTicket ticket =
                collector.beginRevocation("alice", "DOC-FIN-01", false);
        Instant committedAt = ticket.processingStartedAt().plusMillis(5);
        Instant processedAt = committedAt.plusMillis(2);
        Instant deniedAt = processedAt.plusMillis(10);

        collector.recordRevocationProcessed(ticket, committedAt, processedAt);
        collector.recordFirstDenial(ticket, deniedAt);

        ResearchMetricsCollector.RevocationObservation event = collector.getRevocationObservations().getFirst();
        assertTrue(event.generatedAt().isBefore(event.processingStartedAt()) ||
                event.generatedAt().equals(event.processingStartedAt()));
        assertTrue(event.processingStartedAt().isBefore(event.permissionCommittedAt()));
        assertTrue(event.permissionCommittedAt().isBefore(event.firstDeniedAt()));
        assertEquals(12.0, event.revocationEnforcementLatencyMs(), 0.0001);
        assertEquals(Instant.parse(ticket.generatedAt().toString()), event.generatedAt());
        assertEquals(java.time.Duration.between(ticket.generatedAt(), event.processedAt()).toNanos() / 1_000_000.0,
                event.eventProcessingLatencyMs(), 0.0001);
        assertThrows(IllegalArgumentException.class,
                () -> collector.recordFirstDenial(ticket, committedAt.minusMillis(1)));
    }

    @Test
    void summarizesGeneratedProcessedFailedAndDuplicateRevocationEvents() {
        ResearchMetricsCollector collector = new ResearchMetricsCollector();
        ResearchMetricsCollector.RevocationTicket duplicate =
                collector.beginRevocation("alice", "DOC-FIN-01", true);
        Instant commit = Instant.now();
        collector.recordRevocationProcessed(duplicate, commit, commit.plusMillis(1));
        ResearchMetricsCollector.RevocationTicket failed =
                collector.beginRevocation("alice", "DOC-FIN-02", false);
        collector.recordRevocationFailed(failed, failed.processingStartedAt().plusMillis(3), "simulated test failure");

        Map<String, Object> summary = collector.summarize();
        assertEquals(2L, summary.get("generatedRevocationEvents"));
        assertEquals(0L, summary.get("processedRevocationEvents"));
        assertEquals(1L, summary.get("failedRevocationEvents"));
        assertEquals(1L, summary.get("duplicateRevocationEvents"));
        assertEquals(1L, summary.get("successfullyHandledRevocationEvents"));
        assertEquals(0.5, summary.get("revocationEventProcessingSuccessRate"));
    }
}
