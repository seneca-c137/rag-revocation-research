package edu.academic.rag.accesscontrol.service;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.DocumentMetadata;
import edu.academic.rag.accesscontrol.model.User;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pre-retrieval metadata filter that reduces candidate document spaces
 * based on user attributes, role memberships, and security classifications.
 */
public class MetadataFilter {
    private final PermissionManager permissionManager;

    public MetadataFilter(PermissionManager permissionManager) {
        this.permissionManager = Objects.requireNonNull(permissionManager, "PermissionManager must not be null");
    }

    /**
     * Filters a list of candidate documents based on current user metadata and permissions.
     */
    public List<Document> filter(List<Document> documents, User user) {
        if (documents == null || documents.isEmpty() || user == null || !user.isActive()) {
            return Collections.emptyList();
        }

        return documents.stream()
                .filter(doc -> isMetadataEligible(doc, user))
                .collect(Collectors.toList());
    }

    /**
     * Checks if document metadata satisfies user clearance, department, and role constraints.
     */
    public boolean isMetadataEligible(Document document, User user) {
        if (document == null || user == null || !user.isActive()) {
            return false;
        }

        String userId = user.getUserId();
        String docId = document.getDocumentId();

        // 1. Check explicit user revocation immediately
        if (permissionManager.isUserAccessRevoked(userId, docId)) {
            return false;
        }

        DocumentMetadata meta = document.getMetadata();

        // 2. Check clearance
        if (!user.getClearanceLevel().satisfies(meta.getClassificationLevel())) {
            return false;
        }

        // 3. Check department boundary
        if (meta.getDepartment() != null && !meta.getDepartment().isBlank()) {
            boolean hasUserGrant = permissionManager.isUserAccessGranted(userId, docId) ||
                    meta.getAllowedUsers().contains(userId);
            if (!hasUserGrant && !meta.getDepartment().equalsIgnoreCase(user.getDepartment())) {
                return false;
            }
        }

        // 4. Check explicit user grant
        if (permissionManager.isUserAccessGranted(userId, docId) || meta.getAllowedUsers().contains(userId)) {
            return true;
        }

        // 5. Check role compatibility
        Set<String> allowedRoles = meta.getAllowedRoles();
        if (allowedRoles.isEmpty()) {
            return true;
        }

        Set<String> userRoles = user.getRoles();
        return userRoles.stream()
                .anyMatch(r -> allowedRoles.contains(r) && !permissionManager.isRoleAccessRevoked(r, docId));
    }
}
