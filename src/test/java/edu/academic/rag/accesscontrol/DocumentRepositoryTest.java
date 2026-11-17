package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.DocumentMetadata;
import edu.academic.rag.accesscontrol.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DocumentRepository Unit Tests")
class DocumentRepositoryTest {
    private DocumentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DocumentRepository();
    }

    @Test
    @DisplayName("Should add, retrieve, and count documents")
    void testAddAndRetrieve() {
        Document doc = new Document("d1", "Test Doc", "Sample content",
                DocumentMetadata.builder().classificationLevel(ClassificationLevel.INTERNAL).build());
        repository.addDocument(doc);

        assertEquals(1, repository.count());
        Optional<Document> retrieved = repository.getDocument("d1");
        assertTrue(retrieved.isPresent());
        assertEquals("Test Doc", retrieved.get().getTitle());
    }

    @Test
    @DisplayName("Should rank documents by relevance score")
    void testRelevanceSearch() {
        Document doc1 = new Document("d1", "Quarterly Financial Analysis", "Revenue and earnings data", null);
        Document doc2 = new Document("d2", "Engineering Architecture", "Microservice design", null);
        Document doc3 = new Document("d3", "Financial Forecast", "Long term projections", null);

        repository.addDocument(doc1);
        repository.addDocument(doc2);
        repository.addDocument(doc3);

        List<Document> results = repository.search("financial", 5);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(d -> d.getDocumentId().equals("d1")));
        assertTrue(results.stream().anyMatch(d -> d.getDocumentId().equals("d3")));
    }

    @Test
    @DisplayName("Should delete documents accurately")
    void testRemoveDocument() {
        Document doc = new Document("d1", "Title", "Content", null);
        repository.addDocument(doc);
        assertEquals(1, repository.count());

        assertTrue(repository.removeDocument("d1"));
        assertEquals(0, repository.count());
        assertFalse(repository.getDocument("d1").isPresent());
    }
}
