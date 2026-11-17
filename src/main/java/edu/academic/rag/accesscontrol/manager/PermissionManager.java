package edu.academic.rag.accesscontrol.manager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages explicit grants, role-level permissions, and dynamic revocations.
 * Guarantees dynamic revocation awareness: any explicit revocation immediately
 * overrides previous grants or metadata allowances.
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

    // Audit log of revocation events
    private final List<RevocationRecord> revocationHistory = Collections.synchronizedList(new ArrayList<>());

    public record RevocationRecord(String principal, String targetType, String documentId, Instant timestamp, String reason) {}

    // --- User-Level Dynamic Grants & Revocations ---

    public void grantUserAccess(String userId, String documentId) {
        if (userId == null || documentId == null) return;
        userRevocations.computeIfPresent(userId, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        userGrants.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(documentId);
    }

    public void revokeUserAccess(String userId, String documentId) {
        revokeUserAccess(userId, documentId, "Explicit user permission revocation");
    }

    public void revokeUserAccess(String userId, String documentId, String reason) {
        if (userId == null || documentId == null) return;
        userGrants.computeIfPresent(userId, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        userRevocations.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        revocationHistory.add(new RevocationRecord(userId, "USER", documentId, Instant.now(), reason));
    }

    public boolean isUserAccessRevoked(String userId, String documentId) {
        if (userId == null || documentId == null) return false;
        Set<String> revoked = userRevocations.get(userId);
        return revoked != null && revoked.contains(documentId);
    }

    public boolean isUserAccessGranted(String userId, String documentId) {
        if (userId == null || documentId == null) return false;
        Set<String> granted = userGrants.get(userId);
        return granted != null && granted.contains(documentId);
    }

    // --- Role-Level Dynamic Grants & Revocations ---

    public void grantRoleAccess(String role, String documentId) {
        if (role == null || documentId == null) return;
        String normalizedRole = role.trim().toUpperCase();
        roleRevocations.computeIfPresent(normalizedRole, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        roleGrants.computeIfAbsent(normalizedRole, k -> ConcurrentHashMap.newKeySet()).add(documentId);
    }

    public void revokeRoleAccess(String role, String documentId) {
        revokeRoleAccess(role, documentId, "Explicit role permission revocation");
    }

    public void revokeRoleAccess(String role, String documentId, String reason) {
        if (role == null || documentId == null) return;
        String normalizedRole = role.trim().toUpperCase();
        roleGrants.computeIfPresent(normalizedRole, (k, set) -> {
            set.remove(documentId);
            return set;
        });
        roleRevocations.computeIfAbsent(normalizedRole, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        revocationHistory.add(new RevocationRecord(normalizedRole, "ROLE", documentId, Instant.now(), reason));
    }

    public boolean isRoleAccessRevoked(String role, String documentId) {
        if (role == null || documentId == null) return false;
        Set<String> revoked = roleRevocations.get(role.trim().toUpperCase());
        return revoked != null && revoked.contains(documentId);
    }

    public boolean isRoleAccessGranted(String role, String documentId) {
        if (role == null || documentId == null) return false;
        Set<String> granted = roleGrants.get(role.trim().toUpperCase());
        return granted != null && granted.contains(documentId);
    }

    public List<RevocationRecord> getRevocationHistory() {
        return new ArrayList<>(revocationHistory);
    }

    public void clear() {
        userGrants.clear();
        userRevocations.clear();
        roleGrants.clear();
        roleRevocations.clear();
        revocationHistory.clear();
    }
}
