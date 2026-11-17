package edu.academic.rag.accesscontrol.model;

import java.util.*;

/**
 * Represents an authenticated user in the system with associated roles,
 * department, clearance level, and dynamic security attributes.
 */
public class User {
    private final String userId;
    private final String name;
    private final Set<String> roles;
    private String department;
    private ClassificationLevel clearanceLevel;
    private final Map<String, String> attributes;
    private boolean active;

    public User(String userId, String name, String department, ClassificationLevel clearanceLevel) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.name = name != null ? name : userId;
        this.department = department != null ? department.toUpperCase() : "GENERAL";
        this.clearanceLevel = clearanceLevel != null ? clearanceLevel : ClassificationLevel.PUBLIC;
        this.roles = new HashSet<>();
        this.attributes = new HashMap<>();
        this.active = true;
    }

    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public synchronized Set<String> getRoles() {
        return Collections.unmodifiableSet(new HashSet<>(roles));
    }

    public synchronized void addRole(String role) {
        if (role != null && !role.isBlank()) {
            this.roles.add(role.trim().toUpperCase());
        }
    }

    public synchronized void removeRole(String role) {
        if (role != null) {
            this.roles.remove(role.trim().toUpperCase());
        }
    }

    public synchronized void setRoles(Collection<String> newRoles) {
        this.roles.clear();
        if (newRoles != null) {
            for (String r : newRoles) {
                if (r != null && !r.isBlank()) {
                    this.roles.add(r.trim().toUpperCase());
                }
            }
        }
    }

    public synchronized boolean hasRole(String role) {
        return role != null && this.roles.contains(role.trim().toUpperCase());
    }

    public synchronized String getDepartment() {
        return department;
    }

    public synchronized void setDepartment(String department) {
        this.department = department != null ? department.toUpperCase() : "GENERAL";
    }

    public synchronized ClassificationLevel getClearanceLevel() {
        return clearanceLevel;
    }

    public synchronized void setClearanceLevel(ClassificationLevel clearanceLevel) {
        this.clearanceLevel = clearanceLevel != null ? clearanceLevel : ClassificationLevel.PUBLIC;
    }

    public synchronized Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    public synchronized void setAttribute(String key, String value) {
        this.attributes.put(key, value);
    }

    public synchronized String getAttribute(String key) {
        return this.attributes.get(key);
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", name='" + name + '\'' +
                ", roles=" + roles +
                ", department='" + department + '\'' +
                ", clearanceLevel=" + clearanceLevel +
                ", active=" + active +
                '}';
    }
}
