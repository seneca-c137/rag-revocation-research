package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.experiment.ExperimentRunner;
import edu.academic.rag.accesscontrol.model.ExperimentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Experimental Evaluation Scenarios (TC-1 through TC-6)")
class ExperimentTest {
    private ExperimentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ExperimentRunner();
    }

    @Test
    @DisplayName("TC-1: Baseline Authorized Retrieval")
    void testCase1_BaselineAuthorizedRetrieval() {
        ExperimentResult result = runner.runTestCase1_BaselineAuthorizedRetrieval();
        assertTrue(result.isPassed(), "TC-1 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-2: Direct Permission Revocation Awareness")
    void testCase2_DirectPermissionRevocation() {
        ExperimentResult result = runner.runTestCase2_DirectPermissionRevocation();
        assertTrue(result.isPassed(), "TC-2 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-3: Dynamic Role Demotion and Clearance Downgrade")
    void testCase3_DynamicRoleDemotion() {
        ExperimentResult result = runner.runTestCase3_DynamicRoleDemotion();
        assertTrue(result.isPassed(), "TC-3 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-4: Cached Retrieval with Dynamic Authorization Revalidation")
    void testCase4_CachedRetrievalWithRevalidation() {
        ExperimentResult result = runner.runTestCase4_CachedRetrievalWithRevalidation();
        assertTrue(result.isPassed(), "TC-4 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-5: Multi-Attribute Metadata Filtering (ABAC Boundary Enforcement)")
    void testCase5_MultiAttributeMetadataFiltering() {
        ExperimentResult result = runner.runTestCase5_MultiAttributeMetadataFiltering();
        assertTrue(result.isPassed(), "TC-5 must pass: " + result.getDetails());
    }

    @Test
    @DisplayName("TC-6: Zero-Privilege Unauthorized Denial")
    void testCase6_UnauthorizedAccessDenial() {
        ExperimentResult result = runner.runTestCase6_UnauthorizedAccessDenial();
        assertTrue(result.isPassed(), "TC-6 must pass: " + result.getDetails());
    }
}
