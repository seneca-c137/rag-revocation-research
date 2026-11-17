package edu.academic.rag.accesscontrol;

import edu.academic.rag.accesscontrol.manager.PermissionManager;
import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.DocumentMetadata;
import edu.academic.rag.accesscontrol.model.User;
import edu.academic.rag.accesscontrol.service.MetadataFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MetadataFilter Unit Tests")
class MetadataFilterTest {
    private PermissionManager permissionManager;
    private MetadataFilter filter;

    @BeforeEach
    void setUp() {
        permissionManager = new PermissionManager();
        filter = new MetadataFilter(permissionManager);
    }

    @Test
    @DisplayName("Should filter out documents exceeding user clearance")
    void testClearanceFilter() {
        User user = new User("u1", "Charlie", "ENGINEERING", ClassificationLevel.INTERNAL);
        user.addRole("ROLE_ENG");

        Document internalDoc = new Document("d1", "Internal Spec", "Text",
                DocumentMetadata.builder().classificationLevel(ClassificationLevel.INTERNAL).department("ENGINEERING").build());
        Document restrictedDoc = new Document("d2", "Top Secret", "Text",
                DocumentMetadata.builder().classificationLevel(ClassificationLevel.RESTRICTED).department("ENGINEERING").build());

        List<Document> filtered = filter.filter(List.of(internalDoc, restrictedDoc), user);
        assertEquals(1, filtered.size());
        assertEquals("d1", filtered.getFirst().getDocumentId());
    }

    @Test
    @DisplayName("Should exclude revoked documents in pre-filtering")
    void testRevokedDocumentExclusion() {
        User user = new User("u1", "Alice", "FINANCE", ClassificationLevel.CONFIDENTIAL);
        user.addRole("ROLE_FINANCE");

        Document doc1 = new Document("d1", "Report 1", "Text",
                DocumentMetadata.builder().classificationLevel(ClassificationLevel.CONFIDENTIAL).department("FINANCE").build());
        Document doc2 = new Document("d2", "Report 2", "Text",
                DocumentMetadata.builder().classificationLevel(ClassificationLevel.CONFIDENTIAL).department("FINANCE").build());

        permissionManager.revokeUserAccess("u1", "d1");

        List<Document> filtered = filter.filter(List.of(doc1, doc2), user);
        assertEquals(1, filtered.size());
        assertEquals("d2", filtered.getFirst().getDocumentId());
    }
}
