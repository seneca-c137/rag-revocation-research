# System Architecture

The diagram reflects the Java implementation: an in-memory document and cache layer, lexical retrieval, metadata-based filtering, and current-state authorization. The experiment harnesses are shown separately from the request and policy paths.

```mermaid
flowchart LR
    subgraph REQUEST["Retrieval request path"]
        direction TB
        Caller["Caller<br/>RetrievalRequest"]
        Retrieval["RetrievalService<br/>user lookup and orchestration"]
        UserManager["UserManager<br/>active status, roles,<br/>department, clearance"]
        Cache["CacheService<br/>in-memory result cache"]
        Repository["DocumentRepository<br/>in-memory document store"]
        MetadataFilter["MetadataFilter<br/>pre-retrieval eligibility"]
        Ranking["Lexical top-K ranking<br/>(inside RetrievalService)"]
        Response["RetrievalResponse<br/>authorized documents + decisions"]

        Caller -->|retrieve request| Retrieval
        Retrieval -->|getUser(userId)| UserManager
        UserManager -->|current User| Retrieval
        Retrieval -->|cache lookup with current User| Cache
        Cache -->|cache miss| Retrieval
        Retrieval -->|getAllDocuments()| Repository
        Repository -->|Document records| Retrieval
        Retrieval -->|filter(documents, user)| MetadataFilter
        MetadataFilter -->|eligible candidates| Retrieval
        Retrieval -->|rank candidates| Ranking
        Retrieval -->|evaluate ranked documents| Authorization
        Authorization -->|decisions| Retrieval
        Ranking -->|ranked top-K documents| Authorization
        Retrieval -->|cache authorized results| Cache
        Cache -->|authorized cached results| Retrieval
        Retrieval -->|response| Response
    end

    subgraph AUTH["Authorization path (fresh and cached documents)"]
        direction TB
        Authorization["AuthorizationService<br/>current per-document decision"]
    end

    subgraph DATA["Document data"]
        direction TB
        Document["Document<br/>document ID, title, content"]
        DocumentMetadata["DocumentMetadata<br/>department, classification,<br/>allowed roles/users, custom attributes"]
        Document -->|contains| DocumentMetadata
    end

    subgraph POLICY["Permission-management path"]
        direction TB
        PermissionManager["PermissionManager<br/>in-memory user and role grants/revocations"]
        PermissionState["PermissionState<br/>GRANTED / REVOKED / UNSPECIFIED"]
        RevocationRecord["RevocationRecord<br/>principal, target type, document ID,<br/>action, previous/updated state,<br/>reason, timestamp"]
        PermissionManager -->|maintains current state| PermissionState
        PermissionManager -->|appends grant/revoke transition| RevocationRecord
    end

    subgraph EXPERIMENT["Experiment and audit export path"]
        direction TB
        ExperimentRunner["ExperimentRunner"]
        RepeatedRunner["RepeatedExperimentRunner"]
        Metrics["ResearchMetricsCollector"]
        ExperimentLogger["ExperimentLogger"]
        RepeatedLogger["RepeatedExperimentLogger"]
        Exports["CSV/JSON experiment outputs<br/>reproducibility snapshot JSON"]

        ExperimentRunner -->|records observations| Metrics
        ExperimentRunner -->|results, revocation history, metrics| ExperimentLogger
        RepeatedRunner -->|repeated trial results| RepeatedLogger
        ExperimentLogger --> Exports
        RepeatedLogger --> Exports
    end

    Cache -->|revalidate each cached document| Authorization
    Authorization -->|authorization decisions| Cache
    Authorization -->|permission state queries| PermissionManager
    MetadataFilter -->|permission state checks| PermissionManager
    DocumentRepository -->|stores and returns| Document
    Document -->|content for ranking| Retrieval
    DocumentMetadata -->|policy attributes| MetadataFilter
    DocumentMetadata -->|policy attributes| Authorization
    ExperimentRunner -->|revocation scenarios| Retrieval
    ExperimentRunner -->|revokeUserAccess and history lookup| PermissionManager
    RepeatedRunner -.->|constructs per-trial component instances| Retrieval
    PermissionManager -->|revocation history| ExperimentRunner
    ExperimentRunner -->|exports research metrics| ExperimentLogger
    Metrics -->|observations| ExperimentLogger

    classDef request fill:#eaf3f8,stroke:#28627a,color:#142b36;
    classDef policy fill:#fff3dd,stroke:#9a6b16,color:#352608;
    classDef data fill:#edf5eb,stroke:#4e7a45,color:#1e301b;
    classDef experiment fill:#f1eef8,stroke:#69528a,color:#2c223a;
    class Caller,Retrieval,UserManager,Cache,Repository,MetadataFilter,Ranking,Response request;
    class Authorization policy;
    class PermissionManager,PermissionState,RevocationRecord policy;
    class Document,DocumentMetadata data;
    class ExperimentRunner,RepeatedRunner,Metrics,ExperimentLogger,RepeatedLogger,Exports experiment;
```

`PermissionManager` keeps the full grant/revoke transition history in memory. The baseline runner gathers revocation history for its reproducibility snapshot; experiment metrics and repeated-trial observations are exported through their respective loggers. There is no separate audit service or durable audit database. The repository and cache are in-memory, and retrieval ranking is lexical; no vector database, embedding model, or LLM is part of this implementation.
