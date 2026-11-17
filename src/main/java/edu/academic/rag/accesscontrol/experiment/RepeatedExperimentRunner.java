package edu.academic.rag.accesscontrol.experiment;

import edu.academic.rag.accesscontrol.dataset.SyntheticDataset;
import edu.academic.rag.accesscontrol.dataset.SyntheticDatasetExporter;
import edu.academic.rag.accesscontrol.dataset.SyntheticDatasetGenerator;
import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.manager.UserManager;
import edu.academic.rag.accesscontrol.model.*;
import edu.academic.rag.accesscontrol.repository.DocumentRepository;
import edu.academic.rag.accesscontrol.service.AuthorizationService;
import edu.academic.rag.accesscontrol.service.CacheService;
import edu.academic.rag.accesscontrol.service.MetadataFilter;
import edu.academic.rag.accesscontrol.service.RetrievalService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs repeated, deterministic access-control experiments against exported
 * synthetic datasets. Retrieval and event-processing durations use System.nanoTime;
 * revocation enforcement durations use recorded wall-clock Instants.
 */
public final class RepeatedExperimentRunner {
    public static final int DEFAULT_REPETITIONS = 30;
    public static final int DEFAULT_WARMUP_RUNS = 1;
    public static final String CONFIGURATION_ID = "task6-v1";
    private static final String AUTHORIZED_USER_ID = "dataset-user-engineering";
    private static final String UNAUTHORIZED_QUERY_ID = "QUERY-FINANCE";
    private static final String AUTHORIZED_QUERY_ID = "QUERY-ENGINEERING";
    private static final int TOP_K = 5;
    private static final int MULTI_DOCUMENT_TOP_K = 20;
    private static final List<String> EXPERIMENTS = List.of(
            "authorized-retrieval",
            "unauthorized-retrieval",
            "fresh-retrieval-revocation",
            "cached-retrieval-revocation",
            "multi-document-cache-revalidation",
            "revocation-event-processing",
            "precision-at-k"
    );

    public Results run(Path datasetDirectory, int repetitions, int warmupRuns, long seed) throws IOException {
        Objects.requireNonNull(datasetDirectory, "datasetDirectory must not be null");
        if (repetitions <= 0 || warmupRuns <= 0) {
            throw new IllegalArgumentException("Repetitions and warmup runs must both be positive");
        }

        SyntheticDatasetExporter datasetExporter = new SyntheticDatasetExporter();
        List<TrialObservation> trials = new ArrayList<>();
        List<RequestObservation> requests = new ArrayList<>();
        List<DocumentObservation> documents = new ArrayList<>();
        List<RevocationEventObservation> events = new ArrayList<>();
        List<PrecisionObservation> precision = new ArrayList<>();
        int nextRequestId = 0;
        int nextEventId = 0;
        Instant runStartedAt = Instant.now();

        for (int size : SyntheticDatasetGenerator.supportedSizes()) {
            Path file = datasetDirectory.resolve(
                    SyntheticDatasetGenerator.GENERATOR_VERSION + "-" + size + "-" + seed + ".json");
            SyntheticDataset dataset = datasetExporter.load(file);
            validateDataset(dataset, size, seed);
            for (int warmup = 1; warmup <= warmupRuns; warmup++) {
                nextRequestId = runRepetition(dataset, -warmup, true, nextRequestId, nextEventId,
                        trials, requests, documents, events, precision);
                nextEventId = eventCounterAfter(events);
            }
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                nextRequestId = runRepetition(dataset, repetition, false, nextRequestId, nextEventId,
                        trials, requests, documents, events, precision);
                nextEventId = eventCounterAfter(events);
            }
        }

