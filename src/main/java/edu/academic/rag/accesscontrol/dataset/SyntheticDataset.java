package edu.academic.rag.accesscontrol.dataset;

import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.User;

import java.util.*;

/**
 * A deterministic simulated dataset and its permission-independent relevance labels.
 */
public final class SyntheticDataset {
    private final Metadata metadata;
    private final List<Document> documents;
    private final List<User> users;
    private final List<Query> queries;
    private final Map<String, Set<String>> relevanceGroundTruth;

    public SyntheticDataset(Metadata metadata, List<Document> documents, List<User> users,
                            List<Query> queries, Map<String, Set<String>> relevanceGroundTruth) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.documents = List.copyOf(documents);
        this.users = List.copyOf(users);
        this.queries = List.copyOf(queries);
        Map<String, Set<String>> labels = new LinkedHashMap<>();
        relevanceGroundTruth.forEach((queryId, documentIds) ->
                labels.put(queryId, Collections.unmodifiableSet(new LinkedHashSet<>(documentIds))));
        this.relevanceGroundTruth = Collections.unmodifiableMap(labels);
    }

    public Metadata metadata() {
        return metadata;
    }

    public List<Document> documents() {
        return documents;
    }

    public List<User> users() {
        return users;
    }

    public List<Query> queries() {
        return queries;
    }

    public Map<String, Set<String>> relevanceGroundTruth() {
        return relevanceGroundTruth;
    }

    /**
     * Evaluates the first K ranked IDs. K remains the denominator if fewer than K
     * documents were returned. The authorization set affects only the second score.
     */
    public PrecisionAtK evaluatePrecisionAtK(String queryId, List<String> rankedDocumentIds, int k,
                                             Set<String> authorizedDocumentIds) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Objects.requireNonNull(rankedDocumentIds, "rankedDocumentIds must not be null");
        Objects.requireNonNull(authorizedDocumentIds, "authorizedDocumentIds must not be null");
        Set<String> relevantIds = relevanceGroundTruth.get(queryId);
        if (relevantIds == null) {
            throw new IllegalArgumentException("Unknown query ID: " + queryId);
        }

        int relevantRetrieved = 0;
        int authorizedRelevantRetrieved = 0;
        for (String documentId : rankedDocumentIds.subList(0, Math.min(k, rankedDocumentIds.size()))) {
            if (relevantIds.contains(documentId)) {
                relevantRetrieved++;
                if (authorizedDocumentIds.contains(documentId)) {
                    authorizedRelevantRetrieved++;
                }
            }
        }
        return new PrecisionAtK(queryId, k, relevantRetrieved, authorizedRelevantRetrieved,
                (double) relevantRetrieved / k, (double) authorizedRelevantRetrieved / k);
    }

    public record Metadata(String datasetId, int documentCount, long seed, int userCount,
                           int queryCount, String generatorVersion, String relevanceRule) {}

    public record Query(String queryId, String queryText, String topicId) {}

    public record PrecisionAtK(String queryId, int k, int relevantRetrieved,
                               int authorizedRelevantRetrieved, double precisionAtK,
                               double authorizedRelevantPrecisionAtK) {}
}
