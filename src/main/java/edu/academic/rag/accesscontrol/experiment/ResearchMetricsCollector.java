package edu.academic.rag.accesscontrol.experiment;

import edu.academic.rag.accesscontrol.model.*;
import edu.academic.rag.accesscontrol.service.AuthorizationService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Collects request-level and document-level observations for the deterministic
 * simulated experiments. Latency percentiles use nearest-rank (ceil(p*n)).
 */
public class ResearchMetricsCollector {
    private static final Map<String, Set<String>> SIMULATED_RELEVANCE_GROUND_TRUTH = Map.of(
            "financial earnings revenue", Set.of("DOC-FIN-01"),
            "financial earnings", Set.of("DOC-FIN-01"),
            "proprietary model weights", Set.of("DOC-ENG-02"),
            "acquisition strategy", Set.of("DOC-FIN-02"),
            "finance multi", Set.of("DOC-FIN-01", "DOC-FIN-02"),
            "strategy", Set.of("DOC-FIN-02", "DOC-HR-01", "DOC-EXEC-01")
    );

    private final List<RetrievalObservation> retrievalObservations = new ArrayList<>();
    private final List<DocumentObservation> documentObservations = new ArrayList<>();
    private final List<RevocationObservation> revocationObservations = new ArrayList<>();
    private final List<PrecisionObservation> precisionObservations = new ArrayList<>();
    private final List<LatencyObservation> latencyObservations = new ArrayList<>();
    private int nextRequestId;
    private int nextEventId;

    public record RevocationTicket(String eventId, String userId, String documentId,
                                  Instant generatedAt, Instant processingStartedAt,
                                  boolean duplicate) {}

    public record RetrievalObservation(String requestId, String userId, String query, int k,
                                       boolean cacheHit, String latencyCategory, double latencyMs,
                                       int documentsReturned, int unauthorizedDocuments,
                                       boolean returnedUnauthorizedDocument, Instant observedAt) {}

    public record DocumentObservation(String requestId, String userId, String documentId,
                                      boolean authorized, boolean relevant,
                                      String relevanceLabelSource, Instant observedAt) {}

    public record RevocationObservation(String eventId, String userId, String documentId,
                                       Instant generatedAt, Instant processingStartedAt,
                                       Instant permissionCommittedAt, Instant processedAt,
                                       Instant firstDeniedAt, String outcome, boolean duplicate,
                                       String failure, double eventProcessingLatencyMs,
                                       Double revocationEnforcementLatencyMs) {}

    public record PrecisionObservation(String requestId, String userId, String query, int k,
                                       int relevantRetrieved, int authorizedRelevantRetrieved,
                                       double precisionAtK, double authorizedRelevantPrecisionAtK,
                                       Instant observedAt) {}

    public record LatencyObservation(String requestId, String category, double latencyMs, Instant observedAt) {}

    public void clear() {
        retrievalObservations.clear();
        documentObservations.clear();
        revocationObservations.clear();
        precisionObservations.clear();
        latencyObservations.clear();
        nextRequestId = 0;
        nextEventId = 0;
    }

    public String recordRetrieval(RetrievalRequest request, RetrievalResponse response, User user,
                                  AuthorizationService authorizationService, RevocationTicket ticket) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(authorizationService, "authorizationService must not be null");
        String requestId = "request-" + (++nextRequestId);
        Instant observedAt = Instant.now();
        String category = ticket != null ? "AFTER_REVOCATION" :
                (response.isCacheHit() ? "CACHED" : "FRESH");
        int unauthorized = 0;
        int relevant = 0;
        int authorizedRelevant = 0;
        Set<String> relevantDocumentIds = SIMULATED_RELEVANCE_GROUND_TRUTH
                .getOrDefault(normalize(request.getQuery()), Collections.emptySet());

        List<Document> returnedDocuments = response.getDocuments();
        for (int index = 0; index < returnedDocuments.size(); index++) {
            Document document = returnedDocuments.get(index);
            boolean authorized = user != null &&
                    authorizationService.evaluate(user, document).isAuthorized();
            if (!authorized) unauthorized++;
            boolean isRelevant = relevantDocumentIds.contains(document.getDocumentId());
            if (index < request.getTopK() && isRelevant) {
                relevant++;
                if (authorized) authorizedRelevant++;
            }
            documentObservations.add(new DocumentObservation(
                    requestId, request.getUserId(), document.getDocumentId(), authorized, isRelevant,
                    "SIMULATED_QUERY_DOCUMENT_GROUND_TRUTH", observedAt));
        }

