package edu.academic.rag.accesscontrol.service;

import edu.academic.rag.accesscontrol.model.AuthorizationDecision;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.User;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages document retrieval cache with mandatory dynamic authorization revalidation.
 *
 * Guarantees that cached entries are never blindly reused: every cached document
 * is re-evaluated against the current user authorization state at retrieval time.
 */
public class CacheService {
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong revalidationsPassed = new AtomicLong(0);
    private final AtomicLong revalidationsFailed = new AtomicLong(0);

    public record CacheEntry(String key, List<Document> documents, Instant createdAt) {}

    public record RevalidationResult(List<Document> authorizedDocuments, List<AuthorizationDecision> decisions) {}

    public void put(String key, List<Document> documents) {
        if (key == null || documents == null) return;
        cache.put(key, new CacheEntry(key, new ArrayList<>(documents), Instant.now()));
    }

    public Optional<CacheEntry> get(String key) {
        if (key == null) return Optional.empty();
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            cacheHits.incrementAndGet();
            return Optional.of(entry);
        } else {
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }

    /**
     * Retrieves cached documents and enforces dynamic authorization revalidation
     * against current user credentials and permissions.
     */
    public Optional<RevalidationResult> getWithRevalidation(String key, User user, AuthorizationService authService) {
        if (key == null || user == null || authService == null) {
            return Optional.empty();
        }

        CacheEntry entry = cache.get(key);
        if (entry == null) {
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }

        cacheHits.incrementAndGet();
        List<Document> authorizedDocs = new ArrayList<>();
        List<AuthorizationDecision> decisions = new ArrayList<>();

        for (Document doc : entry.documents()) {
            AuthorizationDecision decision = authService.evaluate(user, doc);
            decisions.add(decision);
            if (decision.isAuthorized()) {
                authorizedDocs.add(doc);
                revalidationsPassed.incrementAndGet();
            } else {
                revalidationsFailed.incrementAndGet();
            }
        }

        return Optional.of(new RevalidationResult(authorizedDocs, decisions));
    }

    public void invalidate(String key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    public void clear() {
        cache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
        revalidationsPassed.set(0);
        revalidationsFailed.set(0);
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public long getRevalidationsPassed() {
        return revalidationsPassed.get();
    }

    public long getRevalidationsFailed() {
        return revalidationsFailed.get();
    }
}
