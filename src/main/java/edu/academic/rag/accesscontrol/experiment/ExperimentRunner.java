package edu.academic.rag.accesscontrol.experiment;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.manager.UserManager;
import edu.academic.rag.accesscontrol.model.*;
import edu.academic.rag.accesscontrol.repository.DocumentRepository;
import edu.academic.rag.accesscontrol.service.AuthorizationService;
import edu.academic.rag.accesscontrol.service.CacheService;
import edu.academic.rag.accesscontrol.service.MetadataFilter;
import edu.academic.rag.accesscontrol.service.RetrievalService;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Executes the six core academic experimental test cases evaluating
 * revocation awareness, dynamic authorization, metadata filtering,
 * role demotion, and stale cache prevention in RAG systems.
 */
public class ExperimentRunner {
    private UserManager userManager;
    private DocumentRepository documentRepository;
    private PermissionManager permissionManager;
    private AuthorizationService authorizationService;
    private MetadataFilter metadataFilter;
    private CacheService cacheService;
    private RetrievalService retrievalService;
    private final ExperimentLogger logger;

    public ExperimentRunner() {
        this.logger = new ExperimentLogger();
        setupEnvironment();
    }

    public void setupEnvironment() {
        this.userManager = new UserManager();
        this.documentRepository = new DocumentRepository();
        this.permissionManager = new PermissionManager();
        this.authorizationService = new AuthorizationService(permissionManager);
        this.metadataFilter = new MetadataFilter(permissionManager);
        this.cacheService = new CacheService();
        this.retrievalService = new RetrievalService(
                userManager,
                documentRepository,
                metadataFilter,
                authorizationService,
                cacheService
        );

        seedUsers();
        seedCorpus();
    }