        int k = request.getTopK();
        retrievalObservations.add(new RetrievalObservation(
                requestId, request.getUserId(), request.getQuery(), k, response.isCacheHit(),
                category, response.getLatencyMs(), response.getDocuments().size(), unauthorized,
                unauthorized > 0, observedAt));
        latencyObservations.add(new LatencyObservation(requestId, category, response.getLatencyMs(), observedAt));
        precisionObservations.add(new PrecisionObservation(
                requestId, request.getUserId(), request.getQuery(), k, relevant, authorizedRelevant,
                k > 0 ? (double) relevant / k : 0.0,
                k > 0 ? (double) authorizedRelevant / k : 0.0, observedAt));

        return requestId;
    }

    public RevocationTicket beginRevocation(String userId, String documentId, boolean duplicate) {
        Instant generatedAt = Instant.now();
        return new RevocationTicket("revocation-" + (++nextEventId), userId, documentId,
                generatedAt, Instant.now(), duplicate);
    }

    public void recordRevocationProcessed(RevocationTicket ticket, Instant permissionCommittedAt) {
        recordRevocationProcessed(ticket, permissionCommittedAt, Instant.now());
    }

    public void recordRevocationProcessed(RevocationTicket ticket, Instant permissionCommittedAt, Instant processedAt) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        Objects.requireNonNull(permissionCommittedAt, "permissionCommittedAt must not be null");
        Objects.requireNonNull(processedAt, "processedAt must not be null");
        validateTimestampOrder(ticket.generatedAt(), ticket.processingStartedAt(), permissionCommittedAt);
        if (processedAt.isBefore(permissionCommittedAt)) {
            throw new IllegalArgumentException("processedAt must not precede permission commit");
        }
        double processingLatency = elapsedMs(ticket.generatedAt(), processedAt);
        revocationObservations.add(new RevocationObservation(
                ticket.eventId(), ticket.userId(), ticket.documentId(), ticket.generatedAt(),
                ticket.processingStartedAt(), permissionCommittedAt, processedAt, null,
                ticket.duplicate() ? "DUPLICATE" : "PROCESSED", ticket.duplicate(), null,
                processingLatency, null));
    }

    public void recordRevocationFailed(RevocationTicket ticket, Instant failedAt, String failure) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        validateTimestampOrder(ticket.generatedAt(), ticket.processingStartedAt(), failedAt);
        revocationObservations.add(new RevocationObservation(
                ticket.eventId(), ticket.userId(), ticket.documentId(), ticket.generatedAt(),
                ticket.processingStartedAt(), null, failedAt, null, "FAILED", ticket.duplicate(),
                failure, elapsedMs(ticket.generatedAt(), failedAt), null));
    }

    public void recordFirstDenial(RevocationTicket ticket, Instant firstDeniedAt) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        Objects.requireNonNull(firstDeniedAt, "firstDeniedAt must not be null");
        for (int i = 0; i < revocationObservations.size(); i++) {
            RevocationObservation event = revocationObservations.get(i);
            if (event.eventId().equals(ticket.eventId())) {
                if (!"PROCESSED".equals(event.outcome()) && !"DUPLICATE".equals(event.outcome())) {
                    return;
                }
                if (firstDeniedAt.isBefore(event.permissionCommittedAt())) {
                    throw new IllegalArgumentException("firstDeniedAt must not precede permission commit");
                }
                revocationObservations.set(i, new RevocationObservation(
                        event.eventId(), event.userId(), event.documentId(), event.generatedAt(),
                        event.processingStartedAt(), event.permissionCommittedAt(), event.processedAt(),
                        firstDeniedAt, event.outcome(), event.duplicate(), event.failure(),
                        event.eventProcessingLatencyMs(),
                        elapsedMs(event.permissionCommittedAt(), firstDeniedAt)));
                return;
            }
        }
        throw new IllegalArgumentException("No processed revocation event for " + ticket.eventId());
    }

    private void validateTimestampOrder(Instant generatedAt, Instant processingStartedAt, Instant completedAt) {
        if (processingStartedAt.isBefore(generatedAt) || completedAt.isBefore(processingStartedAt)) {
            throw new IllegalArgumentException("Revocation timestamps must be nondecreasing");
        }
    }

    private double elapsedMs(Instant start, Instant end) {
        return Duration.between(start, end).toNanos() / 1_000_000.0;
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    public List<RetrievalObservation> getRetrievalObservations() {
        return List.copyOf(retrievalObservations);
    }

    public List<DocumentObservation> getDocumentObservations() {
        return List.copyOf(documentObservations);
    }

    public List<RevocationObservation> getRevocationObservations() {
        return List.copyOf(revocationObservations);
    }

    public List<PrecisionObservation> getPrecisionObservations() {
        return List.copyOf(precisionObservations);
    }

    public List<LatencyObservation> getLatencyObservations() {
        return List.copyOf(latencyObservations);
    }

    public Map<String, Object> summarize() {
        long unauthorizedRequests = retrievalObservations.stream()
                .filter(RetrievalObservation::returnedUnauthorizedDocument).count();
        long unauthorizedDocuments = retrievalObservations.stream()
                .mapToLong(RetrievalObservation::unauthorizedDocuments).sum();
        long generated = revocationObservations.size();
        long processed = revocationObservations.stream()
                .filter(event -> "PROCESSED".equals(event.outcome())).count();
        long failed = revocationObservations.stream().filter(event -> "FAILED".equals(event.outcome())).count();
        long duplicates = revocationObservations.stream().filter(RevocationObservation::duplicate).count();
        long successfullyHandled = processed + duplicates;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRetrievalRequests", retrievalObservations.size());
        summary.put("requestsReturningUnauthorizedDocuments", unauthorizedRequests);
        summary.put("totalDocumentsReturned", documentObservations.size());
        summary.put("unauthorizedDocumentsReturned", unauthorizedDocuments);
        summary.put("requestUnauthorizedRetrievalRate", rate(unauthorizedRequests, retrievalObservations.size()));
        summary.put("documentUnauthorizedExposureRate", rate(unauthorizedDocuments, documentObservations.size()));
        summary.put("generatedRevocationEvents", generated);
        summary.put("processedRevocationEvents", processed);
        summary.put("failedRevocationEvents", failed);
        summary.put("duplicateRevocationEvents", duplicates);
        summary.put("successfullyHandledRevocationEvents", successfullyHandled);
        summary.put("revocationEventProcessingSuccessRate", rate(successfullyHandled, generated));
        summary.put("eventProcessingLatencyDefinition", "processedAt minus generatedAt");
        summary.put("revocationEnforcementLatencyDefinition", "firstDeniedAt minus permissionCommittedAt");
        summary.put("retrievalLatencyMeasurement", "RetrievalResponse latency measured with System.nanoTime");
        summary.put("latencyPercentileConvention", "nearest-rank: sorted[ceil(p*n)-1]");
        summary.put("latencyStandardDeviationConvention", "population standard deviation");
        summary.put("precisionAtKDenominator", "K, including missing results when fewer than K are returned");
        summary.put("relevanceGroundTruthSource",
                "Deterministic simulated query-to-document mapping, independent of authorization decisions");
        summary.put("simulatedRelevanceGroundTruth", SIMULATED_RELEVANCE_GROUND_TRUTH);
        summary.put("meanPrecisionAtK", meanPrecision(false));
        summary.put("meanAuthorizedRelevantPrecisionAtK", meanPrecision(true));
        for (String category : List.of("FRESH", "CACHED", "AFTER_REVOCATION")) {
            List<Double> values = latencyObservations.stream()
                    .filter(sample -> category.equals(sample.category()))
                    .map(LatencyObservation::latencyMs).toList();
            summary.put(category.toLowerCase(Locale.ROOT) + "LatencyMs", latencyStatistics(values));
        }
        return summary;
    }

    private double meanPrecision(boolean authorizedRelevant) {
        if (precisionObservations.isEmpty()) return 0.0;
        return precisionObservations.stream().mapToDouble(observation -> authorizedRelevant
                ? observation.authorizedRelevantPrecisionAtK()
                : observation.precisionAtK()).average().orElse(0.0);
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    public static Map<String, Double> latencyStatistics(Collection<Double> samples) {
        List<Double> sorted = samples.stream().sorted().toList();
        Map<String, Double> stats = new LinkedHashMap<>();
        if (sorted.isEmpty()) {
            stats.put("count", 0.0);
            stats.put("mean", 0.0);
            stats.put("median", 0.0);
            stats.put("standardDeviation", 0.0);
            stats.put("p95", 0.0);
            stats.put("p99", 0.0);
            return stats;
        }
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = sorted.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0.0);
        stats.put("count", (double) sorted.size());
        stats.put("mean", mean);
        stats.put("median", sorted.size() % 2 == 0
                ? (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0
                : sorted.get(sorted.size() / 2));
        stats.put("standardDeviation", Math.sqrt(variance));
        stats.put("p95", nearestRank(sorted, 0.95));
        stats.put("p99", nearestRank(sorted, 0.99));
        return stats;
    }

    private static double nearestRank(List<Double> sorted, double percentile) {
        return sorted.get(Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
    }
}
