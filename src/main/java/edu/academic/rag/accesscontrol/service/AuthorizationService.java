package edu.academic.rag.accesscontrol.service;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.model.AuthorizationDecision;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.DocumentMetadata;
import edu.academic.rag.accesscontrol.model.User;

import java.util.Objects;
import java.util.Set;

/**
 * Core dynamic authorization engine combining Role-Based Access Control (RBAC),
 * Attribute-Based Access Control (ABAC), and Dynamic Revocation checks.
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
public class AuthorizationService {
    private final PermissionManager permissionManager;

    public AuthorizationService(PermissionManager permissionManager) {
        this.permissionManager = Objects.requireNonNull(permissionManager, "PermissionManager must not be null");
    }

    /**
     * Evaluates whether a user is currently authorized to access the given document.
     * Records evaluation latency in nanoseconds.
     *
     * Precedence Order:
     * 1. Identity & Active Status Verification
     * 2. Explicit User Revocation Check (overrides all grants)
     * 3. Explicit Role Revocation Check (checks if all user roles revoked)
     * 4. Mandatory Hierarchical Clearance Level Check (ABAC)
     * 5. Mandatory Department Boundary Check (ABAC - bypassed only by explicit user override)
     * 6. Direct User Access Grant Check
     * 7. Role-Based Access Control (RBAC) Match
     * 8. Deny-by-Default Fallback
     */
    public AuthorizationDecision evaluate(User user, Document document) {
        long startNs = System.nanoTime();

        if (user == null) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.deny("UNKNOWN",
                    document != null ? document.getDocumentId() : "UNKNOWN",
                    "User identity is null or unauthenticated", durationNs);
        }

        String userId = user.getUserId();
        String docId = document != null ? document.getDocumentId() : "UNKNOWN";

        if (!user.isActive()) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.deny(userId, docId,
                    "User account is inactive or disabled", durationNs);
        }

        if (document == null) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.deny(userId, "NULL_DOC",
                    "Document target is null", durationNs);
        }

        DocumentMetadata meta = document.getMetadata();

        // 1. CRITICAL: Check User-Level Explicit Revocation
        if (permissionManager.isUserAccessRevoked(userId, docId)) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.deny(userId, docId,
                    "Access explicitly revoked for user: " + userId, durationNs);
        }

        // 2. Check Role-Level Explicit Revocations
        Set<String> userRoles = user.getRoles();
        boolean allUserRolesRevokedForDoc = !userRoles.isEmpty() &&
                userRoles.stream().allMatch(r -> permissionManager.isRoleAccessRevoked(r, docId));
        if (!userRoles.isEmpty() && allUserRolesRevokedForDoc) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.deny(userId, docId,
                    "All user roles are explicitly revoked for this document", durationNs);
        }

        // 3. Attribute-Based: Clearance Level Check (Hierarchical)
        if (!user.getClearanceLevel().satisfies(meta.getClassificationLevel())) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.deny(userId, docId,
                    String.format("Insufficient clearance: held %s, required %s",
                            user.getClearanceLevel(), meta.getClassificationLevel()), durationNs);
        }

        // 4. Attribute-Based: Department Boundary Check
        if (meta.getDepartment() != null && !meta.getDepartment().isBlank()) {
            boolean userHasOverride = permissionManager.isUserAccessGranted(userId, docId) ||
                    meta.getAllowedUsers().contains(userId);
            if (!userHasOverride && !meta.getDepartment().equalsIgnoreCase(user.getDepartment())) {
                long durationNs = System.nanoTime() - startNs;
                return AuthorizationDecision.deny(userId, docId,
                        String.format("Department boundary mismatch: user %s, document %s",
                                user.getDepartment(), meta.getDepartment()), durationNs);
            }
        }

        // 5. Direct User Grant (explicit grant or in metadata allowedUsers)
        if (permissionManager.isUserAccessGranted(userId, docId) || meta.getAllowedUsers().contains(userId)) {
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.allow(userId, docId,
                    "Authorized via explicit user access grant", durationNs);
        }

        // 6. Role-Based Access Control (RBAC)
        Set<String> allowedRoles = meta.getAllowedRoles();
        if (allowedRoles.isEmpty()) {
            // No role restriction configured, access granted by clearance & department
            long durationNs = System.nanoTime() - startNs;
            return AuthorizationDecision.allow(userId, docId,
                    "Authorized via general clearance and department match", durationNs);
        }

        // Check if any non-revoked role is allowed
        boolean roleAuthorized = userRoles.stream()
                .anyMatch(role -> allowedRoles.contains(role) &&
                        !permissionManager.isRoleAccessRevoked(role, docId));

        long durationNs = System.nanoTime() - startNs;
        if (roleAuthorized) {
            return AuthorizationDecision.allow(userId, docId,
                    "Authorized via matching role membership", durationNs);
        } else {
            return AuthorizationDecision.deny(userId, docId,
                    String.format("Missing required role: required %s, user possesses %s",
                            allowedRoles, userRoles), durationNs);
        }
    }
}
