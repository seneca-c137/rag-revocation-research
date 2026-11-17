package edu.academic.rag.accesscontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.academic.rag.accesscontrol.dataset.SyntheticDataset;
import edu.academic.rag.accesscontrol.dataset.SyntheticDatasetExporter;
import edu.academic.rag.accesscontrol.dataset.SyntheticDatasetGenerator;
import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticDatasetTest {
    @TempDir
    Path tempDirectory;

    private final SyntheticDatasetGenerator generator = new SyntheticDatasetGenerator();

    @Test
    void generatesAllRequestedSizesWithUniqueDocumentsAndValidPermissionMetadata() {
        Map<Integer, SyntheticDataset> datasets = generator.generateAll();

        assertEquals(List.of(100, 1_000, 5_000, 10_000), new ArrayList<>(datasets.keySet()));
        for (Map.Entry<Integer, SyntheticDataset> entry : datasets.entrySet()) {
            SyntheticDataset dataset = entry.getValue();
            assertEquals(entry.getKey(), dataset.documents().size());
            assertEquals(dataset.documents().size(), dataset.documents().stream()
                    .map(Document::getDocumentId).distinct().count());
            assertEquals(6, dataset.users().size());
            assertEquals(6, dataset.queries().size());

            Set<String> userIds = dataset.users().stream().map(user -> user.getUserId()).collect(Collectors.toSet());
            for (Document document : dataset.documents()) {
                assertFalse(document.getDocumentId().isBlank());
                assertFalse(document.getContent().isBlank());
                assertFalse(document.getMetadata().getDepartment().isBlank());
                assertNotNull(document.getMetadata().getClassificationLevel());
                assertFalse(document.getMetadata().getAllowedRoles().isEmpty());
                assertTrue(document.getMetadata().getAllowedUsers().stream().allMatch(userIds::contains));
                assertTrue(document.getMetadata().getCustomAttributes().containsKey("datasetTopicId"));
            }

            assertEquals(6, dataset.users().stream().map(user -> user.getDepartment()).distinct().count());
            assertEquals(ClassificationLevel.values().length,
                    dataset.users().stream().map(user -> user.getClearanceLevel()).distinct().count());
            assertEquals(6, dataset.users().stream().flatMap(user -> user.getRoles().stream()).distinct().count());
        }
    }

    @Test
    void sameSeedProducesIdenticalJsonExportsAndRelevanceLabelsDoNotDependOnPermissions() throws Exception {
        long seed = 91357L;
        SyntheticDataset first = generator.generate(100, seed);
        SyntheticDataset second = generator.generate(100, seed);
        Path firstFile = new SyntheticDatasetExporter().export(first, tempDirectory.resolve("first"));
        Path secondFile = new SyntheticDatasetExporter().export(second, tempDirectory.resolve("second"));

        assertArrayEquals(Files.readAllBytes(firstFile), Files.readAllBytes(secondFile));
        assertEquals(first.relevanceGroundTruth(), second.relevanceGroundTruth());

        SyntheticDataset.Query financeQuery = first.queries().stream()
                .filter(query -> "FINANCE".equals(query.topicId())).findFirst().orElseThrow();
        String relevantId = first.relevanceGroundTruth().get(financeQuery.queryId()).iterator().next();
        Document relevantDocument = first.documents().stream()
                .filter(document -> relevantId.equals(document.getDocumentId())).findFirst().orElseThrow();
        Set<String> originalLabels = first.relevanceGroundTruth().get(financeQuery.queryId());
        String currentRole = relevantDocument.getMetadata().getAllowedRoles().iterator().next();

        relevantDocument.getMetadata().removeAllowedRole(currentRole);
        relevantDocument.getMetadata().addAllowedRole("ROLE_CHANGED_AFTER_LABELING");

        assertEquals(originalLabels, first.relevanceGroundTruth().get(financeQuery.queryId()));
        assertTrue(originalLabels.contains(relevantId));
        assertEquals(financeQuery.topicId(),
                relevantDocument.getMetadata().getCustomAttributes().get("datasetTopicId"));
    }

    @Test
    void predefinedQueriesAreSearchableAndLabelsMatchTopicsOnly() {
        SyntheticDataset dataset = generator.generate(1_000);
        DocumentRepository repository = new DocumentRepository();
        dataset.documents().forEach(repository::addDocument);

        for (SyntheticDataset.Query query : dataset.queries()) {
            Set<String> expectedRelevant = dataset.relevanceGroundTruth().get(query.queryId());
            assertFalse(expectedRelevant.isEmpty());
            assertTrue(expectedRelevant.stream().allMatch(documentId ->
                    dataset.documents().stream()
                            .filter(document -> documentId.equals(document.getDocumentId()))
                            .anyMatch(document -> query.topicId().equals(
                                    document.getMetadata().getCustomAttributes().get("datasetTopicId")))));
            List<Document> matches = repository.search(query.queryText(), 100);
            assertFalse(matches.isEmpty());
            assertTrue(matches.stream().allMatch(document -> expectedRelevant.contains(document.getDocumentId())));
        }
    }

    @Test
    void evaluatesConventionalAndAuthorizedPrecisionAtKIndependently() {
        SyntheticDataset dataset = generator.generate(100);
        SyntheticDataset.Query query = dataset.queries().getFirst();
        List<String> relevant = new ArrayList<>(dataset.relevanceGroundTruth().get(query.queryId()));
        String relevantId = relevant.getFirst();
        String nonRelevantId = dataset.documents().stream()
                .map(Document::getDocumentId)
                .filter(id -> !dataset.relevanceGroundTruth().get(query.queryId()).contains(id))
                .findFirst().orElseThrow();

        SyntheticDataset.PrecisionAtK metrics = dataset.evaluatePrecisionAtK(
                query.queryId(), List.of(relevantId, nonRelevantId), 3, Set.of());

        assertEquals(1, metrics.relevantRetrieved());
        assertEquals(0, metrics.authorizedRelevantRetrieved());
        assertEquals(1.0 / 3.0, metrics.precisionAtK(), 0.00001);
        assertEquals(0.0, metrics.authorizedRelevantPrecisionAtK());
        assertThrows(IllegalArgumentException.class,
                () -> dataset.evaluatePrecisionAtK(query.queryId(), List.of(), 0, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> dataset.evaluatePrecisionAtK("unknown", List.of(), 1, Set.of()));
    }

    @Test
    void exportsMetadataDocumentsUsersQueriesAndGroundTruthForAllSizes() throws Exception {
        Map<Integer, SyntheticDataset> datasets = generator.generateAll(24680L);
        List<Path> exports = new SyntheticDatasetExporter()
                .exportAll(datasets, tempDirectory.resolve("all-datasets"));

        assertEquals(4, exports.size());
        ObjectMapper mapper = new ObjectMapper();
        for (Path export : exports) {
            JsonNode root = mapper.readTree(export.toFile());
            int count = root.path("metadata").path("documentCount").asInt();
            assertTrue(SyntheticDatasetGenerator.supportedSizes().contains(count));
            assertEquals(count, root.path("documents").size());
            assertEquals(6, root.path("users").size());
            assertEquals(6, root.path("queries").size());
            assertEquals(6, root.path("relevanceGroundTruth").size());
            assertFalse(root.path("documents").get(0).has("createdAt"));
            assertTrue(root.path("documents").get(0).path("allowedRoles").size() > 0);
            assertTrue(root.path("documents").get(0).path("content").asText().length() > 0);
        }
    }
}
