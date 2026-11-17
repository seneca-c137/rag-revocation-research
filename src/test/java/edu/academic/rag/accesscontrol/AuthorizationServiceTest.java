package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.model.*;
import edu.academic.rag.accesscontrol.service.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuthorizationService Unit Tests")
class AuthorizationServiceTest {
    private PermissionManager permissionManager;
    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        permissionManager = new PermissionManager();
        authorizationService = new AuthorizationService(permissionManager);
    }

    @Test
    @DisplayName("Should authorize user when role, department, and clearance match")
    void testAuthorizedAccess() {
        User user = new User("u1", "Alice", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        user.addRole("ROLE_FINANCE_ANALYST");

        Document doc = new Document("d1", "Earnings", "Content",
                DocumentMetadata.builder()
                        .department("FINANCE")
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .allowedRoles("ROLE_FINANCE_ANALYST")
                        .build());

        AuthorizationDecision decision = authorizationService.evaluate(user, doc);
        assertTrue(decision.isAuthorized());
        assertTrue(decision.getEvaluationDurationNs() > 0);
    }

    @Test
    @DisplayName("Should deny access when clearance is insufficient")
    void testInsufficientClearance() {
        User user = new User("u1", "Charlie", "ENGINEERING", ClassificationLevel.INTERNAL);
        user.addRole("ROLE_ENGINEER");

        Document doc = new Document("d1", "Secret Blueprints", "Content",
                DocumentMetadata.builder()
                        .department("ENGINEERING")
                        .classificationLevel(ClassificationLevel.RESTRICTED)
                        .allowedRoles("ROLE_ENGINEER")
                        .build());

        AuthorizationDecision decision = authorizationService.evaluate(user, doc);
        assertFalse(decision.isAuthorized());
        assertTrue(decision.getReason().contains("Insufficient clearance"));
    }

    @Test
    @DisplayName("Should deny access when department mismatches without override")
    void testDepartmentMismatch() {
        User user = new User("u1", "Bob", "ENGINEERING", ClassificationLevel.CONFIDENTIAL);
        user.addRole("ROLE_MANAGER");

        Document doc = new Document("d1", "HR Salaries", "Content",
                DocumentMetadata.builder()
                        .department("HR")
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .allowedRoles("ROLE_MANAGER")
                        .build());

        AuthorizationDecision decision = authorizationService.evaluate(user, doc);
        assertFalse(decision.isAuthorized());
        assertTrue(decision.getReason().contains("Department boundary mismatch"));
    }

    @Test
    @DisplayName("Should deny access immediately when explicit user revocation exists")
    void testExplicitUserRevocation() {
        User user = new User("u1", "Alice", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        user.addRole("ROLE_FINANCE_ANALYST");

        Document doc = new Document("d1", "Earnings", "Content",
                DocumentMetadata.builder()
                        .department("FINANCE")
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .allowedRoles("ROLE_FINANCE_ANALYST")
                        .build());

        permissionManager.revokeUserAccess("u1", "d1");

        AuthorizationDecision decision = authorizationService.evaluate(user, doc);
        assertFalse(decision.isAuthorized());
        assertTrue(decision.getReason().contains("explicitly revoked"));
    }

    @Test
    @DisplayName("Should deny access when role is explicitly revoked")
    void testExplicitRoleRevocation() {
        User user = new User("u1", "Bob", "ENGINEERING", ClassificationLevel.INTERNAL);
        user.addRole("ROLE_DEVELOPER");

        Document doc = new Document("d1", "Tech Spec", "Content",
                DocumentMetadata.builder()
                        .department("ENGINEERING")
                        .classificationLevel(ClassificationLevel.INTERNAL)
                        .allowedRoles("ROLE_DEVELOPER")
                        .build());

        permissionManager.revokeRoleAccess("ROLE_DEVELOPER", "d1");

        AuthorizationDecision decision = authorizationService.evaluate(user, doc);
        assertFalse(decision.isAuthorized());
        assertTrue(decision.getReason().contains("revoked"));
    }
}
