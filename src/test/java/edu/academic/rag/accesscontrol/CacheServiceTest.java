package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.DocumentMetadata;
import edu.academic.rag.accesscontrol.model.User;
import edu.academic.rag.accesscontrol.service.AuthorizationService;
import edu.academic.rag.accesscontrol.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CacheService Unit Tests")
class CacheServiceTest {
    private CacheService cacheService;
    private PermissionManager permissionManager;
    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService();
        permissionManager = new PermissionManager();
        authorizationService = new AuthorizationService(permissionManager);
    }

    @Test
    @DisplayName("Should put and retrieve entries from cache")
    void testBasicPutAndGet() {
        Document doc = new Document("d1", "Title", "Content", null);
        cacheService.put("query-1", List.of(doc));

        Optional<CacheService.CacheEntry> entry = cacheService.get("query-1");
        assertTrue(entry.isPresent());
        assertEquals(1, entry.get().documents().size());
        assertEquals(1, cacheService.getCacheHits());
    }

    @Test
    @DisplayName("Should enforce authorization revalidation on cached items")
    void testAuthorizationRevalidationOnCachedItems() {
        User user = new User("alice", "Alice", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        user.addRole("ROLE_FINANCE");

        Document doc = new Document("d1", "Earnings", "Content",
                DocumentMetadata.builder()
                        .department("FINANCE")
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .allowedRoles("ROLE_FINANCE")
                        .build());

        cacheService.put("alice:earnings", List.of(doc));

        // First revalidation: authorized
        Optional<CacheService.RevalidationResult> reval1 =
                cacheService.getWithRevalidation("alice:earnings", user, authorizationService);
        assertTrue(reval1.isPresent());
        assertEquals(1, reval1.get().authorizedDocuments().size());

        // Revoke access
        permissionManager.revokeUserAccess("alice", "d1");

        // Second revalidation: revoked document must be purged from result
        Optional<CacheService.RevalidationResult> reval2 =
                cacheService.getWithRevalidation("alice:earnings", user, authorizationService);
        assertTrue(reval2.isPresent());
        assertEquals(0, reval2.get().authorizedDocuments().size());
        assertEquals(1, cacheService.getRevalidationsFailed());
    }
}
