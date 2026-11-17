# Task 7 — Statistical Analysis and Research Interpretation

## Scope and evidence

This analysis uses only the existing Task 6 observations, summaries, reproducibility metadata, and the four exported synthetic datasets. It does not rerun experiments or alter Task 6 data. All empirical claims below refer to this simulated in-memory lexical prototype.

Primary evidence: [Task 6 raw JSON](../task6/repeated_experiment_raw.json), [Task 6 raw CSV](../task6/repeated_experiment_raw.csv), [Task 6 aggregates](../task6/repeated_experiment_summary.json), [reproducibility metadata](../task6/reproducibility_metadata.json), and [seeded dataset exports](../../datasets/generated/).

## Data and validation

- Seed: `20261117`; 30 measured repetitions and one warm-up per dataset.
- Datasets: 100, 1000, 5000, 10000 documents.
- Raw observations: 868 scenario rows (840 measured, 28 warm-up), 1240 requests, 8618 document observations, 868 event observations, and 124 precision observations.
- Raw CSV payloads matched the JSON observations (11718 rows); the Task 6 summary CSV matched its JSON aggregate (120 metrics).
- All 840 measured scenarios passed; warm-ups are excluded below.

## Methods

Descriptive statistics are computed over individual measured observations: arithmetic mean, median, sample standard deviation (denominator n−1), minimum, maximum, and nearest-rank P95/P99 (`sorted[ceil(p·n)−1]`). Timing confidence intervals are 95% percentile bootstrap intervals for the mean, resampling the 30 repetition-level block means with replacement (10,000 deterministic resamples). This treats repetitions—not the nested requests/documents/events—as the resampling unit.

Binary rates use 95% Wilson score intervals at the request, returned-document, or event unit. Those nominal intervals assume independent Bernoulli observations; repeated observations within a repetition may be correlated. Therefore, the report also records repetition-level counts of any unauthorized exposure or any failed event with Wilson intervals over the 30 repetition blocks. The confidence intervals quantify sampling uncertainty under these assumptions; they do not establish causality or statistical significance. Precision confidence intervals resample repetition blocks.

## Security outcomes

| Documents | Requests exposed / requests | Request exposure rate (95% Wilson CI) | Unauthorized documents / returned | Document exposure rate (95% Wilson CI) | Processed | Duplicate | Failed | Event handling success |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 0 / 300 | 0.0000% [0.0000%, 1.2643%] | 0 / 1950 | 0.0000% [0.0000%, 0.1966%] | 180 | 30 | 0 | 210/210 (100.0%; event CI 98.2%–100.0%; block 30/30 CI 88.6%–100.0%) |
| 1000 | 0 / 300 | 0.0000% [0.0000%, 1.2643%] | 0 / 2130 | 0.0000% [0.0000%, 0.1800%] | 180 | 30 | 0 | 210/210 (100.0%; event CI 98.2%–100.0%; block 30/30 CI 88.6%–100.0%) |
| 5000 | 0 / 300 | 0.0000% [0.0000%, 1.2643%] | 0 / 2130 | 0.0000% [0.0000%, 0.1800%] | 180 | 30 | 0 | 210/210 (100.0%; event CI 98.2%–100.0%; block 30/30 CI 88.6%–100.0%) |
| 10000 | 0 / 300 | 0.0000% [0.0000%, 1.2643%] | 0 / 2130 | 0.0000% [0.0000%, 0.1800%] | 180 | 30 | 0 | 210/210 (100.0%; event CI 98.2%–100.0%; block 30/30 CI 88.6%–100.0%) |

Across the measured data there were 1,200 retrieval requests and zero unauthorized results; the returned-document denominator is the number of returned document observations, not the corpus size. Per dataset, 180 events were newly processed and 30 were recognized as duplicates. The Task 6 success convention counts either processed or recognized duplicate handling as successful, so it yields 210/210 per dataset; it must not be interpreted as 210 new permission-state changes. No unauthorized retrieval or event failure was observed. In every dataset no repetition block had an exposure or failure (0/30; block-level Wilson upper bound 11.35%); all events were handled in 30/30 blocks (block-level Wilson CI 88.65%–100%). These finite-run intervals do not establish a zero population risk.

## Latency and scalability

