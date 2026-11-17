package edu.academic.rag.accesscontrol.model;

import java.time.Instant;
import java.util.*;

/**
 * Metadata associated with a document governing authorization rules,
 * classification levels, department isolation, and role restrictions.
 */
public class DocumentMetadata {
    private final Set<String> allowedRoles;
    private final Set<String> allowedUsers;
    private ClassificationLevel classificationLevel;
    private String department;
    private final Map<String, String> customAttributes;
    private final Instant createdAt;
    private int version;

    public DocumentMetadata() {
        this.allowedRoles = new HashSet<>();
        this.allowedUsers = new HashSet<>();
        this.classificationLevel = ClassificationLevel.INTERNAL;
        this.department = null; // null implies cross-department / general
        this.customAttributes = new HashMap<>();
        this.createdAt = Instant.now();
        this.version = 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public synchronized Set<String> getAllowedRoles() {
        return Collections.unmodifiableSet(new HashSet<>(allowedRoles));
    }

    public synchronized void addAllowedRole(String role) {
        if (role != null && !role.isBlank()) {
            this.allowedRoles.add(role.trim().toUpperCase());
        }
    }

    public synchronized void removeAllowedRole(String role) {
        if (role != null) {
            this.allowedRoles.remove(role.trim().toUpperCase());
        }
    }

    public synchronized Set<String> getAllowedUsers() {
        return Collections.unmodifiableSet(new HashSet<>(allowedUsers));
    }

    public synchronized void addAllowedUser(String userId) {
        if (userId != null && !userId.isBlank()) {
            this.allowedUsers.add(userId.trim());
        }
    }

    public synchronized void removeAllowedUser(String userId) {
        if (userId != null) {
            this.allowedUsers.remove(userId.trim());
        }
    }

    public synchronized ClassificationLevel getClassificationLevel() {
        return classificationLevel;
    }

    public synchronized void setClassificationLevel(ClassificationLevel classificationLevel) {
        this.classificationLevel = classificationLevel != null ? classificationLevel : ClassificationLevel.PUBLIC;
    }

    public synchronized String getDepartment() {
        return department;
    }

    public synchronized void setDepartment(String department) {
        this.department = department != null ? department.toUpperCase() : null;
    }

    public synchronized Map<String, String> getCustomAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(customAttributes));
    }

    public synchronized void setCustomAttribute(String key, String value) {
        this.customAttributes.put(key, value);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized int getVersion() {
        return version;
    }

    public synchronized void incrementVersion() {
        this.version++;
    }

    public static class Builder {
        private final Set<String> allowedRoles = new HashSet<>();
        private final Set<String> allowedUsers = new HashSet<>();
        private ClassificationLevel classificationLevel = ClassificationLevel.INTERNAL;
        private String department = null;
        private final Map<String, String> customAttributes = new HashMap<>();

        public Builder allowedRoles(String... roles) {
            if (roles != null) {
                for (String r : roles) {
                    if (r != null && !r.isBlank()) {
                        this.allowedRoles.add(r.trim().toUpperCase());
                    }
                }
            }
            return this;
        }

        public Builder allowedUsers(String... userIds) {
            if (userIds != null) {
                for (String u : userIds) {
                    if (u != null && !u.isBlank()) {
                        this.allowedUsers.add(u.trim());
                    }
                }
            }
            return this;
        }

        public Builder classificationLevel(ClassificationLevel level) {
            this.classificationLevel = level;
            return this;
        }

        public Builder department(String dept) {
            this.department = dept != null ? dept.toUpperCase() : null;
            return this;
        }

        public Builder attribute(String key, String value) {
            this.customAttributes.put(key, value);
            return this;
        }

        public DocumentMetadata build() {
            DocumentMetadata meta = new DocumentMetadata();
            for (String r : allowedRoles) {
                meta.addAllowedRole(r);
            }
            for (String u : allowedUsers) {
                meta.addAllowedUser(u);
            }
            meta.setClassificationLevel(classificationLevel);
            meta.setDepartment(department);
            for (Map.Entry<String, String> entry : customAttributes.entrySet()) {
                meta.setCustomAttribute(entry.getKey(), entry.getValue());
            }
            return meta;
        }
    }

    @Override
    public String toString() {
        return "DocumentMetadata{" +
                "allowedRoles=" + allowedRoles +
                ", allowedUsers=" + allowedUsers +
                ", classificationLevel=" + classificationLevel +
                ", department='" + department + '\'' +
                ", version=" + version +
                '}';
    }
}