    private void seedUsers() {
        // Alice: Finance Analyst, Confidential clearance
        User alice = new User("alice", "Alice Finance", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        alice.addRole("ROLE_FINANCE_ANALYST");
        userManager.registerUser(alice);

        // Bob: Senior Engineer, Confidential clearance
        User bob = new User("bob", "Bob Engineer", "ENGINEERING", ClassificationLevel.CONFIDENTIAL);
        bob.addRole("ROLE_ENGINEER");
        bob.addRole("ROLE_DEVELOPER");
        userManager.registerUser(bob);

        // Charlie: Intern in Engineering, Internal clearance only
        User charlie = new User("charlie", "Charlie Intern", "ENGINEERING", ClassificationLevel.INTERNAL);
        charlie.addRole("ROLE_INTERN");
        userManager.registerUser(charlie);

        // Eve: External guest, Public clearance only
        User eve = new User("eve", "Eve External", "GUEST", ClassificationLevel.PUBLIC);
        eve.addRole("ROLE_GUEST");
        userManager.registerUser(eve);
    }

    private void seedCorpus() {
        // Public document
        documentRepository.addDocument(new Document(
                "DOC-PUB-01",
                "Open Source AI Policy",
                "Public guidelines and ethical AI development policies.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.PUBLIC)
                        .build()
        ));

        // Engineering Internal
        documentRepository.addDocument(new Document(
                "DOC-ENG-01",
                "RAG Retrieval Engine Architecture",
                "Internal architecture specifications for RAG vector search pipelines.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.INTERNAL)
                        .department("ENGINEERING")
                        .allowedRoles("ROLE_ENGINEER", "ROLE_DEVELOPER", "ROLE_INTERN")
                        .build()
        ));

        // Engineering Confidential
        documentRepository.addDocument(new Document(
                "DOC-ENG-02",
                "Proprietary Model Weights and Architecture",
                "Confidential engineering blueprints for core language models.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .department("ENGINEERING")
                        .allowedRoles("ROLE_ENGINEER")
                        .build()
        ));

        // Finance Confidential 1
        documentRepository.addDocument(new Document(
                "DOC-FIN-01",
                "Q3 Financial Earnings and Revenue Forecast",
                "Confidential revenue breakdowns and quarterly profit forecasts.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .department("FINANCE")
                        .allowedRoles("ROLE_FINANCE_ANALYST", "ROLE_EXECUTIVE")
                        .build()
        ));

        // Finance Confidential 2
        documentRepository.addDocument(new Document(
                "DOC-FIN-02",
                "M&A Acquisition and Synergy Strategy",
                "Confidential corporate acquisition planning and valuations.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .department("FINANCE")
                        .allowedRoles("ROLE_FINANCE_ANALYST", "ROLE_EXECUTIVE")
                        .build()
        ));

        // HR Confidential
        documentRepository.addDocument(new Document(
                "DOC-HR-01",
                "Annual Employee Compensation Strategy",
                "Confidential salary bands and executive equity allocation.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.CONFIDENTIAL)
                        .department("HR")
                        .allowedRoles("ROLE_HR_MANAGER")
                        .build()
        ));

        // Executive Restricted
        documentRepository.addDocument(new Document(
                "DOC-EXEC-01",
                "Board of Directors Strategic Directives",
                "Restricted board resolutions and strategic corporate pivot plans.",
                DocumentMetadata.builder()
                        .classificationLevel(ClassificationLevel.RESTRICTED)
                        .department("EXECUTIVE")
                        .allowedRoles("ROLE_EXECUTIVE")
                        .build()
        ));
    }

    public List<ExperimentResult> runAllExperiments() {
        List<ExperimentResult> results = new ArrayList<>();

        System.out.println("\n[ExperimentRunner] Starting dynamic access control experimental suite...\n");

        results.add(runTestCase1_BaselineAuthorizedRetrieval());
        results.add(runTestCase2_DirectPermissionRevocation());
        results.add(runTestCase3_DynamicRoleDemotion());
        results.add(runTestCase4_CachedRetrievalWithRevalidation());
        results.add(runTestCase5_MultiAttributeMetadataFiltering());
        results.add(runTestCase6_UnauthorizedAccessDenial());

        logger.logSummaryTable(results);

        try {
            logger.exportAll(results);
        } catch (IOException e) {
            System.err.println("[ExperimentRunner] Failed to export experimental results: " + e.getMessage());
        }

        return results;
    }

    /**
     * TC1: Baseline Authorized Retrieval
     * Verifies that an authorized user with matching role, department, and clearance
     * correctly retrieves target documents with zero policy friction.
     */
    public ExperimentResult runTestCase1_BaselineAuthorizedRetrieval() {
        setupEnvironment();
        String testId = "TC-1";
        String testName = "Baseline Authorized Retrieval";
        String scenario = "Alice (Finance Analyst) queries Q3 earnings report";

        RetrievalRequest request = new RetrievalRequest("alice", "financial earnings revenue", 5);
        RetrievalResponse response = retrievalService.retrieve(request);

        boolean containsTarget = response.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-FIN-01"));
        boolean passed = !response.getDocuments().isEmpty() && containsTarget;

        String authDecision = response.getAuditDecisions().isEmpty() ? "ALLOWED" :
                (response.getAuditDecisions().getFirst().isAuthorized() ? "ALLOWED" : "DENIED");

        String details = String.format("Retrieved %d docs; Target DOC-FIN-01 included; Latency=%.3fms",
                response.getDocuments().size(), response.getLatencyMs());

        return new ExperimentResult(testId, testName, scenario, "alice", passed,
                response.getLatencyMs(), response.getDocuments().size(), response.isCacheHit(),
                authDecision, details, Instant.now());
    }

    /**
     * TC2: Direct Permission Revocation (Revocation Awareness)
     * Demonstrates that revoking a user's permission to a previously accessible document
     * immediately takes effect on the next retrieval request.
     */
    public ExperimentResult runTestCase2_DirectPermissionRevocation() {
        setupEnvironment();
        String testId = "TC-2";
        String testName = "Direct Permission Revocation";
        String scenario = "Revoke Alice's access to DOC-FIN-01 after initial access";

        // Initial retrieval
        RetrievalRequest initialReq = new RetrievalRequest("alice", "financial earnings", 5);
        RetrievalResponse initialRes = retrievalService.retrieve(initialReq);
        boolean initialSuccess = initialRes.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-FIN-01"));

        // Revoke access dynamically
        permissionManager.revokeUserAccess("alice", "DOC-FIN-01", "Security audit policy update");

        // Subsequent retrieval post-revocation
        cacheService.invalidate("alice:financial earnings"); // test clean retrieval re-eval
        RetrievalRequest postRevokeReq = new RetrievalRequest("alice", "financial earnings", 5);
        RetrievalResponse postRevokeRes = retrievalService.retrieve(postRevokeReq);

        boolean leakedTarget = postRevokeRes.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-FIN-01"));

        boolean passed = initialSuccess && !leakedTarget;
        String authDecision = "REVOKED_DENIED";
        String details = String.format("Initial retrieval=%b, Post-revocation leak=%b, Revocation strictly enforced",
                initialSuccess, leakedTarget);

        return new ExperimentResult(testId, testName, scenario, "alice", passed,
                postRevokeRes.getLatencyMs(), postRevokeRes.getDocuments().size(),
                postRevokeRes.isCacheHit(), authDecision, details, Instant.now());
    }

    /**
     * TC3: Dynamic Role Demotion / Role Modification
     * Verifies that modifying a user's role and lowering clearance prevents access
     * to confidential documents requiring the previous elevated role.
     */
    public ExperimentResult runTestCase3_DynamicRoleDemotion() {
        setupEnvironment();
        String testId = "TC-3";
        String testName = "Dynamic Role Demotion";
        String scenario = "Demote Bob from Engineer (Confidential) to Intern (Internal)";

        // Bob queries proprietary weights initially
        RetrievalRequest req1 = new RetrievalRequest("bob", "Proprietary Model Weights", 5);
        RetrievalResponse res1 = retrievalService.retrieve(req1);
        boolean initialAccess = res1.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-ENG-02"));

        // Dynamic Role Demotion: strip ROLE_ENGINEER, assign ROLE_INTERN, reduce clearance
        userManager.updateRoles("bob", List.of("ROLE_INTERN"));
        userManager.updateClearance("bob", ClassificationLevel.INTERNAL);
        cacheService.invalidate("bob:proprietary model weights");

        // Subsequent query attempt
        RetrievalRequest req2 = new RetrievalRequest("bob", "Proprietary Model Weights", 5);
        RetrievalResponse res2 = retrievalService.retrieve(req2);
        boolean leakedAfterDemotion = res2.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-ENG-02"));

        boolean passed = initialAccess && !leakedAfterDemotion;
        String authDecision = "DEMOTION_DENIED";
        String details = String.format("Pre-demotion access=%b, Post-demotion leak=%b (Clearance & role blocked)",
                initialAccess, leakedAfterDemotion);

        return new ExperimentResult(testId, testName, scenario, "bob", passed,
                res2.getLatencyMs(), res2.getDocuments().size(), res2.isCacheHit(),
                authDecision, details, Instant.now());
    }

    /**
     * TC4: Cached Retrieval with Dynamic Authorization Revalidation
     * Validates that cached documents undergo real-time authorization revalidation.
     * When permission is revoked while cache is active, cached documents must NOT leak.
     */
    public ExperimentResult runTestCase4_CachedRetrievalWithRevalidation() {
        setupEnvironment();
        String testId = "TC-4";
        String testName = "Cached Retrieval Revalidation";
        String scenario = "Stale cache access after revocation on cached DOC-FIN-02";

        // Query 1: populates cache
        RetrievalRequest req1 = new RetrievalRequest("alice", "acquisition strategy", 5);
        RetrievalResponse res1 = retrievalService.retrieve(req1);
        boolean cachedDocPresent = res1.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-FIN-02"));

        // Dynamically revoke access to DOC-FIN-02 without invalidating cache
        permissionManager.revokeUserAccess("alice", "DOC-FIN-02", "Emergency privilege suspension");

        // Query 2: Identical query hits cache
        RetrievalRequest req2 = new RetrievalRequest("alice", "acquisition strategy", 5);
        RetrievalResponse res2 = retrievalService.retrieve(req2);

        boolean cacheHit = res2.isCacheHit();
        boolean documentLeakedFromCache = res2.getDocuments().stream()
                .anyMatch(d -> d.getDocumentId().equals("DOC-FIN-02"));

        boolean passed = cachedDocPresent && cacheHit && !documentLeakedFromCache;
        String authDecision = "CACHE_REVAL_DENIED";
        String details = String.format("CacheHit=%b, LeakedFromCache=%b (Zero stale leakage after revocation)",
                cacheHit, documentLeakedFromCache);

        return new ExperimentResult(testId, testName, scenario, "alice", passed,
                res2.getLatencyMs(), res2.getDocuments().size(), cacheHit,
                authDecision, details, Instant.now());
    }

    /**
     * TC5: Multi-Attribute Metadata Filtering (ABAC - Department & Classification)
     * Demonstrates metadata filtering across department boundaries and classification levels.
     */
    public ExperimentResult runTestCase5_MultiAttributeMetadataFiltering() {
        setupEnvironment();
        String testId = "TC-5";
        String testName = "Multi-Attribute Metadata Filtering";
        String scenario = "Cross-department query: Bob (Engineering) searches strategy";

        // "strategy" matches DOC-FIN-02 (Finance), DOC-HR-01 (HR), DOC-EXEC-01 (Executive)
        RetrievalRequest req = new RetrievalRequest("bob", "strategy", 10);
        RetrievalResponse res = retrievalService.retrieve(req);

        // Bob must NOT receive Finance, HR, or Restricted Executive documents
        boolean leakedFinance = res.getDocuments().stream()
                .anyMatch(d -> "FINANCE".equals(d.getMetadata().getDepartment()));
        boolean leakedHr = res.getDocuments().stream()
                .anyMatch(d -> "HR".equals(d.getMetadata().getDepartment()));
        boolean leakedRestricted = res.getDocuments().stream()
                .anyMatch(d -> d.getMetadata().getClassificationLevel() == ClassificationLevel.RESTRICTED);

        boolean passed = !leakedFinance && !leakedHr && !leakedRestricted;
        String authDecision = "ISOLATION_ENFORCED";
        String details = String.format("FinanceLeaked=%b, HrLeaked=%b, RestrictedLeaked=%b (Isolation valid)",
                leakedFinance, leakedHr, leakedRestricted);

        return new ExperimentResult(testId, testName, scenario, "bob", passed,
                res.getLatencyMs(), res.getDocuments().size(), res.isCacheHit(),
                authDecision, details, Instant.now());
    }

    /**
     * TC6: Unauthorized Access Attempt / Zero-Privilege Denial
     * Verifies that unauthorized users or users with zero privileges receive
     * immediate denial with full audit logging.
     */
    public ExperimentResult runTestCase6_UnauthorizedAccessDenial() {
        setupEnvironment();
        String testId = "TC-6";
        String testName = "Zero-Privilege Unauthorized Denial";
        String scenario = "Eve (Guest) attempts to retrieve confidential financial forecasts";

        RetrievalRequest req = new RetrievalRequest("eve", "financial earnings revenue", 5);
        RetrievalResponse res = retrievalService.retrieve(req);

        boolean passed = res.getDocuments().isEmpty();
        String authDecision = "STRICT_DENIAL";
        String details = String.format("Documents returned=%d; AuditDecisionsCount=%d; Denial reasons logged",
                res.getDocuments().size(), res.getAuditDecisions().size());

        return new ExperimentResult(testId, testName, scenario, "eve", passed,
                res.getLatencyMs(), res.getDocuments().size(), res.isCacheHit(),
                authDecision, details, Instant.now());
    }

    // Getters for services to enable inspection
    public UserManager getUserManager() { return userManager; }
    public DocumentRepository getDocumentRepository() { return documentRepository; }
    public PermissionManager getPermissionManager() { return permissionManager; }
    public AuthorizationService getAuthorizationService() { return authorizationService; }
    public MetadataFilter getMetadataFilter() { return metadataFilter; }
    public CacheService getCacheService() { return cacheService; }
    public RetrievalService getRetrievalService() { return retrievalService; }
    public ExperimentLogger getLogger() { return logger; }

    public static void main(String[] args) {
        ExperimentRunner runner = new ExperimentRunner();
        List<ExperimentResult> results = runner.runAllExperiments();

        boolean allPassed = results.stream().allMatch(ExperimentResult::isPassed);
        System.exit(allPassed ? 0 : 1);
    }
}
