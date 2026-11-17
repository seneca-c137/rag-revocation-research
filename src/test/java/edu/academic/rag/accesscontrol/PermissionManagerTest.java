package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionManager Unit Tests")
class PermissionManagerTest {
    private PermissionManager permissionManager;

    @BeforeEach
    void setUp() {
        permissionManager = new PermissionManager();
    }

    @Test
    @DisplayName("Should grant and verify user access")
    void testUserGrant() {
        permissionManager.grantUserAccess("alice", "doc-100");
        assertTrue(permissionManager.isUserAccessGranted("alice", "doc-100"));
        assertFalse(permissionManager.isUserAccessRevoked("alice", "doc-100"));
    }

    @Test
    @DisplayName("Should revoke user access and override previous grant")
    void testUserRevocationOverridesGrant() {
        permissionManager.grantUserAccess("alice", "doc-100");
        assertTrue(permissionManager.isUserAccessGranted("alice", "doc-100"));

        permissionManager.revokeUserAccess("alice", "doc-100", "Security policy");
        assertFalse(permissionManager.isUserAccessGranted("alice", "doc-100"));
        assertTrue(permissionManager.isUserAccessRevoked("alice", "doc-100"));
        assertEquals(1, permissionManager.getRevocationHistory().size());
    }

    @Test
    @DisplayName("Should grant and revoke role access")
    void testRoleGrantAndRevocation() {
        permissionManager.grantRoleAccess("ROLE_ANALYST", "doc-200");
        assertTrue(permissionManager.isRoleAccessGranted("ROLE_ANALYST", "doc-200"));
        assertFalse(permissionManager.isRoleAccessRevoked("ROLE_ANALYST", "doc-200"));

        permissionManager.revokeRoleAccess("ROLE_ANALYST", "doc-200");
        assertFalse(permissionManager.isRoleAccessGranted("ROLE_ANALYST", "doc-200"));
        assertTrue(permissionManager.isRoleAccessRevoked("ROLE_ANALYST", "doc-200"));
    }
}