Means below are in milliseconds; brackets contain 95% repetition-cluster bootstrap confidence intervals for the mean. P95/P99, medians, sample standard deviations, minima, and maxima for every metric are in `statistical_summary.csv` and `scalability_comparison.csv`.

| Documents | Fresh retrieval | Cached retrieval | After revocation | Event processing | Revocation enforcement |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 0.2208 [0.1714, 0.2800] | 0.0259 [0.0212, 0.0314] | 0.0904 [0.0745, 0.1079] | 0.0088 [0.0076, 0.0101] | 0.0932 [0.0779, 0.1101] |
| 1000 | 0.5047 [0.4173, 0.5976] | 0.0197 [0.0129, 0.0276] | 0.2307 [0.1651, 0.3162] | 0.0072 [0.0058, 0.0089] | 0.1624 [0.1198, 0.2161] |
| 5000 | 2.0747 [1.9280, 2.2388] | 0.0090 [0.0071, 0.0114] | 0.7264 [0.6688, 0.7914] | 0.0053 [0.0047, 0.0062] | 0.4516 [0.4164, 0.4910] |
| 10000 | 4.0947 [3.9580, 4.2580] | 0.0070 [0.0068, 0.0074] | 1.4249 [1.3774, 1.4818] | 0.0049 [0.0046, 0.0051] | 0.8690 [0.8409, 0.9042] |

The post-revocation phase combines different cache paths: one request per repetition follows explicit cache invalidation (cache miss), while two use a populated cache and revalidate it (cache hit). The split below is computed directly from each request's `cacheHit` field.

| Documents | Cache-miss requests (n, mean [95% CI]) | Cache-hit revalidation requests (n, mean [95% CI]) |
| ---: | ---: | ---: |
| 100 | 30, 0.1811 [0.1479, 0.2152] | 60, 0.0450 [0.0370, 0.0544] |
| 1000 | 30, 0.6450 [0.4497, 0.9119] | 60, 0.0235 [0.0193, 0.0283] |
| 5000 | 30, 2.1585 [1.9894, 2.3519] | 60, 0.0104 [0.0095, 0.0115] |
| 10000 | 30, 4.2554 [4.1127, 4.4295] | 60, 0.0097 [0.0088, 0.0108] |

Observed mean fresh and post-revocation latency increased with corpus size in this run. The 10,000-document/100-document mean ratios were 18.55× for fresh retrieval and 15.76× after revocation. Cached means were lower in this run but did not increase monotonically; event processing means also did not increase with corpus size. The post-revocation cache-miss means increased with corpus size, while cache-hit revalidation means did not; the combined post-revocation trend is therefore not a pure estimate of cache revalidation cost. These are descriptive patterns, not significance claims. Revocation enforcement latency includes the time to the first denied response, so it includes retrieval work after the permission commit; it is distinct from the direct event processing duration.

The observed zero exposure is consistent with the prototype revalidating authorization before returning cached documents, while the longer post-revocation and enforcement timings are consistent with the retrieval path doing additional work. The experiment is not a controlled decomposition of that cost, so this explanation is a mechanism-based interpretation rather than a causal estimate.

## Retrieval quality and label difficulty

All 120 measured Precision@5 observations and all 120 authorized-relevant Precision@5 observations equal 1.0. Each dataset contributes 30 precision observations, all for `QUERY-ENGINEERING`; every observation has five relevant documents and five authorized-relevant documents among the top five. The repetition-bootstrap intervals are consequently degenerate at 1.0 and should not be read as evidence of universal perfect retrieval.

The dataset generator defines relevance by exact topic-ID equality, independently of permissions; each document's content starts with that topic's fixed query phrase. The retrieval scorer awards points for query-token substring matches in title/content, and nonmatching topic documents generally receive zero. Thus, the query is an exact lexical match, there are many relevant documents relative to K=5, and there are few hard negatives. The runner evaluates only the engineering query for Precision@5, despite six queries and ground-truth sets being present. This creates a narrow and likely easy benchmark; these are design-based explanations, not evidence that the scores were manipulated. Relevance labels were not changed.

