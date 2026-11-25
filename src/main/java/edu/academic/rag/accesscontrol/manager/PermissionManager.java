package edu.academic.rag.accesscontrol.manager;

import edu.academic.rag.accesscontrol.model.PermissionState;
import edu.academic.rag.accesscontrol.model.RevocationRecord;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages explicit user grants, role-level permissions, and dynamic revocations.
 * Guarantees dynamic revocation awareness: any explicit revocation immediately
 * overrides previous grants or metadata allowances.
 *
 * =========================================================================================
 * AUTHORIZATION EVALUATION PRECEDENCE & RESOLUTION ORDER:
 * =========================================================================================
 * 1. MANDATORY CLEARANCE & DEPARTMENT CONSTRAINTS:
 *    - The principal's classification clearance must hierarchically satisfy the document's
 *      classification level (RESTRICTED > CONFIDENTIAL > INTERNAL > PUBLIC). Insufficient
 *      clearance results in immediate denial, regardless of role grants or explicit grants.
 *    - Department boundaries are strictly enforced. Access across departmental boundaries is
 *      denied unless an explicit user grant/override is active.
 *
 * 2. EXPLICIT USER REVOCATION OVERRIDES ROLE GRANTS:
 *    - If a user has an active, explicit revocation for a target document, access is immediately
 *      denied. An explicit user revocation supersedes all group/role memberships and metadata
 *      allowances (zero-tolerance for revoked access).
 *
 * 3. NEWER EXPLICIT GRANT OVERRIDES AN OLDER REVOCATION:
 *    - Permission state transitions are stateful and sequential. An explicit grant action clears
 *      any prior revocation for that principal and document (transitioning from REVOKED to
 *      GRANTED). Conversely, a newer revocation clears any prior grant (transitioning from
 *      GRANTED to REVOKED).
 *
 * 4. ROLE GRANTS APPLY ONLY WHEN NO EXPLICIT USER REVOCATION EXISTS:
 *    - Role-Based Access Control (RBAC) grants apply if and only if no active explicit user
 *      revocation exists for the target document. If all user roles are revoked, access is denied.
 *
 * 5. DENY-BY-DEFAULT:
 *    - If no applicable explicit user grant or matching allowed role grant authorizes access,
 *      access is denied by default (zero-privilege baseline).
 * =========================================================================================
 */
public class PermissionManager {
    // userId -> Set of explicitly granted documentIds
    private final Map<String, Set<String>> userGrants = new ConcurrentHashMap<>();
    // userId -> Set of explicitly revoked documentIds (blacklisted)
    private final Map<String, Set<String>> userRevocations = new ConcurrentHashMap<>();

    // role -> Set of granted documentIds
    private final Map<String, Set<String>> roleGrants = new ConcurrentHashMap<>();
    // role -> Set of revoked documentIds
    private final Map<String, Set<String>> roleRevocations = new ConcurrentHashMap<>();

    // Audit log of permission transition events (both grant and revoke)
    private final List<RevocationRecord> auditHistory;

    public PermissionManager() {
        this.auditHistory = Collections.synchronizedList(new ArrayList<>());
    }

    public PermissionManager(List<RevocationRecord> sharedAuditLog) {
        this.auditHistory = sharedAuditLog != null ? sharedAuditLog : Collections.synchronizedList(new ArrayList<>());
    }

    // --- State Query Helpers ---

    /**
     * Queries the current permission state for a user with respect to a document.
     *
     * @param userId user identifier
     * @param documentId target document identifier
     * @return GRANTED, REVOKED, or UNSPECIFIED
     */
    public synchronized PermissionState getUserPermissionState(String userId, String documentId) {
        if (userId == null || documentId == null) {
            return PermissionState.UNSPECIFIED;
        }
        if (isUserAccessGranted(userId, documentId)) {
            return PermissionState.GRANTED;
        }
        if (isUserAccessRevoked(userId, documentId)) {
            return PermissionState.REVOKED;
        }
        return PermissionState.UNSPECIFIED;
    }

    /**
     * Queries the current permission state for a role with respect to a document.
     *
     * @param role role identifier
     * @param documentId target document identifier
     * @return GRANTED, REVOKED, or UNSPECIFIED
     */
    public PermissionState getRolePermissionState(String role, String documentId) {
        if (role == null || documentId == null) {
            return PermissionState.UNSPECIFIED;
        }
        String normalizedRole = role.trim().toUpperCase();
        if (isRoleAccessGranted(normalizedRole, documentId)) {
            return PermissionState.GRANTED;
        }
        if (isRoleAccessRevoked(normalizedRole, documentId)) {
            return PermissionState.REVOKED;
        }
        return PermissionState.UNSPECIFIED;
    }

    /**
     * Unified helper querying current permission state for a principal of given target type.
     *
     * @param principal principal identifier (user ID or role name)
     * @param targetType "ROLE" or "USER"
     * @param documentId target document identifier
     * @return GRANTED, REVOKED, or UNSPECIFIED
     */
    public PermissionState getPermissionState(String principal, String targetType, String documentId) {
        if ("ROLE".equalsIgnoreCase(targetType)) {
            return getRolePermissionState(principal, documentId);
        } else {
            return getUserPermissionState(principal, documentId);
        }
    }

