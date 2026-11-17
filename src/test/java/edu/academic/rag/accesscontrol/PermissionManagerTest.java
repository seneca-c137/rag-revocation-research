package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.model.PermissionState;
import edu.academic.rag.accesscontrol.model.RevocationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

    @Test
    @DisplayName("Should record canonical revocation audit records capturing principal, targetType, documentId, reason, timestamp")
    void testCanonicalUserRevocationAuditRecord() {
        Instant before = Instant.now().minusSeconds(1);
        permissionManager.revokeUserAccess("alice", "doc-confidential", "Security compliance policy");
        Instant after = Instant.now().plusSeconds(1);

        List<RevocationRecord> history = permissionManager.getRevocationHistory();
        assertEquals(1, history.size());

        RevocationRecord record = history.getFirst();
        assertEquals("alice", record.principal());
        assertEquals("alice", record.getPrincipal());
        assertEquals("USER", record.targetType());
        assertEquals("USER", record.getTargetType());
        assertEquals("doc-confidential", record.documentId());
        assertEquals("doc-confidential", record.getDocumentId());
        assertEquals("Security compliance policy", record.reason());
        assertEquals("Security compliance policy", record.getReason());
        assertNotNull(record.timestamp());
        assertNotNull(record.getTimestamp());
        assertTrue(record.timestamp().isAfter(before) && record.timestamp().isBefore(after));
    }

    @Test
    @DisplayName("Should record canonical role revocation audit record")
    void testCanonicalRoleRevocationAuditRecord() {
        permissionManager.revokeRoleAccess("ROLE_CONTRACTOR", "doc-internal", "Contract expiration");

        List<RevocationRecord> history = permissionManager.getRevocationHistory();
        assertEquals(1, history.size());

        RevocationRecord record = history.getFirst();
        assertEquals("ROLE_CONTRACTOR", record.principal());
        assertEquals("ROLE", record.targetType());
        assertEquals("doc-internal", record.documentId());
        assertEquals("Contract expiration", record.reason());
        assertNotNull(record.timestamp());
    }

    @Test
    @DisplayName("Should populate default reason on revocation when not specified")
    void testDefaultRevocationReason() {
        permissionManager.revokeUserAccess("bob", "doc-300");
        List<RevocationRecord> history = permissionManager.getRevocationHistory();
        assertEquals(1, history.size());
        assertEquals("Explicit user permission revocation", history.getFirst().reason());
    }

    @Test
    @DisplayName("Should clear history on clear() and protect internal history from external mutation")
    void testHistoryImmutabilityAndClear() {
        permissionManager.revokeUserAccess("alice", "doc-1");
        List<RevocationRecord> history = permissionManager.getRevocationHistory();
        assertEquals(1, history.size());

        // Modifying returned list should not affect internal state
        history.clear();
        assertEquals(1, permissionManager.getRevocationHistory().size());

        permissionManager.clear();
        assertEquals(0, permissionManager.getRevocationHistory().size());
    }

    // --- Task 2: State Transitions and Helper Tests ---

    @Test
    @DisplayName("Should support PermissionState canonical values and lookup")
    void testPermissionStateCanonicalValues() {
        assertEquals(PermissionState.GRANTED, PermissionState.valueOf("GRANTED"));
        assertEquals(PermissionState.REVOKED, PermissionState.valueOf("REVOKED"));
        assertEquals(PermissionState.UNSPECIFIED, PermissionState.valueOf("UNSPECIFIED"));

        assertEquals(PermissionState.GRANTED, PermissionState.fromString("granted"));
        assertEquals(PermissionState.REVOKED, PermissionState.fromString("REVOKED"));
        assertEquals(PermissionState.UNSPECIFIED, PermissionState.fromString("unknown"));
        assertEquals(PermissionState.UNSPECIFIED, PermissionState.fromString(null));
    }

    @Test
    @DisplayName("State Transition: GRANT -> REVOKE")
    void testTransitionGrantToRevoke() {
        // Initial state is UNSPECIFIED
        assertEquals(PermissionState.UNSPECIFIED, permissionManager.getUserPermissionState("alice", "doc-1"));

        // Grant access
        permissionManager.grantUserAccess("alice", "doc-1", "Initial project onboarding");
        assertEquals(PermissionState.GRANTED, permissionManager.getUserPermissionState("alice", "doc-1"));
        assertTrue(permissionManager.isUserAccessGranted("alice", "doc-1"));
        assertFalse(permissionManager.isUserAccessRevoked("alice", "doc-1"));

        // Transition to REVOKE
        permissionManager.revokeUserAccess("alice", "doc-1", "Security credential rotation");
        assertEquals(PermissionState.REVOKED, permissionManager.getUserPermissionState("alice", "doc-1"));
        assertFalse(permissionManager.isUserAccessGranted("alice", "doc-1"));
        assertTrue(permissionManager.isUserAccessRevoked("alice", "doc-1"));

        // Verify audit history captures both events with accurate states
        List<RevocationRecord> auditHistory = permissionManager.getAuditHistory();
        assertEquals(2, auditHistory.size());

        RevocationRecord grantEvent = auditHistory.get(0);
        assertEquals("alice", grantEvent.principal());
        assertEquals("USER", grantEvent.targetType());
        assertEquals("doc-1", grantEvent.documentId());
        assertEquals("GRANT", grantEvent.action());
        assertEquals(PermissionState.UNSPECIFIED, grantEvent.previousState());
        assertEquals(PermissionState.GRANTED, grantEvent.updatedState());
        assertEquals("Initial project onboarding", grantEvent.reason());
        assertNotNull(grantEvent.timestamp());

        RevocationRecord revokeEvent = auditHistory.get(1);
        assertEquals("alice", revokeEvent.principal());
        assertEquals("USER", revokeEvent.targetType());
        assertEquals("doc-1", revokeEvent.documentId());
        assertEquals("REVOKE", revokeEvent.action());
        assertEquals(PermissionState.GRANTED, revokeEvent.previousState());
        assertEquals(PermissionState.REVOKED, revokeEvent.updatedState());
        assertEquals("Security credential rotation", revokeEvent.reason());
        assertNotNull(revokeEvent.timestamp());
    }

    @Test
    @DisplayName("State Transition: REVOKE -> GRANT (Newer explicit grant overrides older revocation)")
    void testTransitionRevokeToGrant() {
        // Initial revocation
        permissionManager.revokeUserAccess("bob", "doc-2", "Initial access ban");
        assertEquals(PermissionState.REVOKED, permissionManager.getUserPermissionState("bob", "doc-2"));
        assertFalse(permissionManager.isUserAccessGranted("bob", "doc-2"));
        assertTrue(permissionManager.isUserAccessRevoked("bob", "doc-2"));

        // Newer grant overrides previous revocation
        permissionManager.grantUserAccess("bob", "doc-2", "Managerial reinstation");
        assertEquals(PermissionState.GRANTED, permissionManager.getUserPermissionState("bob", "doc-2"));
        assertTrue(permissionManager.isUserAccessGranted("bob", "doc-2"));
        assertFalse(permissionManager.isUserAccessRevoked("bob", "doc-2"));

        List<RevocationRecord> auditHistory = permissionManager.getAuditHistory();
        assertEquals(2, auditHistory.size());

        RevocationRecord revokeEvent = auditHistory.get(0);
        assertEquals("REVOKE", revokeEvent.action());
        assertEquals(PermissionState.UNSPECIFIED, revokeEvent.previousState());
        assertEquals(PermissionState.REVOKED, revokeEvent.updatedState());

        RevocationRecord grantEvent = auditHistory.get(1);
        assertEquals("GRANT", grantEvent.action());
        assertEquals(PermissionState.REVOKED, grantEvent.previousState());
        assertEquals(PermissionState.GRANTED, grantEvent.updatedState());
        assertEquals("Managerial reinstation", grantEvent.reason());
    }

    @Test
    @DisplayName("State Transition: GRANT -> GRANT (Idempotent successive grants)")
    void testTransitionGrantToGrant() {
        permissionManager.grantUserAccess("charlie", "doc-3", "First explicit grant");
        assertEquals(PermissionState.GRANTED, permissionManager.getUserPermissionState("charlie", "doc-3"));

        permissionManager.grantUserAccess("charlie", "doc-3", "Second explicit grant refresher");
        assertEquals(PermissionState.GRANTED, permissionManager.getUserPermissionState("charlie", "doc-3"));
        assertTrue(permissionManager.isUserAccessGranted("charlie", "doc-3"));
        assertFalse(permissionManager.isUserAccessRevoked("charlie", "doc-3"));

        List<RevocationRecord> auditHistory = permissionManager.getAuditHistory();
        assertEquals(2, auditHistory.size());

        RevocationRecord firstGrant = auditHistory.get(0);
        assertEquals("GRANT", firstGrant.action());
        assertEquals(PermissionState.UNSPECIFIED, firstGrant.previousState());
        assertEquals(PermissionState.GRANTED, firstGrant.updatedState());

        RevocationRecord secondGrant = auditHistory.get(1);
        assertEquals("GRANT", secondGrant.action());
        assertEquals(PermissionState.GRANTED, secondGrant.previousState());
        assertEquals(PermissionState.GRANTED, secondGrant.updatedState());
        assertEquals("Second explicit grant refresher", secondGrant.reason());
    }

    @Test
    @DisplayName("State Transition: REVOKE -> REVOKE (Idempotent successive revocations)")
    void testTransitionRevokeToRevoke() {
        permissionManager.revokeUserAccess("david", "doc-4", "First violation");
        assertEquals(PermissionState.REVOKED, permissionManager.getUserPermissionState("david", "doc-4"));

        permissionManager.revokeUserAccess("david", "doc-4", "Second audit confirmation");
        assertEquals(PermissionState.REVOKED, permissionManager.getUserPermissionState("david", "doc-4"));
        assertFalse(permissionManager.isUserAccessGranted("david", "doc-4"));
        assertTrue(permissionManager.isUserAccessRevoked("david", "doc-4"));

        List<RevocationRecord> auditHistory = permissionManager.getAuditHistory();
        assertEquals(2, auditHistory.size());

        RevocationRecord firstRevoke = auditHistory.get(0);
        assertEquals("REVOKE", firstRevoke.action());
        assertEquals(PermissionState.UNSPECIFIED, firstRevoke.previousState());
        assertEquals(PermissionState.REVOKED, firstRevoke.updatedState());

        RevocationRecord secondRevoke = auditHistory.get(1);
        assertEquals("REVOKE", secondRevoke.action());
        assertEquals(PermissionState.REVOKED, secondRevoke.previousState());
        assertEquals(PermissionState.REVOKED, secondRevoke.updatedState());
        assertEquals("Second audit confirmation", secondRevoke.reason());
    }

    @Test
    @DisplayName("Should query role state and unified permission state accurately")
    void testRoleStateTransitionsAndUnifiedHelper() {
        assertEquals(PermissionState.UNSPECIFIED, permissionManager.getRolePermissionState("ROLE_ANALYST", "doc-5"));
        assertEquals(PermissionState.UNSPECIFIED, permissionManager.getPermissionState("ROLE_ANALYST", "ROLE", "doc-5"));

        permissionManager.grantRoleAccess("ROLE_ANALYST", "doc-5", "Role provisioned");
        assertEquals(PermissionState.GRANTED, permissionManager.getRolePermissionState("ROLE_ANALYST", "doc-5"));
        assertEquals(PermissionState.GRANTED, permissionManager.getPermissionState("ROLE_ANALYST", "ROLE", "doc-5"));

        permissionManager.revokeRoleAccess("ROLE_ANALYST", "doc-5", "Role deprecated");
        assertEquals(PermissionState.REVOKED, permissionManager.getRolePermissionState("ROLE_ANALYST", "doc-5"));
        assertEquals(PermissionState.REVOKED, permissionManager.getPermissionState("ROLE_ANALYST", "ROLE", "doc-5"));

        // Helper recordTransition direct invocation
        RevocationRecord customRecord = permissionManager.recordTransition("ROLE_ENGINEER", "ROLE", "doc-9",
                "GRANT", PermissionState.UNSPECIFIED, PermissionState.GRANTED, "Direct helper test");
        assertNotNull(customRecord);
        assertEquals("GRANT", customRecord.action());
        assertEquals(PermissionState.GRANTED, customRecord.updatedState());
        assertTrue(permissionManager.getAuditHistory().contains(customRecord));
    }
}