| Documents | Query | Query text | Relevant labels |
| ---: | --- | --- | ---: |
| 100 | `QUERY-FINANCE` | `treasury dividend ledger` | 17 |
| 100 | `QUERY-ENGINEERING` | `compiler kernel protocol` | 17 |
| 100 | `QUERY-HUMAN_RESOURCES` | `onboarding benefits payroll` | 17 |
| 100 | `QUERY-LEGAL` | `statute litigation contract` | 17 |
| 100 | `QUERY-SECURITY` | `cipher credential threat` | 16 |
| 100 | `QUERY-RESEARCH` | `genomics telescope climate` | 16 |
| 1000 | `QUERY-FINANCE` | `treasury dividend ledger` | 167 |
| 1000 | `QUERY-ENGINEERING` | `compiler kernel protocol` | 167 |
| 1000 | `QUERY-HUMAN_RESOURCES` | `onboarding benefits payroll` | 167 |
| 1000 | `QUERY-LEGAL` | `statute litigation contract` | 167 |
| 1000 | `QUERY-SECURITY` | `cipher credential threat` | 166 |
| 1000 | `QUERY-RESEARCH` | `genomics telescope climate` | 166 |
| 5000 | `QUERY-FINANCE` | `treasury dividend ledger` | 834 |
| 5000 | `QUERY-ENGINEERING` | `compiler kernel protocol` | 834 |
| 5000 | `QUERY-HUMAN_RESOURCES` | `onboarding benefits payroll` | 833 |
| 5000 | `QUERY-LEGAL` | `statute litigation contract` | 833 |
| 5000 | `QUERY-SECURITY` | `cipher credential threat` | 833 |
| 5000 | `QUERY-RESEARCH` | `genomics telescope climate` | 833 |
| 10000 | `QUERY-FINANCE` | `treasury dividend ledger` | 1667 |
| 10000 | `QUERY-ENGINEERING` | `compiler kernel protocol` | 1667 |
| 10000 | `QUERY-HUMAN_RESOURCES` | `onboarding benefits payroll` | 1667 |
| 10000 | `QUERY-LEGAL` | `statute litigation contract` | 1667 |
| 10000 | `QUERY-SECURITY` | `cipher credential threat` | 1666 |
| 10000 | `QUERY-RESEARCH` | `genomics telescope climate` | 1666 |

Primary implementation evidence: the topic-to-label rule is in [`SyntheticDatasetGenerator`](../../src/main/java/edu/academic/rag/accesscontrol/dataset/SyntheticDatasetGenerator.java); the Precision@K denominator and label lookup are in [`SyntheticDataset.evaluatePrecisionAtK`](../../src/main/java/edu/academic/rag/accesscontrol/dataset/SyntheticDataset.java); the selected query and top-K are recorded in [`RepeatedExperimentRunner`](../../src/main/java/edu/academic/rag/accesscontrol/experiment/RepeatedExperimentRunner.java); and lexical token scoring is implemented in [`RetrievalService`](../../src/main/java/edu/academic/rag/accesscontrol/service/RetrievalService.java).

## Interpretation and limitations

- **Measured evidence:** the repeated run reported no unauthorized retrieval/document exposure; all tested revocation/cache scenarios passed; mean fresh and post-revocation timings rose with corpus size; cached timing remained lower but was non-monotonic; event processing showed no observed corpus-size increase.
- **Security/latency trade-off:** dynamic revalidation coincided with continued denial of revoked content. Post-revocation/enforcement latencies were higher than cached-before-revocation means in the observed run, but no causal overhead estimate is isolated.
- **Synthetic scope:** generated documents have fixed topic phrases, deterministic metadata, and a small set of users/queries. This does not model natural-language ambiguity, realistic corpus distributions, concurrency, network/storage effects, or adversarial document content.
- **Retrieval scope:** the implementation performs in-memory lexical ranking, not vector search or an LLM-backed RAG pipeline. These timings do not measure prompt construction, model behavior, or prompt leakage.
- **Statistical scope:** repetitions are only 30 per dataset and share fixed data/configuration; wall-clock timing is sensitive to local JVM/host noise. Intervals describe this run under the stated resampling assumptions and do not support claims of general performance or causal effects.

## Reproduction

From the repository root, run `python3 scripts/analyze_task7.py`. The script reads the existing Task 6 and dataset exports, validates CSV/JSON agreement and counts, and writes the three statistical outputs plus this report under `results/task7/`. It does not write to `results/task6/` or `datasets/generated/`.
