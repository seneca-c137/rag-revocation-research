package edu.academic.rag.accesscontrol.model;

/**
 * Request payload for document retrieval in the RAG system.
 */
public class RetrievalRequest {
    private final String userId;
    private final String query;
    private final int topK;

    public RetrievalRequest(String userId, String query, int topK) {
        this.userId = userId;
        this.query = query != null ? query : "";
        this.topK = topK > 0 ? topK : 5;
    }

    public RetrievalRequest(String userId, String query) {
        this(userId, query, 5);
    }

    public String getUserId() {
        return userId;
    }

    public String getQuery() {
        return query;
    }

    public int getTopK() {
        return topK;
    }

    @Override
    public String toString() {
        return "RetrievalRequest{" +
                "userId='" + userId + '\'' +
                ", query='" + query + '\'' +
                ", topK=" + topK +
                '}';
    }
}