        ReproducibilityMetadata metadata = reproducibilityMetadata(repetitions, warmupRuns, seed, runStartedAt);
        List<DatasetSummary> summaries = summarize(metadata, trials, requests, events, precision);
        return new Results(metadata, List.copyOf(trials), List.copyOf(requests),
                List.copyOf(documents), List.copyOf(events), List.copyOf(precision), summaries);
    }

    public Results runDefault(Path datasetDirectory) throws IOException {
        return run(datasetDirectory, DEFAULT_REPETITIONS, DEFAULT_WARMUP_RUNS,
                SyntheticDatasetGenerator.DEFAULT_SEED);
    }

    private int runRepetition(SyntheticDataset dataset, int repetition, boolean warmup, int requestId,
                              int eventId, List<TrialObservation> trials,
                              List<RequestObservation> requests, List<DocumentObservation> documents,
                              List<RevocationEventObservation> events, List<PrecisionObservation> precision) {
        String query = queryText(dataset, AUTHORIZED_QUERY_ID);
        requestId = runScenario(dataset, repetition, warmup, "authorized-retrieval", requestId, eventId,
                trials, requests, documents, events, precision, context -> {
                    RequestObservation observation = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "FRESH");
                    boolean passed = observation.documentsReturned() > 0 &&
                            observation.unauthorizedDocuments() == 0;
                    return new ScenarioResult(passed, "Authorized results=" + observation.documentsReturned());
                });
        eventId = eventCounterAfter(events);
        requestId = runScenario(dataset, repetition, warmup, "unauthorized-retrieval", requestId, eventId,
                trials, requests, documents, events, precision, context -> {
                    RequestObservation observation = context.retrieve(AUTHORIZED_USER_ID, UNAUTHORIZED_QUERY_ID,
                            queryText(dataset, UNAUTHORIZED_QUERY_ID), TOP_K, "FRESH");
                    boolean passed = observation.documentsReturned() == 0 &&
                            observation.unauthorizedDocuments() == 0;
                    return new ScenarioResult(passed,
                            "Returned=" + observation.documentsReturned() +
                                    ", unauthorized=" + observation.unauthorizedDocuments());
                });
        eventId = eventCounterAfter(events);
        requestId = runScenario(dataset, repetition, warmup, "fresh-retrieval-revocation", requestId, eventId,
                trials, requests, documents, events, precision, context -> {
                    RequestObservation before = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "FRESH");
                    String target = firstDocumentId(before.documentIds());
                    RevocationTicket ticket = context.revoke(target);
                    context.environment.cacheService().invalidate(cacheKey(query));
                    RequestObservation after = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "AFTER_REVOCATION");
                    boolean denied = context.recordEffectiveDenial(ticket, after);
                    return new ScenarioResult(target != null && denied,
                            "Target=" + target + ", denied=" + denied);
                });
        eventId = eventCounterAfter(events);
        requestId = runScenario(dataset, repetition, warmup, "cached-retrieval-revocation", requestId, eventId,
                trials, requests, documents, events, precision, context -> {
                    RequestObservation fresh = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "FRESH");
                    String target = firstDocumentId(fresh.documentIds());
                    RequestObservation cached = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "CACHED");
                    RevocationTicket ticket = context.revoke(target);
                    RequestObservation after = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "AFTER_REVOCATION");
                    boolean denied = context.recordEffectiveDenial(ticket, after);
                    boolean passed = target != null && cached.cacheHit() && after.cacheHit() && denied;
                    return new ScenarioResult(passed,
                            "Pre-revoke cache hit=" + cached.cacheHit() + ", post-revoke cache hit=" +
                                    after.cacheHit() + ", denied=" + denied);
                });
        eventId = eventCounterAfter(events);
        requestId = runScenario(dataset, repetition, warmup, "multi-document-cache-revalidation", requestId,
                eventId, trials, requests, documents, events, precision, context -> {
                    RequestObservation before = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, MULTI_DOCUMENT_TOP_K, "FRESH");
                    List<String> targets = before.documentIds().stream().sorted()
                            .limit(Math.min(5, before.documentIds().size()) / 2 + 1).toList();
                    List<RevocationTicket> tickets = targets.stream().map(context::revoke).toList();
                    RequestObservation after = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, MULTI_DOCUMENT_TOP_K, "AFTER_REVOCATION");
                    boolean allDenied = tickets.stream().allMatch(ticket ->
                            context.recordEffectiveDenial(ticket, after));
                    boolean retainsAuthorized = after.documentIds().stream()
                            .anyMatch(id -> !targets.contains(id) && context.isAuthorized(id));
                    return new ScenarioResult(before.documentIds().size() >= 2 && after.cacheHit() &&
                            allDenied && retainsAuthorized,
                            "Revoked=" + targets.size() + ", authorized retained=" + retainsAuthorized);
                });
        eventId = eventCounterAfter(events);
        requestId = runScenario(dataset, repetition, warmup, "revocation-event-processing", requestId, eventId,
                trials, requests, documents, events, precision, context -> {
                    List<String> candidates = dataset.documents().stream()
                            .filter(document -> AUTHORIZED_QUERY_ID.endsWith(
                                    document.getMetadata().getCustomAttributes().get("datasetTopicId")))
                            .map(Document::getDocumentId).sorted().toList();
                    if (candidates.isEmpty()) return new ScenarioResult(false, "No event target available");
                    RevocationTicket first = context.revoke(candidates.getFirst());
                    RevocationTicket duplicate = context.revoke(candidates.getFirst());
                    boolean passed = "PROCESSED".equals(first.outcome()) &&
                            "DUPLICATE".equals(duplicate.outcome());
                    return new ScenarioResult(passed,
                            "Generated two events; duplicate detected=" + duplicate.duplicate());
                });
        eventId = eventCounterAfter(events);
        requestId = runScenario(dataset, repetition, warmup, "precision-at-k", requestId, eventId,
                trials, requests, documents, events, precision, context -> {
                    RequestObservation observation = context.retrieve(AUTHORIZED_USER_ID, AUTHORIZED_QUERY_ID,
                            query, TOP_K, "FRESH");
                    PrecisionObservation result = context.measurePrecision(observation);
                    return new ScenarioResult(observation.documentsReturned() > 0,
                            "P@K=" + result.precisionAtK() + ", authorized P@K=" +
                                    result.authorizedRelevantPrecisionAtK());
                });
        return requestId;
    }

    private int runScenario(SyntheticDataset dataset, int repetition, boolean warmup, String experiment,
                            int requestId, int eventId, List<TrialObservation> trials,
                            List<RequestObservation> requests, List<DocumentObservation> documents,
                            List<RevocationEventObservation> events, List<PrecisionObservation> precision,
                            ScenarioAction action) {
        int requestStart = requests.size();
        int documentStart = documents.size();
        int eventStart = events.size();
        int precisionStart = precision.size();
        Instant startedAt = Instant.now();
        TrialContext context = new TrialContext(dataset, repetition, warmup, experiment,
                requestId, eventId, requests, documents, events, precision);
        ScenarioResult result;
        try {
            result = action.run(context);
        } catch (RuntimeException exception) {
            result = new ScenarioResult(false, exception.toString());
        }

        List<RequestObservation> trialRequests = requests.subList(requestStart, requests.size());
        long returned = trialRequests.stream().mapToLong(RequestObservation::documentsReturned).sum();
        long unauthorizedDocuments = trialRequests.stream()
                .mapToLong(RequestObservation::unauthorizedDocuments).sum();
        long unauthorizedRequests = trialRequests.stream()
                .filter(request -> request.unauthorizedDocuments() > 0).count();
        Instant completedAt = Instant.now();
        trials.add(new TrialObservation(
                dataset.metadata().datasetId(), dataset.metadata().documentCount(), repetition,
                dataset.metadata().seed(), CONFIGURATION_ID, experiment, warmup, result.passed(),
                trialRequests.size(), returned, unauthorizedDocuments, unauthorizedRequests,
                trialRequests.isEmpty() ? null : (double) unauthorizedRequests / trialRequests.size(),
                returned == 0 ? null : (double) unauthorizedDocuments / returned,
                startedAt, completedAt, result.details()));
        return context.nextRequestId();
    }

    private int eventCounterAfter(List<RevocationEventObservation> events) {
        return events.size();
    }

    private List<DatasetSummary> summarize(ReproducibilityMetadata metadata,
                                           List<TrialObservation> trials,
                                           List<RequestObservation> requests,
                                           List<RevocationEventObservation> events,
                                           List<PrecisionObservation> precision) {
        List<DatasetSummary> summaries = new ArrayList<>();
        for (int size : metadata.datasetSizes()) {
            List<TrialObservation> datasetTrials = trials.stream()
                    .filter(trial -> !trial.warmup() && trial.datasetSize() == size).toList();
            List<RequestObservation> datasetRequests = requests.stream()
                    .filter(request -> !request.warmup() && request.datasetSize() == size).toList();
            List<RevocationEventObservation> datasetEvents = events.stream()
                    .filter(event -> !event.warmup() && event.datasetSize() == size).toList();
            List<PrecisionObservation> datasetPrecision = precision.stream()
                    .filter(observation -> !observation.warmup() && observation.datasetSize() == size).toList();

            long unauthorizedRequests = datasetRequests.stream()
                    .filter(request -> request.unauthorizedDocuments() > 0).count();
            long returnedDocuments = datasetRequests.stream()
                    .mapToLong(RequestObservation::documentsReturned).sum();
            long unauthorizedDocuments = datasetRequests.stream()
                    .mapToLong(RequestObservation::unauthorizedDocuments).sum();
            long processed = datasetEvents.stream()
                    .filter(event -> "PROCESSED".equals(event.outcome())).count();
            long duplicates = datasetEvents.stream()
                    .filter(RevocationEventObservation::duplicate).count();
            long failed = datasetEvents.stream()
                    .filter(event -> "FAILED".equals(event.outcome())).count();
            long successfulEvents = processed + duplicates;

            summaries.add(new DatasetSummary(
                    size, datasetTrials.isEmpty() ? "" : datasetTrials.getFirst().datasetId(),
                    metadata.seed(), metadata.repetitions(), datasetTrials.size(),
                    datasetTrials.stream().filter(trial -> !trial.passed()).count(),
                    datasetRequests.size(), unauthorizedRequests, returnedDocuments, unauthorizedDocuments,
                    rate(unauthorizedRequests, datasetRequests.size()),
                    rate(unauthorizedDocuments, returnedDocuments),
                    datasetEvents.size(), processed, failed, duplicates,
                    rate(successfulEvents, datasetEvents.size()),
                    latencyStats(datasetRequests, "FRESH"),
                    latencyStats(datasetRequests, "CACHED"),
                    latencyStats(datasetRequests, "AFTER_REVOCATION"),
                    latencyStatsEvents(datasetEvents, false),
                    latencyStatsEvents(datasetEvents, true),
                    mean(datasetPrecision.stream().mapToDouble(PrecisionObservation::precisionAtK).toArray()),
                    mean(datasetPrecision.stream()
                            .mapToDouble(PrecisionObservation::authorizedRelevantPrecisionAtK).toArray()),
                    datasetPrecision.size()
            ));
        }
        return List.copyOf(summaries);
    }

    private LatencyStatistics latencyStats(List<RequestObservation> requests, String phase) {
        return latencyStats(requests.stream().filter(request -> phase.equals(request.phase()))
                .mapToDouble(RequestObservation::latencyMs).toArray());
    }

    private LatencyStatistics latencyStatsEvents(List<RevocationEventObservation> events, boolean enforcement) {
        return latencyStats(events.stream().map(event -> enforcement
                        ? event.revocationEnforcementLatencyMs()
                        : Double.valueOf(event.eventProcessingLatencyMs()))
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray());
    }

    private LatencyStatistics latencyStats(double[] samples) {
        if (samples.length == 0) return new LatencyStatistics(0, null, null, null, null, null);
        double[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElseThrow();
        double variance = Arrays.stream(sorted).map(value -> Math.pow(value - mean, 2)).average().orElseThrow();
        return new LatencyStatistics(sorted.length, mean, median(sorted), Math.sqrt(variance),
                nearestRank(sorted, 0.95), nearestRank(sorted, 0.99));
    }

    private double nearestRank(double[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1)];
    }

    private double median(double[] sorted) {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    private double mean(double[] values) {
        return values.length == 0 ? Double.NaN : Arrays.stream(values).average().orElseThrow();
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }

    private String queryText(SyntheticDataset dataset, String queryId) {
        return dataset.queries().stream().filter(query -> query.queryId().equals(queryId))
                .map(SyntheticDataset.Query::queryText).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown query: " + queryId));
    }

    private String firstDocumentId(List<String> documentIds) {
        return documentIds.stream().sorted().findFirst().orElse(null);
    }

    private String cacheKey(String query) {
        return AUTHORIZED_USER_ID + ":" + query.trim().toLowerCase(Locale.ROOT);
    }

    private ReproducibilityMetadata reproducibilityMetadata(int repetitions, int warmupRuns,
                                                            long seed, Instant runStartedAt) {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("authorizedUser", AUTHORIZED_USER_ID);
        configuration.put("authorizedQueryId", AUTHORIZED_QUERY_ID);
        configuration.put("unauthorizedQueryId", UNAUTHORIZED_QUERY_ID);
        configuration.put("topK", Integer.toString(TOP_K));
        configuration.put("multiDocumentTopK", Integer.toString(MULTI_DOCUMENT_TOP_K));
        configuration.put("timingMethod", "Retrieval and event processing use System.nanoTime; enforcement uses response and commit Instants");
        configuration.put("percentileMethod", "Nearest rank: sorted[ceil(p*n)-1]; standard deviation is population standard deviation");
        configuration.put("warmupPolicy", "Warm-ups are identified in raw rows and excluded from all aggregates");
        configuration.put("eventOutcomePolicy", "Duplicate revocation requests count as successfully handled and are separately counted");
        configuration.put("authorizationPolicy", "Existing metadata filter, authorization service, and PermissionManager revocation behavior");
        configuration.put("cachePolicy", "Existing cache revalidates each cached document before returning it");
        configuration.put("performanceScope", "Simulated in-memory lexical retrieval; not real-world RAG, vector database, or LLM latency");
        return new ReproducibilityMetadata(seed, repetitions, warmupRuns,
                SyntheticDatasetGenerator.supportedSizes(), EXPERIMENTS, CONFIGURATION_ID,
                runStartedAt, System.getProperty("java.version"), System.getProperty("os.name"), configuration);
    }

    private void validateDataset(SyntheticDataset dataset, int expectedSize, long expectedSeed) throws IOException {
        if (dataset.metadata().documentCount() != expectedSize || dataset.metadata().seed() != expectedSeed ||
                dataset.documents().size() != expectedSize) {
            throw new IOException("Dataset size or seed mismatch: expected " + expectedSize +
                    " documents at seed " + expectedSeed);
        }
        Set<String> documentIds = dataset.documents().stream()
                .map(Document::getDocumentId).collect(Collectors.toSet());
        if (documentIds.size() != expectedSize) {
            throw new IOException("Dataset contains duplicate document IDs: " + dataset.metadata().datasetId());
        }
        Map<String, String> queryTopics = dataset.queries().stream()
                .collect(Collectors.toMap(SyntheticDataset.Query::queryId, SyntheticDataset.Query::topicId));
        for (Map.Entry<String, Set<String>> labels : dataset.relevanceGroundTruth().entrySet()) {
            String topic = queryTopics.get(labels.getKey());
            if (topic == null || labels.getValue().isEmpty()) {
                throw new IOException("Invalid or empty ground-truth labels for query " + labels.getKey());
            }
            for (String documentId : labels.getValue()) {
                Document document = dataset.documents().stream()
                        .filter(candidate -> candidate.getDocumentId().equals(documentId)).findFirst()
                        .orElseThrow(() -> new IOException("Unknown ground-truth document " + documentId));
                if (!topic.equals(document.getMetadata().getCustomAttributes().get("datasetTopicId"))) {
                    throw new IOException("Ground-truth label does not match the document topic: " + documentId);
                }
            }
        }
    }

    private static class TrialContext {
        private final SyntheticDataset dataset;
        private final int repetition;
        private final boolean warmup;
        private final String experiment;
        private final List<RequestObservation> requests;
        private final List<DocumentObservation> documents;
        private final List<RevocationEventObservation> events;
        private final List<PrecisionObservation> precision;
        private final Environment environment;
        private int requestId;
        private int eventId;

        private TrialContext(SyntheticDataset dataset, int repetition, boolean warmup, String experiment,
                             int requestId, int eventId, List<RequestObservation> requests,
                             List<DocumentObservation> documents, List<RevocationEventObservation> events,
                             List<PrecisionObservation> precision) {
            this.dataset = dataset;
            this.repetition = repetition;
            this.warmup = warmup;
            this.experiment = experiment;
            this.requestId = requestId;
            this.eventId = eventId;
            this.requests = requests;
            this.documents = documents;
            this.events = events;
            this.precision = precision;
            this.environment = newEnvironment(dataset);
        }

        private RequestObservation retrieve(String userId, String queryId, String query, int topK, String phase) {
            RetrievalRequest request = new RetrievalRequest(userId, query, topK);
            RetrievalResponse response = environment.retrievalService().retrieve(request);
            Instant observedAt = Instant.now();
            User user = environment.userManager().getUser(userId).orElse(null);
            List<String> returnedIds = new ArrayList<>();
            int unauthorized = 0;
            for (Document document : response.getDocuments()) {
                boolean authorized = user != null &&
                        environment.authorizationService().evaluate(user, document).isAuthorized();
                if (!authorized) unauthorized++;
                returnedIds.add(document.getDocumentId());
                documents.add(new DocumentObservation(dataset.metadata().datasetId(),
                        dataset.metadata().documentCount(), repetition, dataset.metadata().seed(),
                        CONFIGURATION_ID, experiment, warmup, document.getDocumentId(), authorized, observedAt));
            }
            RequestObservation observation = new RequestObservation(
                    dataset.metadata().datasetId(), dataset.metadata().documentCount(), repetition,
                    dataset.metadata().seed(), CONFIGURATION_ID, experiment, warmup,
                    "request-" + (++requestId), phase, userId, queryId, query, topK, response.isCacheHit(),
                    response.getLatencyNs(), response.getLatencyMs(), response.getDocuments().size(),
                    unauthorized, observedAt, List.copyOf(returnedIds));
            requests.add(observation);
            return observation;
        }

        private RevocationTicket revoke(String documentId) {
            if (documentId == null) throw new IllegalArgumentException("Cannot revoke an absent retrieval result");
            boolean duplicate = environment.permissionManager()
                    .getUserPermissionState(AUTHORIZED_USER_ID, documentId) == PermissionState.REVOKED;
            this.eventId++;
            String eventId = "event-" + this.eventId;
            Instant generatedAt = Instant.now();
            long startedNs = System.nanoTime();
            try {
                environment.permissionManager().revokeUserAccess(AUTHORIZED_USER_ID, documentId,
                        "Task 6 repeated revocation experiment");
                RevocationRecord auditRecord = environment.permissionManager().getAuditHistory().getLast();
                Instant processedAt = Instant.now();
                Instant committedAt = auditRecord.timestamp();
                String outcome = duplicate ? "DUPLICATE" : "PROCESSED";
                RevocationEventObservation event = new RevocationEventObservation(
                        dataset.metadata().datasetId(), dataset.metadata().documentCount(), repetition,
                        dataset.metadata().seed(), CONFIGURATION_ID, experiment, warmup, eventId, documentId,
                        generatedAt, committedAt, processedAt, null, outcome, duplicate, null,
                        (System.nanoTime() - startedNs) / 1_000_000.0, null);
                int index = events.size();
                events.add(event);
                return new RevocationTicket(index, eventId, documentId, committedAt, outcome, duplicate);
            } catch (RuntimeException exception) {
                events.add(new RevocationEventObservation(
                        dataset.metadata().datasetId(), dataset.metadata().documentCount(), repetition,
                        dataset.metadata().seed(), CONFIGURATION_ID, experiment, warmup, eventId, documentId,
                        generatedAt, null, Instant.now(), null, "FAILED", duplicate, exception.toString(),
                        (System.nanoTime() - startedNs) / 1_000_000.0, null));
                throw exception;
            }
        }

        private boolean recordEffectiveDenial(RevocationTicket ticket, RequestObservation request) {
            boolean absent = !request.documentIds().contains(ticket.documentId());
            Document target = environment.documentRepository().getDocument(ticket.documentId()).orElse(null);
            User user = environment.userManager().getUser(AUTHORIZED_USER_ID).orElse(null);
            boolean denied = target != null && user != null &&
                    !environment.authorizationService().evaluate(user, target).isAuthorized();
            if (!absent || !denied) return false;
            RevocationEventObservation previous = events.get(ticket.eventIndex());
            double enforcementMs = Duration.between(previous.permissionCommittedAt(), request.observedAt())
                    .toNanos() / 1_000_000.0;
            events.set(ticket.eventIndex(), new RevocationEventObservation(
                    previous.datasetId(), previous.datasetSize(), previous.repetition(), previous.seed(),
                    previous.configurationId(), previous.experiment(), previous.warmup(), previous.eventId(),
                    previous.documentId(), previous.generatedAt(), previous.permissionCommittedAt(),
                    previous.processedAt(), request.observedAt(), previous.outcome(), previous.duplicate(),
                    previous.failure(), previous.eventProcessingLatencyMs(), enforcementMs));
            return true;
        }

        private boolean isAuthorized(String documentId) {
            Document document = environment.documentRepository().getDocument(documentId).orElse(null);
            User user = environment.userManager().getUser(AUTHORIZED_USER_ID).orElse(null);
            return document != null && user != null &&
                    environment.authorizationService().evaluate(user, document).isAuthorized();
        }

        private PrecisionObservation measurePrecision(RequestObservation request) {
            Set<String> authorizedIds = request.documentIds().stream()
                    .filter(this::isAuthorized).collect(Collectors.toSet());
            SyntheticDataset.PrecisionAtK metrics = dataset.evaluatePrecisionAtK(
                    request.queryId(), request.documentIds(), request.topK(), authorizedIds);
            PrecisionObservation observation = new PrecisionObservation(
                    dataset.metadata().datasetId(), dataset.metadata().documentCount(), repetition,
                    dataset.metadata().seed(), CONFIGURATION_ID, experiment, warmup,
                    request.requestId(), request.queryId(), request.topK(), metrics.relevantRetrieved(),
                    metrics.authorizedRelevantRetrieved(), metrics.precisionAtK(),
                    metrics.authorizedRelevantPrecisionAtK(), Instant.now());
            precision.add(observation);
            return observation;
        }

        private int nextRequestId() {
            return requestId;
        }

        private static Environment newEnvironment(SyntheticDataset dataset) {
            UserManager userManager = new UserManager();
            dataset.users().forEach(userManager::registerUser);
            DocumentRepository repository = new DocumentRepository();
            dataset.documents().forEach(repository::addDocument);
            PermissionManager permissionManager = new PermissionManager();
            AuthorizationService authorizationService = new AuthorizationService(permissionManager);
            MetadataFilter metadataFilter = new MetadataFilter(permissionManager);
            CacheService cacheService = new CacheService();
            RetrievalService retrievalService = new RetrievalService(userManager, repository, metadataFilter,
                    authorizationService, cacheService);
            return new Environment(userManager, repository, permissionManager, authorizationService,
                    cacheService, retrievalService);
        }
    }

    private record RevocationTicket(int eventIndex, String eventId, String documentId,
                                    Instant permissionCommittedAt, String outcome, boolean duplicate) {}

    private record Environment(UserManager userManager, DocumentRepository documentRepository,
                               PermissionManager permissionManager, AuthorizationService authorizationService,
                               CacheService cacheService, RetrievalService retrievalService) {}

    private record ScenarioResult(boolean passed, String details) {}

    @FunctionalInterface
    private interface ScenarioAction {
        ScenarioResult run(TrialContext context);
    }

    public record ReproducibilityMetadata(long seed, int repetitions, int warmupRuns,
                                          List<Integer> datasetSizes, List<String> experiments,
                                          String configurationId, Instant runStartedAt, String javaVersion,
                                          String osName, Map<String, String> configuration) {}

    public record TrialObservation(String datasetId, int datasetSize, int repetition, long seed,
                                   String configurationId, String experiment, boolean warmup,
                                   boolean passed, int retrievalRequests, long documentsReturned,
                                   long unauthorizedDocumentsReturned, long requestsWithExposure,
                                   Double requestExposureRate, Double documentExposureRate,
                                   Instant startedAt, Instant completedAt, String details) {}

    public record RequestObservation(String datasetId, int datasetSize, int repetition, long seed,
                                     String configurationId, String experiment, boolean warmup,
                                     String requestId, String phase, String userId, String queryId,
                                     String query, int topK, boolean cacheHit, long latencyNs,
                                     double latencyMs, int documentsReturned, int unauthorizedDocuments,
                                     Instant observedAt, List<String> documentIds) {}

    public record DocumentObservation(String datasetId, int datasetSize, int repetition, long seed,
                                     String configurationId, String experiment, boolean warmup,
                                     String documentId, boolean authorized, Instant observedAt) {}

    public record RevocationEventObservation(String datasetId, int datasetSize, int repetition, long seed,
                                             String configurationId, String experiment, boolean warmup,
                                             String eventId, String documentId, Instant generatedAt,
                                             Instant permissionCommittedAt, Instant processedAt,
                                             Instant firstDeniedAt, String outcome, boolean duplicate,
                                             String failure, double eventProcessingLatencyMs,
                                             Double revocationEnforcementLatencyMs) {}

    public record PrecisionObservation(String datasetId, int datasetSize, int repetition, long seed,
                                       String configurationId, String experiment, boolean warmup,
                                       String requestId, String queryId, int k, int relevantRetrieved,
                                       int authorizedRelevantRetrieved, double precisionAtK,
                                       double authorizedRelevantPrecisionAtK, Instant observedAt) {}

    public record LatencyStatistics(int sampleCount, Double meanMs, Double medianMs,
                                    Double standardDeviationMs, Double p95Ms, Double p99Ms) {}

    public record DatasetSummary(int datasetSize, String datasetId, long seed, int repetitions,
                                 int trialCount, long failedTrials, long totalRetrievalRequests,
                                 long requestsWithExposure, long documentsReturned,
                                 long unauthorizedDocumentsReturned, double unauthorizedRetrievalRate,
                                 double unauthorizedDocumentExposureRate, long generatedRevocationEvents,
                                 long processedRevocationEvents, long failedRevocationEvents,
                                 long duplicateRevocationEvents, double revocationEventProcessingSuccessRate,
                                 LatencyStatistics freshRetrievalLatency, LatencyStatistics cachedRetrievalLatency,
                                 LatencyStatistics postRevocationRetrievalLatency,
                                 LatencyStatistics revocationEventProcessingLatency,
                                 LatencyStatistics revocationEnforcementLatency,
                                 double meanPrecisionAtK, double meanAuthorizedRelevantPrecisionAtK,
                                 int precisionSampleCount) {}

    public record Results(ReproducibilityMetadata metadata, List<TrialObservation> trials,
                          List<RequestObservation> requests, List<DocumentObservation> documents,
                          List<RevocationEventObservation> revocationEvents,
                          List<PrecisionObservation> precisionObservations,
                          List<DatasetSummary> datasetSummaries) {}

    public static void main(String[] args) throws IOException {
        Path datasetDirectory = args.length > 0 ? Path.of(args[0]) : Path.of("datasets/generated");
        Path outputDirectory = args.length > 1 ? Path.of(args[1]) : Path.of("results/task6");
        RepeatedExperimentRunner runner = new RepeatedExperimentRunner();
        Results results = runner.runDefault(datasetDirectory);
        RepeatedExperimentLogger logger = new RepeatedExperimentLogger();
        logger.export(results, outputDirectory);
        long measuredRepetitions = results.trials().stream().filter(trial -> !trial.warmup())
                .map(trial -> trial.datasetSize() + ":" + trial.repetition()).distinct().count();
        System.out.printf("Completed %d measured dataset-level repetitions; %d measured scenarios; %d warm-up scenarios.%n",
                measuredRepetitions,
                results.trials().stream().filter(trial -> !trial.warmup()).count(),
                results.trials().stream().filter(TrialObservation::warmup).count());
        if (measuredRepetitions != 120 ||
                results.trials().stream().anyMatch(trial -> !trial.passed())) {
            throw new IllegalStateException("Repeated evaluation did not complete all 120 planned repetitions successfully");
        }
    }
}
