package edu.academic.rag.accesscontrol.manager;

import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.User;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user identities, active status, role memberships, and security clearances.
 */
public class UserManager {
    private final Map<String, User> users = new ConcurrentHashMap<>();

    public void registerUser(User user) {
        Objects.requireNonNull(user, "User must not be null");
        users.put(user.getUserId(), user);
    }

    public Optional<User> getUser(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(users.get(userId));
    }

    public boolean hasUser(String userId) {
        return userId != null && users.containsKey(userId);
    }

    public void addRole(String userId, String role) {
        getUser(userId).ifPresent(user -> user.addRole(role));
    }

    public void removeRole(String userId, String role) {
        getUser(userId).ifPresent(user -> user.removeRole(role));
    }

    public void updateRoles(String userId, Collection<String> newRoles) {
        getUser(userId).ifPresent(user -> user.setRoles(newRoles));
    }

    public void updateClearance(String userId, ClassificationLevel clearance) {
        getUser(userId).ifPresent(user -> user.setClearanceLevel(clearance));
    }

    public void updateDepartment(String userId, String department) {
        getUser(userId).ifPresent(user -> user.setDepartment(department));
    }

    public void setUserActive(String userId, boolean active) {
        getUser(userId).ifPresent(user -> user.setActive(active));
    }

    public Collection<User> getAllUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    public void clear() {
        users.clear();
    }
}
