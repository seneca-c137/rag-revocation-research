package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.manager.UserManager;
import edu.academic.rag.accesscontrol.model.*;
import edu.academic.rag.accesscontrol.repository.DocumentRepository;
import edu.academic.rag.accesscontrol.service.AuthorizationService;
import edu.academic.rag.accesscontrol.service.CacheService;
import edu.academic.rag.accesscontrol.service.MetadataFilter;
import edu.academic.rag.accesscontrol.service.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RetrievalService Integration Tests")
class RetrievalServiceTest {
    private UserManager userManager;
    private DocumentRepository documentRepository;
    private PermissionManager permissionManager;
    private AuthorizationService authorizationService;
    private MetadataFilter metadataFilter;
    private CacheService cacheService;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        userManager = new UserManager();
        documentRepository = new DocumentRepository();
        permissionManager = new PermissionManager();
        authorizationService = new AuthorizationService(permissionManager);
        metadataFilter = new MetadataFilter(permissionManager);
        cacheService = new CacheService();
        retrievalService = new RetrievalService(
                userManager,
                documentRepository,
                metadataFilter,
                authorizationService,
                cacheService
        );

        User alice = new User("alice", "Alice", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        alice.addRole("ROLE_FINANCE");
        userManager.registerUser(alice);

        documentRepository.addDocument(new Document("doc-fin-1", "Finance Quarterly Profit", "Strong Q1 results",
                DocumentMetadata.builder()
                        .department("FINANCE")
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .allowedRoles("ROLE_FINANCE")
                        .build()));
    }

    @Test
    @DisplayName("Should retrieve document on authorized request and populate cache")
    void testAuthorizedRetrievalAndCachePopulation() {
        RetrievalRequest req1 = new RetrievalRequest("alice", "Finance Profit", 5);
        RetrievalResponse res1 = retrievalService.retrieve(req1);

        assertEquals(1, res1.getDocuments().size());
        assertFalse(res1.isCacheHit());

        // Second identical request should hit cache
        RetrievalRequest req2 = new RetrievalRequest("alice", "Finance Profit", 5);
        RetrievalResponse res2 = retrievalService.retrieve(req2);

        assertEquals(1, res2.getDocuments().size());
        assertTrue(res2.isCacheHit());
    }

    @Test
    @DisplayName("Should immediately deny revoked document even when cache hit occurs")
    void testRevocationAwareCacheRetrieval() {
        // Query 1: populate cache
        retrievalService.retrieve(new RetrievalRequest("alice", "Finance Profit", 5));

        // Revoke permission dynamically
        permissionManager.revokeUserAccess("alice", "doc-fin-1");

        // Query 2: hits cache, but authorization revalidation rejects revoked document
        RetrievalResponse res2 = retrievalService.retrieve(new RetrievalRequest("alice", "Finance Profit", 5));
        assertTrue(res2.isCacheHit());
        assertEquals(0, res2.getDocuments().size(), "Revoked document must not be returned from cache!");
    }
}
