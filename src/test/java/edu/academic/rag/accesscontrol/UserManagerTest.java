package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.UserManager;
import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UserManager Unit Tests")
class UserManagerTest {
    private UserManager userManager;

    @BeforeEach
    void setUp() {
        userManager = new UserManager();
    }

    @Test
    @DisplayName("Should successfully register and retrieve a user")
    void testRegisterAndRetrieveUser() {
        User user = new User("u1", "Alice", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        userManager.registerUser(user);

        Optional<User> retrieved = userManager.getUser("u1");
        assertTrue(retrieved.isPresent());
        assertEquals("u1", retrieved.get().getUserId());
        assertEquals("FINANCE", retrieved.get().getDepartment());
        assertEquals(ClassificationLevel.CONFIDENTIAL, retrieved.get().getClearanceLevel());
    }

    @Test
    @DisplayName("Should handle role additions and removals dynamically")
    void testRoleManagement() {
        User user = new User("u2", "Bob", "ENGINEERING", ClassificationLevel.INTERNAL);
        userManager.registerUser(user);

        userManager.addRole("u2", "ROLE_DEVELOPER");
        assertTrue(userManager.getUser("u2").orElseThrow().hasRole("ROLE_DEVELOPER"));

        userManager.removeRole("u2", "ROLE_DEVELOPER");
        assertFalse(userManager.getUser("u2").orElseThrow().hasRole("ROLE_DEVELOPER"));
    }

    @Test
    @DisplayName("Should update user roles collection")
    void testUpdateRoles() {
        User user = new User("u3", "Charlie", "R&D", ClassificationLevel.INTERNAL);
        userManager.registerUser(user);

        userManager.updateRoles("u3", List.of("ROLE_SCIENTIST", "ROLE_LEAD"));
        User updated = userManager.getUser("u3").orElseThrow();
        assertTrue(updated.hasRole("ROLE_SCIENTIST"));
        assertTrue(updated.hasRole("ROLE_LEAD"));
        assertEquals(2, updated.getRoles().size());
    }

    @Test
    @DisplayName("Should update department and clearance dynamically")
    void testClearanceAndDepartmentUpdate() {
        User user = new User("u4", "Diana", "SALES", ClassificationLevel.PUBLIC);
        userManager.registerUser(user);

        userManager.updateClearance("u4", ClassificationLevel.RESTRICTED);
        userManager.updateDepartment("u4", "EXECUTIVE");

        User updated = userManager.getUser("u4").orElseThrow();
        assertEquals(ClassificationLevel.RESTRICTED, updated.getClearanceLevel());
        assertEquals("EXECUTIVE", updated.getDepartment());
    }

    @Test
    @DisplayName("Should deactivate user and reflect inactive status")
    void testUserDeactivation() {
        User user = new User("u5", "Evan", "OPS", ClassificationLevel.INTERNAL);
        userManager.registerUser(user);

        assertTrue(userManager.getUser("u5").orElseThrow().isActive());
        userManager.setUserActive("u5", false);
        assertFalse(userManager.getUser("u5").orElseThrow().isActive());
    }
}
