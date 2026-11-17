# Revocation-Aware Dynamic Access Control for Retrieval-Augmented Generation (RAG) Systems

## Academic Prototype Overview

This repository implements an academic experimental prototype of **Revocation-Aware Dynamic Access Control for RAG Systems Using Metadata-Based Authorization**.

The core research objective is to guarantee dynamic authorization consistency and zero-privilege leakage in RAG pipelines, solving the stale access window vulnerability where documents retrieved or cached prior to permission revocation might inadvertently leak to unauthorized users.

---

## Architectural Separation of Concerns

The architecture strictly separates responsibilities into modular components:

| Component | Responsibility |
| :--- | :--- |
| **`UserManager`** | Manages user identities, active/inactive statuses, role sets, departments, and hierarchical clearance levels (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`). |
| **`DocumentRepository`** | In-memory simulated vector/document store providing lexical and semantic query relevance scoring. |
| **`PermissionManager`** | Manages explicit user and role grants, dynamic revocations, and audit history. Explicit revocations override all metadata and role permissions immediately. |
| **`AuthorizationService`** | Dynamic policy engine evaluating RBAC, ABAC (department isolation and clearance levels), explicit grants, and dynamic revocation checks in real time. |
| **`MetadataFilter`** | Pre-retrieval metadata filter that prunes the candidate search space based on user clearance, department, and role constraints prior to ranking. |
| **`CacheService`** | High-performance query retrieval cache equipped with **mandatory dynamic authorization revalidation** before returning cached items. |
| **`RetrievalService`** | Coordinates the full secure RAG retrieval pipeline: user identity resolution, cache revalidation, metadata filtering, candidate scoring, post-retrieval authorization validation, and telemetry collection. |
| **`ExperimentRunner`** | Executes the 6 experimental scenarios (TC-1 to TC-6) and orchestrates environment state transitions. |
| **`ExperimentLogger`** | Exports test telemetry, authorization decisions, and latencies to formatted console tables, CSV (`results/experiment_results.csv`), and JSON (`results/experiment_results.json`). |

---

## The Six Experimental Test Cases

1. **TC-1: Baseline Authorized Retrieval**
   - **Scenario**: Alice (Finance Analyst, `CONFIDENTIAL` clearance) requests financial reports.
   - **Validation**: Verifies authorized retrieval of `DOC-FIN-01`, with matching role, department, and clearance.
   - **Decision**: `ALLOWED`.

2. **TC-2: Direct Permission Revocation (Revocation Awareness)**
   - **Scenario**: Alice initially retrieves `DOC-FIN-01`. Access is subsequently revoked dynamically via `PermissionManager`.
   - **Validation**: Subsequent retrieval strictly denies access and omits the document.
   - **Decision**: `REVOKED_DENIED`.

3. **TC-3: Dynamic Role Demotion and Clearance Downgrade**
   - **Scenario**: Bob is demoted from `ROLE_ENGINEER` (`CONFIDENTIAL`) to `ROLE_INTERN` (`INTERNAL`).
   - **Validation**: Subsequent queries for proprietary engineering documents are blocked due to role absence and clearance mismatch.
   - **Decision**: `DEMOTION_DENIED`.

4. **TC-4: Cached Retrieval with Dynamic Authorization Revalidation (Stale Cache Prevention)**
   - **Scenario**: Alice retrieves and caches `DOC-FIN-02`. Her permission is revoked while the cache entry remains active.
   - **Validation**: An identical subsequent query triggers a cache hit, but dynamic authorization revalidation intercepts and purges `DOC-FIN-02`. Zero unauthorized documents leak from the cache.
   - **Decision**: `CACHE_REVAL_DENIED`.

5. **TC-5: Multi-Attribute Metadata Filtering (ABAC Boundary Enforcement)**
   - **Scenario**: Bob (Engineering) executes a broad query matching documents across multiple departments (Finance, HR, Executive).
   - **Validation**: Pre-retrieval filtering and final authorization ensure Bob receives only documents within his department and clearance boundaries. Finance, HR, and Restricted documents are strictly excluded.
   - **Decision**: `ISOLATION_ENFORCED`.

6. **TC-6: Zero-Privilege Unauthorized Denial**
   - **Scenario**: Eve (Guest user with `PUBLIC` clearance and zero roles) attempts to query confidential documents.
   - **Validation**: Immediate denial with zero documents returned and full audit trail logging.
   - **Decision**: `STRICT_DENIAL`.

---

## Test Execution and Reproduction

### 1. Execute All Unit and Integration Tests via Maven

```bash
mvn test
```
*(Or offline mode: `mvn test -o`)*

### 2. Run the Full Experimental Suite and Export Results

```bash
./run-experiments.sh
```

### 3. Output Artifacts

- **Console Summary**: Formatted tabular presentation with per-test status, documents returned, latency measurements, cache hit rates, and authorization decisions.
- **CSV Results**: `results/experiment_results.csv`
- **JSON Results**: `results/experiment_results.json`
