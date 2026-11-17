package edu.academic.rag.accesscontrol.repository;

import edu.academic.rag.accesscontrol.model.Document;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory document storage simulating a vector/document store for RAG.
 * Implements lexical relevance scoring to rank candidate documents.
 */
public class DocumentRepository {
    private final Map<String, Document> storage = new ConcurrentHashMap<>();

    public void addDocument(Document document) {
        Objects.requireNonNull(document, "Document must not be null");
        storage.put(document.getDocumentId(), document);
    }

    public Optional<Document> getDocument(String documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(documentId));
    }

    public List<Document> getAllDocuments() {
        return new ArrayList<>(storage.values());
    }

    public boolean removeDocument(String documentId) {
        if (documentId == null) {
            return false;
        }
        return storage.remove(documentId) != null;
    }

    public int count() {
        return storage.size();
    }

    public void clear() {
        storage.clear();
    }

    /**
     * Simulates retrieval search ranking candidates by relevance score against the query terms.
     */
    public List<Document> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return storage.values().stream()
                    .limit(topK > 0 ? topK : 5)
                    .collect(Collectors.toList());
        }

        Set<String> queryTokens = Arrays.stream(query.toLowerCase().split("\\W+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        return storage.values().stream()
                .map(doc -> new ScoredDocument(doc, computeRelevanceScore(doc, queryTokens)))
                .filter(sd -> sd.score > 0.0)
                .sorted(Comparator.comparingDouble((ScoredDocument sd) -> sd.score).reversed())
                .limit(topK > 0 ? topK : 5)
                .map(sd -> sd.document)
                .collect(Collectors.toList());
    }

    private double computeRelevanceScore(Document doc, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 1.0;
        }

        String title = doc.getTitle().toLowerCase();
        String content = doc.getContent().toLowerCase();

        double score = 0.0;
        for (String token : queryTokens) {
            if (title.contains(token)) {
                score += 3.0; // Higher weight for title matches
            }
            if (content.contains(token)) {
                score += 1.0;
            }
        }
        return score;
    }

    private record ScoredDocument(Document document, double score) {}
}