    // --- User-Level Dynamic Grants & Revocations ---

    public void grantUserAccess(String userId, String documentId) {
        grantUserAccess(userId, documentId, "Explicit user permission grant");
    }

    public synchronized void grantUserAccess(String userId, String documentId, String reason) {
        if (userId == null || documentId == null) return;

        PermissionState previousState = getUserPermissionState(userId, documentId);
        userRevocations.computeIfPresent(userId, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        userGrants.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        recordTransition(userId, "USER", documentId, "GRANT", previousState, PermissionState.GRANTED, reason);
    }

    public void revokeUserAccess(String userId, String documentId) {
        revokeUserAccess(userId, documentId, "Explicit user permission revocation");
    }

    public synchronized void revokeUserAccess(String userId, String documentId, String reason) {
        if (userId == null || documentId == null) return;
        PermissionState previousState = getUserPermissionState(userId, documentId);
        userGrants.computeIfPresent(userId, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        userRevocations.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        recordTransition(userId, "USER", documentId, "REVOKE", previousState, PermissionState.REVOKED, reason);
    }

    public synchronized boolean isUserAccessRevoked(String userId, String documentId) {
        if (userId == null || documentId == null)
            return false;
        Set<String> revoked = userRevocations.get(userId);
        return revoked != null && revoked.contains(documentId);
    }

    public synchronized boolean isUserAccessGranted(String userId, String documentId) {
        if (userId == null || documentId == null)
            return false;
        Set<String> granted = userGrants.get(userId);
        return granted != null && granted.contains(documentId);
    }

    // --- Role-Level Dynamic Grants & Revocations ---

    public void grantRoleAccess(String role, String documentId) {
        grantRoleAccess(role, documentId, "Explicit role permission grant");
    }

    public void grantRoleAccess(String role, String documentId, String reason) {
        if (role == null || documentId == null)
            return;
        String normalizedRole = role.trim().toUpperCase();
        PermissionState previousState = getRolePermissionState(normalizedRole, documentId);
        roleRevocations.computeIfPresent(normalizedRole, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        roleGrants.computeIfAbsent(normalizedRole, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        recordTransition(normalizedRole, "ROLE", documentId, "GRANT", previousState, PermissionState.GRANTED, reason);
    }

    public void revokeRoleAccess(String role, String documentId) {
        revokeRoleAccess(role, documentId, "Explicit role permission revocation");
    }

    public void revokeRoleAccess(String role, String documentId, String reason) {
        if (role == null || documentId == null)
            return;
        String normalizedRole = role.trim().toUpperCase();
        PermissionState previousState = getRolePermissionState(normalizedRole, documentId);
        roleGrants.computeIfPresent(normalizedRole, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        roleRevocations.computeIfAbsent(normalizedRole, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        recordTransition(normalizedRole, "ROLE", documentId, "REVOKE", previousState, PermissionState.REVOKED, reason);
    }
    public boolean isRoleAccessRevoked(String role, String documentId) {
        if (role == null || documentId == null)
            return false;
        Set<String> revoked = roleRevocations.get(role.trim().toUpperCase());
        return revoked != null && revoked.contains(documentId);
    }

    public boolean isRoleAccessGranted(String role, String documentId) {
        if (role == null || documentId == null)
            return false;
        Set<String> granted = roleGrants.get(role.trim().toUpperCase());
        return granted != null && granted.contains(documentId);
    }

    // --- Audit & Transition Recording ---

    /**
     * Records a permission transition event capturing action, previous state,
     * updated state,
     * reason, and timestamp.
     */
    public RevocationRecord recordTransition(String principal, String targetType, String documentId,
            String action, PermissionState previousState, PermissionState updatedState,
            String reason) {
        RevocationRecord record = new RevocationRecord(principal, targetType, documentId,
                action, previousState, updatedState, reason, Instant.now());
        recordTransition(record);
        return record;
    }

    /**
     * Adds an audit record directly to the transition log.
     */
    public void recordTransition(RevocationRecord record) {
        if (record != null) {
            auditHistory.add(record);
        }
    }

    /**
     * Compatibility alias for recording a revocation or transition event.
     */
    public void recordRevocation(RevocationRecord record) {
        recordTransition(record);
    }

    /**
     * Returns a copy of the complete audit log containing all permission
     * transitions (both GRANT and REVOKE).
     */
    public List<RevocationRecord> getAuditHistory() {
        return new ArrayList<>(auditHistory);
    }

    /**
     * Alias for getAuditHistory().
     */
    public List<RevocationRecord> getAuditRecords() {
        return getAuditHistory();
    }

    /**
     * Returns a copy of all revocation events recorded in the audit log.
     * Preserves backward compatibility with earlier audit log consumers.
     */
    public List<RevocationRecord> getRevocationHistory() {
        return auditHistory.stream()
                .filter(r -> "REVOKE".equalsIgnoreCase(r.action()) || r.updatedState() == PermissionState.REVOKED)
                .collect(Collectors.toList());
    }

    public void clear() {
        userGrants.clear();
        userRevocations.clear();
        roleGrants.clear();
        roleRevocations.clear();
        auditHistory.clear();
    }
}
