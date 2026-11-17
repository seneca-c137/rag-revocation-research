package edu.academic.rag.accesscontrol.model;

import java.util.Objects;

/**
 * Represents a document stored in the RAG repository.
 */
public class Document {
    private final String documentId;
    private final String title;
    private final String content;
    private final DocumentMetadata metadata;

    public Document(String documentId, String title, String content, DocumentMetadata metadata) {
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.title = title != null ? title : "";
        this.content = content != null ? content : "";
        this.metadata = metadata != null ? metadata : new DocumentMetadata();
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public DocumentMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Document document = (Document) o;
        return Objects.equals(documentId, document.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId);
    }

    @Override
    public String toString() {
        return "Document{" +
                "documentId='" + documentId + '\'' +
                ", title='" + title + '\'' +
                ", metadata=" + metadata +
                '}';
    }
}
