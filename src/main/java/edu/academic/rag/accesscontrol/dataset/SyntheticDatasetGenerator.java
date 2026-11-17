package edu.academic.rag.accesscontrol.dataset;

import edu.academic.rag.accesscontrol.model.*;

import java.util.*;

/**
 * Generates isolated, deterministic datasets for scale and metric experiments.
 * The fixed topic assignment is generated before permission metadata is applied;
 * relevance labels are then derived from topic IDs only.
 */
public final class SyntheticDatasetGenerator {
    public static final long DEFAULT_SEED = 20261117L;
    public static final String GENERATOR_VERSION = "synthetic-rag-v1";
    private static final List<Integer> SUPPORTED_SIZES = List.of(100, 1_000, 5_000, 10_000);
    private static final List<Topic> TOPICS = List.of(
            new Topic("FINANCE", "FINANCE", "ROLE_FINANCE_ANALYST", "treasury dividend ledger",
                    "A simulated corporate finance record."),
            new Topic("ENGINEERING", "ENGINEERING", "ROLE_ENGINEER", "compiler kernel protocol",
                    "A simulated software engineering record."),
            new Topic("HUMAN_RESOURCES", "HR", "ROLE_HR_MANAGER", "onboarding benefits payroll",
                    "A simulated human resources record."),
            new Topic("LEGAL", "LEGAL", "ROLE_LEGAL_COUNSEL", "statute litigation contract",
                    "A simulated legal affairs record."),
            new Topic("SECURITY", "SECURITY", "ROLE_SECURITY_ANALYST", "cipher credential threat",
                    "A simulated information security record."),
            new Topic("RESEARCH", "RESEARCH", "ROLE_RESEARCHER", "genomics telescope climate",
                    "A simulated scientific research record.")
    );

    public static List<Integer> supportedSizes() {
        return SUPPORTED_SIZES;
    }

    public SyntheticDataset generate(int documentCount) {
        return generate(documentCount, DEFAULT_SEED);
    }

    public SyntheticDataset generate(int documentCount, long seed) {
        if (!SUPPORTED_SIZES.contains(documentCount)) {
            throw new IllegalArgumentException("Unsupported dataset size: " + documentCount);
        }

        Random random = new Random(seed);
        List<Document> documents = new ArrayList<>(documentCount);
        Map<String, String> topicByDocumentId = new LinkedHashMap<>();
        for (int index = 0; index < documentCount; index++) {
            Topic topic = TOPICS.get(index % TOPICS.size());
            String documentId = String.format(Locale.ROOT, "SYN-DOC-%05d", index + 1);
            ClassificationLevel classification = ClassificationLevel.values()[
                    random.nextInt(ClassificationLevel.values().length)];
            DocumentMetadata.Builder metadataBuilder = DocumentMetadata.builder()
                    .classificationLevel(classification)
                    .department(topic.department())
                    .allowedRoles(topic.requiredRole())
                    .attribute("datasetTopicId", topic.id())
                    .attribute("datasetSeed", Long.toString(seed));
            if (random.nextInt(5) == 0) {
                metadataBuilder.allowedUsers(topic.datasetUserId());
            }

            String title = topic.id().replace('_', ' ') + " record " +
                    String.format(Locale.ROOT, "%05d", index + 1);
            String content = topic.queryText() + ". " + topic.description() +
                    " Synthetic record number " + (index + 1) + " from seed " + seed + ".";
            documents.add(new Document(documentId, title, content, metadataBuilder.build()));
            topicByDocumentId.put(documentId, topic.id());
        }

        List<User> users = createUsers();
        List<SyntheticDataset.Query> queries = new ArrayList<>(TOPICS.size());
        Map<String, Set<String>> groundTruth = new LinkedHashMap<>();
        for (Topic topic : TOPICS) {
            String queryId = "QUERY-" + topic.id();
            queries.add(new SyntheticDataset.Query(queryId, topic.queryText(), topic.id()));
            Set<String> relevantDocumentIds = new LinkedHashSet<>();
            topicByDocumentId.forEach((documentId, topicId) -> {
                if (topic.id().equals(topicId)) {
                    relevantDocumentIds.add(documentId);
                }
            });
            groundTruth.put(queryId, relevantDocumentIds);
        }

        SyntheticDataset.Metadata datasetMetadata = new SyntheticDataset.Metadata(
                GENERATOR_VERSION + "-" + documentCount + "-" + seed,
                documentCount,
                seed,
                users.size(),
                queries.size(),
                GENERATOR_VERSION,
                "A document is relevant exactly when its generated topic ID matches the query topic ID; permissions are not consulted."
        );
        return new SyntheticDataset(datasetMetadata, documents, users, queries, groundTruth);
    }

    public Map<Integer, SyntheticDataset> generateAll() {
        return generateAll(DEFAULT_SEED);
    }

    public Map<Integer, SyntheticDataset> generateAll(long seed) {
        Map<Integer, SyntheticDataset> datasets = new LinkedHashMap<>();
        for (int size : SUPPORTED_SIZES) {
            datasets.put(size, generate(size, seed));
        }
        return Collections.unmodifiableMap(datasets);
    }

    private List<User> createUsers() {
        List<User> users = new ArrayList<>(TOPICS.size());
        users.add(createUser("dataset-user-finance", "Finance Analyst", "FINANCE",
                ClassificationLevel.CONFIDENTIAL, "ROLE_FINANCE_ANALYST"));
        users.add(createUser("dataset-user-engineering", "Engineer", "ENGINEERING",
                ClassificationLevel.RESTRICTED, "ROLE_ENGINEER"));
        users.add(createUser("dataset-user-hr", "HR Manager", "HR",
                ClassificationLevel.INTERNAL, "ROLE_HR_MANAGER"));
        users.add(createUser("dataset-user-legal", "Legal Counsel", "LEGAL",
                ClassificationLevel.CONFIDENTIAL, "ROLE_LEGAL_COUNSEL"));
        users.add(createUser("dataset-user-security", "Security Analyst", "SECURITY",
                ClassificationLevel.RESTRICTED, "ROLE_SECURITY_ANALYST"));
        users.add(createUser("dataset-user-research", "Researcher", "RESEARCH",
                ClassificationLevel.PUBLIC, "ROLE_RESEARCHER"));
        return users;
    }

    private User createUser(String id, String name, String department, ClassificationLevel clearance, String role) {
        User user = new User(id, name, department, clearance);
        user.addRole(role);
        return user;
    }

    private record Topic(String id, String department, String requiredRole, String queryText, String description) {
        private String datasetUserId() {
            return "dataset-user-" + switch (id) {
                case "HUMAN_RESOURCES" -> "hr";
                default -> id.toLowerCase(Locale.ROOT);
            };
        }
    }
}
