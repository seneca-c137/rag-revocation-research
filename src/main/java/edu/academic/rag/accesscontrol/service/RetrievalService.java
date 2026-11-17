package edu.academic.rag.accesscontrol.service;

import edu.academic.rag.accesscontrol.manager.UserManager;
import edu.academic.rag.accesscontrol.model.*;
import edu.academic.rag.accesscontrol.repository.DocumentRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the secure RAG document retrieval pipeline:
 * 1. Resolves current dynamic user state.
 * 2. Checks cache with mandatory dynamic authorization revalidation.
 * 3. On cache miss: performs metadata pre-filtering, similarity ranking,
 *    and final post-retrieval authorization validation.
 * 4. Records telemetry, latencies, and audit logs.
 */
public class RetrievalService {
    private final UserManager userManager;
    private final DocumentRepository documentRepository;
    private final MetadataFilter metadataFilter;
    private final AuthorizationService authorizationService;
    private final CacheService cacheService;

    public RetrievalService(UserManager userManager,
                            DocumentRepository documentRepository,
                            MetadataFilter metadataFilter,
                            AuthorizationService authorizationService,
                            CacheService cacheService) {
        this.userManager = Objects.requireNonNull(userManager, "UserManager must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "DocumentRepository must not be null");
        this.metadataFilter = Objects.requireNonNull(metadataFilter, "MetadataFilter must not be null");
        this.authorizationService = Objects.requireNonNull(authorizationService, "AuthorizationService must not be null");
        this.cacheService = Objects.requireNonNull(cacheService, "CacheService must not be null");
    }

    /**
     * Executes the secure retrieval pipeline for a given request.
     */
    public RetrievalResponse retrieve(RetrievalRequest request) {
        long startNs = System.nanoTime();
        String userId = request.getUserId();
        String query = request.getQuery();
        int topK = request.getTopK();

        // 1. Resolve current user identity and dynamic attributes
        Optional<User> userOpt = userManager.getUser(userId);
        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            long durationNs = System.nanoTime() - startNs;
            AuthorizationDecision deny = AuthorizationDecision.deny(userId, "ALL",
                    "User does not exist or is inactive", durationNs);
            return new RetrievalResponse(query, userId, Collections.emptyList(), 0, 0, false, durationNs, List.of(deny));
        }
        User currentUser = userOpt.get();

        String cacheKey = buildCacheKey(userId, query);

        // 2. Check Cache with Mandatory Authorization Revalidation
        Optional<CacheService.RevalidationResult> cacheResult =
                cacheService.getWithRevalidation(cacheKey, currentUser, authorizationService);

        if (cacheResult.isPresent()) {
            CacheService.RevalidationResult reval = cacheResult.get();
            long durationNs = System.nanoTime() - startNs;
            return new RetrievalResponse(
                    query,
                    userId,
                    reval.authorizedDocuments(),
                    reval.authorizedDocuments().size(),
                    reval.authorizedDocuments().size(),
                    true,
                    durationNs,
                    reval.decisions()
            );
        }

        // 3. Cache Miss: Pre-retrieval metadata filtering
        List<Document> allDocuments = documentRepository.getAllDocuments();
        List<Document> eligibleCandidates = metadataFilter.filter(allDocuments, currentUser);

        // 4. Similarity / Keyword Relevance Ranking
        List<Document> rankedCandidates = rankCandidates(eligibleCandidates, query, topK);

        // 5. Final Post-Retrieval Authorization Validation
        List<Document> authorizedDocuments = new ArrayList<>();
        List<AuthorizationDecision> auditDecisions = new ArrayList<>();

        for (Document candidate : rankedCandidates) {
            AuthorizationDecision decision = authorizationService.evaluate(currentUser, candidate);
            auditDecisions.add(decision);
            if (decision.isAuthorized()) {
                authorizedDocuments.add(candidate);
            }
        }

        // 6. Cache authorized results for subsequent queries
        cacheService.put(cacheKey, authorizedDocuments);

        long durationNs = System.nanoTime() - startNs;
        return new RetrievalResponse(
                query,
                userId,
                authorizedDocuments,
                rankedCandidates.size(),
                authorizedDocuments.size(),
                false,
                durationNs,
                auditDecisions
        );
    }

    private List<Document> rankCandidates(List<Document> candidates, String query, int topK) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        if (query == null || query.isBlank()) {
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }

        Set<String> queryTerms = Arrays.stream(query.toLowerCase().split("\\W+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        return candidates.stream()
                .map(doc -> new ScoredCandidate(doc, calculateScore(doc, queryTerms)))
                .filter(sc -> sc.score > 0.0)
                .sorted(Comparator.comparingDouble((ScoredCandidate sc) -> sc.score).reversed())
                .limit(topK)
                .map(sc -> sc.document)
                .collect(Collectors.toList());
    }

    private double calculateScore(Document doc, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) return 1.0;
        String title = doc.getTitle().toLowerCase();
        String content = doc.getContent().toLowerCase();
        double score = 0.0;
        for (String term : queryTerms) {
            if (title.contains(term)) score += 3.0;
            if (content.contains(term)) score += 1.0;
        }
        return score;
    }

    private String buildCacheKey(String userId, String query) {
        return userId + ":" + (query != null ? query.trim().toLowerCase() : "");
    }

    private record ScoredCandidate(Document document, double score) {}
}
