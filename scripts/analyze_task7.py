#!/usr/bin/env python3
"""Reproduce the statistical analysis of the existing Task 6 observations."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import math
import random
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = ROOT / "results" / "task6"
DEFAULT_DATASETS = ROOT / "datasets" / "generated"
DEFAULT_OUTPUT = ROOT / "results" / "task7"
BOOTSTRAP_RESAMPLES = 10_000
ANALYSIS_SEED = 20261118
CONFIDENCE_Z = 1.959963984540054

RAW_CSV_TYPES = {
    "trial": "trials",
    "request": "requests",
    "document": "documents",
    "revocation": "revocationEvents",
    "precision": "precisionObservations",
}
LATENCY_METRICS = (
    ("freshRetrievalLatencyMs", "request", "FRESH", None),
    ("cachedRetrievalLatencyMs", "request", "CACHED", None),
    ("postRevocationRetrievalLatencyMs", "request", "AFTER_REVOCATION", None),
    ("postRevocationCacheHitLatencyMs", "request", "AFTER_REVOCATION", True),
    ("postRevocationCacheMissLatencyMs", "request", "AFTER_REVOCATION", False),
    ("revocationEventProcessingLatencyMs", "event", None, None),
    ("revocationEnforcementLatencyMs", "enforcement", None, None),
)
TASK6_LATENCY_METRICS = frozenset({
    "freshRetrievalLatencyMs",
    "cachedRetrievalLatencyMs",
    "postRevocationRetrievalLatencyMs",
    "revocationEventProcessingLatencyMs",
    "revocationEnforcementLatencyMs",
})
SIZES = (100, 1_000, 5_000, 10_000)


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def verify_raw_csv(raw: dict[str, list[dict[str, Any]]], path: Path) -> int:
    observed: dict[str, Counter[str]] = {name: Counter() for name in RAW_CSV_TYPES.values()}
    total = 0
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["observationType", "payloadJson"]:
            raise ValueError(f"Unexpected raw CSV columns: {reader.fieldnames}")
        for row in reader:
            collection = RAW_CSV_TYPES.get(row["observationType"])
            if collection is None:
                raise ValueError(f"Unknown raw CSV observation type: {row['observationType']}")
            observed[collection][canonical_json(json.loads(row["payloadJson"]))] += 1
            total += 1
    for collection, csv_records in observed.items():
        json_records = Counter(canonical_json(row) for row in raw[collection])
        if csv_records != json_records:
            raise ValueError(f"Raw CSV and JSON observations differ for {collection}")
    return total


def flatten_task6_summary(summary: dict[str, Any]) -> dict[tuple[int, str], tuple[int, float]]:
    expected: dict[tuple[int, str], tuple[int, float]] = {}
    size = summary["datasetSize"]
    for metric, sample_count, value in (
        ("unauthorizedRetrievalRate", summary["totalRetrievalRequests"], summary["unauthorizedRetrievalRate"]),
        ("unauthorizedDocumentExposureRate", summary["documentsReturned"],
         summary["unauthorizedDocumentExposureRate"]),
        ("revocationEventProcessingSuccessRate", summary["generatedRevocationEvents"],
         summary["revocationEventProcessingSuccessRate"]),
        ("meanPrecisionAtK", summary["precisionSampleCount"], summary["meanPrecisionAtK"]),
        ("meanAuthorizedRelevantPrecisionAtK", summary["precisionSampleCount"],
         summary["meanAuthorizedRelevantPrecisionAtK"]),
    ):
        expected[(size, metric)] = (sample_count, value)
    for source_name, csv_prefix in (
        ("freshRetrievalLatency", "freshRetrieval"),
        ("cachedRetrievalLatency", "cachedRetrieval"),
        ("postRevocationRetrievalLatency", "postRevocationRetrieval"),
        ("revocationEventProcessingLatency", "revocationEventProcessing"),
        ("revocationEnforcementLatency", "revocationEnforcement"),
    ):
        latency = summary[source_name]
        for statistic in ("meanMs", "medianMs", "standardDeviationMs", "p95Ms", "p99Ms"):
            expected[(size, f"{csv_prefix}.{statistic}")] = (
                latency["sampleCount"], latency[statistic])
    return expected


def verify_summary_csv(summaries: list[dict[str, Any]], path: Path) -> int:
    expected = {}
    for summary in summaries:
        expected.update(flatten_task6_summary(summary))
    observed: dict[tuple[int, str], tuple[int, float]] = {}
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["datasetSize", "metric", "sampleCount", "value"]:
            raise ValueError(f"Unexpected summary CSV columns: {reader.fieldnames}")
        for row in reader:
            key = (int(row["datasetSize"]), row["metric"])
            value = (int(row["sampleCount"]), float(row["value"]))
            observed[key] = value
    if observed.keys() != expected.keys():
        raise ValueError("Task 6 summary CSV metric keys do not match its JSON summary")
    for key, (sample_count, value) in expected.items():
        actual_count, actual_value = observed[key]
        if actual_count != sample_count or not math.isclose(actual_value, value, rel_tol=1e-10, abs_tol=1e-12):
            raise ValueError(f"Task 6 summary CSV differs from JSON at {key}")
    return len(observed)


def nearest_rank(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def descriptive(values: list[float]) -> dict[str, float | int | None]:
    if not values:
        return {
            "sampleCount": 0, "mean": None, "median": None, "sampleStandardDeviation": None,
            "minimum": None, "maximum": None, "p95": None, "p99": None,
        }
    return {
        "sampleCount": len(values),
        "mean": statistics.mean(values),
        "median": statistics.median(values),
        "sampleStandardDeviation": statistics.stdev(values) if len(values) > 1 else None,
        "minimum": min(values),
        "maximum": max(values),
        "p95": nearest_rank(values, 0.95),
        "p99": nearest_rank(values, 0.99),
    }


def interpolated_quantile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    position = probability * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    fraction = position - lower
    return ordered[lower] + fraction * (ordered[upper] - ordered[lower])


def stable_seed(dataset_size: int, metric: str) -> int:
    digest = hashlib.sha256(f"{ANALYSIS_SEED}:{dataset_size}:{metric}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big")


def cluster_bootstrap_ci(
    blocks: dict[int, list[float]], dataset_size: int, metric: str, resamples: int
) -> tuple[float | None, float | None]:
    block_means = [statistics.mean(block) for _, block in sorted(blocks.items()) if block]
    if not block_means:
        return None, None
    if len(block_means) == 1:
        return block_means[0], block_means[0]
    rng = random.Random(stable_seed(dataset_size, metric))
    count = len(block_means)
    bootstrap_means = []
    for _ in range(resamples):
        bootstrap_means.append(
            sum(block_means[rng.randrange(count)] for _ in range(count)) / count
        )
    return (
        interpolated_quantile(bootstrap_means, 0.025),
        interpolated_quantile(bootstrap_means, 0.975),
    )


def wilson_interval(successes: int, total: int) -> tuple[float | None, float | None]:
    if total == 0:
        return None, None
    if successes == 0:
        z_squared = CONFIDENCE_Z * CONFIDENCE_Z
        upper = z_squared / (total + z_squared)
        return 0.0, upper
    if successes == total:
        z_squared = CONFIDENCE_Z * CONFIDENCE_Z
        lower = total / (total + z_squared)
        return lower, 1.0
    proportion = successes / total
    z_squared = CONFIDENCE_Z * CONFIDENCE_Z
    denominator = 1.0 + z_squared / total
    center = (proportion + z_squared / (2 * total)) / denominator
    margin = (CONFIDENCE_Z / denominator) * math.sqrt(
        proportion * (1 - proportion) / total + z_squared / (4 * total * total)
    )
    return max(0.0, center - margin), min(1.0, center + margin)


def metric_row(
    dataset_size: int,
    metric: str,
    unit: str,
    values: list[float],
    blocks: dict[int, list[float]] | None = None,
    numerator: int | None = None,
    denominator: int | None = None,
    confidence_method: str | None = None,
    resamples: int = BOOTSTRAP_RESAMPLES,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "datasetSize": dataset_size,
        "metric": metric,
        "unit": unit,
        **descriptive(values),
        "numerator": numerator,
        "denominator": denominator,
        "rate": numerator / denominator if numerator is not None and denominator else None,
        "ci95Low": None,
        "ci95High": None,
        "confidenceMethod": confidence_method,
        "confidenceClusterCount": len(blocks) if blocks is not None else None,
    }
    if blocks is not None and confidence_method == "repetitionClusterBootstrapPercentile":
        row["ci95Low"], row["ci95High"] = cluster_bootstrap_ci(
            blocks, dataset_size, metric, resamples)
    elif numerator is not None and denominator is not None and confidence_method == "wilsonBinomial":
        row["ci95Low"], row["ci95High"] = wilson_interval(numerator, denominator)
    return row


def check_close(actual: float | None, expected: float | None, label: str) -> None:
    if actual is None or expected is None:
        if actual is not expected:
            raise ValueError(f"Missing-value mismatch while validating {label}")
    elif not math.isclose(actual, expected, rel_tol=1e-8, abs_tol=1e-10):
        raise ValueError(f"Raw recomputation differs from Task 6 aggregate for {label}: {actual} vs {expected}")


def validate_dataset_file(path: Path, size: int, seed: int) -> dict[str, Any]:
    data = load_json(path)
    metadata = data["metadata"]
    documents = data["documents"]
    if len(documents) != size or metadata["documentCount"] != size or metadata["seed"] != seed:
        raise ValueError(f"Dataset size or seed mismatch: {path}")
    document_ids = [document["documentId"] for document in documents]
    if len(set(document_ids)) != size:
        raise ValueError(f"Duplicate document IDs in {path}")
    topic_by_id = {
        document["documentId"]: document["customAttributes"]["datasetTopicId"]
        for document in documents
    }
    queries = []
    for query in data["queries"]:
        relevant = set(data["relevanceGroundTruth"][query["queryId"]])
        expected = {doc_id for doc_id, topic in topic_by_id.items() if topic == query["topicId"]}
        if relevant != expected:
            raise ValueError(f"Ground truth does not match topic rule for {query['queryId']} in {path}")
        queries.append({
            "queryId": query["queryId"],
            "queryText": query["queryText"],
            "topicId": query["topicId"],
            "relevantDocumentCount": len(relevant),
        })
    if "permissions are not consulted" not in metadata["relevanceRule"]:
        raise ValueError(f"Dataset does not declare permission-independent relevance: {path}")
    return {
        "datasetId": metadata["datasetId"],
        "documentCount": size,
        "uniqueDocumentCount": len(set(document_ids)),
        "seed": seed,
        "relevanceRule": metadata["relevanceRule"],
        "queries": queries,
    }


def validate_event_timestamps(events: list[dict[str, Any]]) -> None:
    def timestamp_ns(value: str) -> int:
        normalized = value.removesuffix("Z")
        base, separator, fraction = normalized.partition(".")
        instant = dt.datetime.strptime(base, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=dt.timezone.utc)
        seconds = int(instant.timestamp())
        nanos = int((fraction + "000000000")[:9]) if separator else 0
        return seconds * 1_000_000_000 + nanos

    for event in events:
        generated = timestamp_ns(event["generatedAt"])
        committed = timestamp_ns(event["permissionCommittedAt"])
        processed = timestamp_ns(event["processedAt"])
        if not generated <= committed <= processed:
            raise ValueError(f"Invalid event timestamp order for {event['eventId']}")
        first_denied = event["firstDeniedAt"]
        enforcement = event["revocationEnforcementLatencyMs"]
        if first_denied is None:
            if enforcement is not None:
                raise ValueError(f"Enforcement latency has no denial timestamp for {event['eventId']}")
            continue
        denied = timestamp_ns(first_denied)
        if denied < committed or enforcement is None:
            raise ValueError(f"Invalid enforcement timestamps for {event['eventId']}")
        measured_ms = (denied - committed) / 1_000_000
        if not math.isclose(measured_ms, enforcement, rel_tol=1e-10, abs_tol=1e-9):
            raise ValueError(f"Enforcement latency differs from timestamps for {event['eventId']}")


def build_analysis(
    input_dir: Path,
    dataset_dir: Path,
    bootstrap_resamples: int,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    raw = load_json(input_dir / "repeated_experiment_raw.json")
    task6_summaries = load_json(input_dir / "repeated_experiment_summary.json")
    metadata = load_json(input_dir / "reproducibility_metadata.json")
    raw_csv_count = verify_raw_csv(raw, input_dir / "repeated_experiment_raw.csv")
    summary_csv_count = verify_summary_csv(task6_summaries, input_dir / "repeated_experiment_summary.csv")

    sizes = metadata["datasetSizes"]
    repetitions = metadata["repetitions"]
    warmup_runs = metadata["warmupRuns"]
    seed = metadata["seed"]
    expected_experiments = set(metadata["experiments"])
    if sizes != list(SIZES):
        raise ValueError(f"Unexpected dataset sizes in reproducibility metadata: {sizes}")
    if repetitions != 30 or warmup_runs != 1:
        raise ValueError(f"Unexpected Task 6 repetition configuration: {repetitions}, {warmup_runs}")

    trials = raw["trials"]
    requests = raw["requests"]
    documents = raw["documents"]
    events = raw["revocationEvents"]
    precision = raw["precisionObservations"]
    measured_trials = [row for row in trials if not row["warmup"]]
    warmup_trials = [row for row in trials if row["warmup"]]
    if len(measured_trials) != len(sizes) * repetitions * len(expected_experiments):
        raise ValueError("Measured scenario count does not match the Task 6 design")
    if len(warmup_trials) != len(sizes) * warmup_runs * len(expected_experiments):
        raise ValueError("Warm-up scenario count does not match the Task 6 design")
    for size in sizes:
        dataset_trials = [row for row in measured_trials if row["datasetSize"] == size]
        if {row["experiment"] for row in dataset_trials} != expected_experiments:
            raise ValueError(f"Missing scenario for dataset size {size}")
        if {row["repetition"] for row in dataset_trials} != set(range(1, repetitions + 1)):
            raise ValueError(f"Missing measured repetition for dataset size {size}")
        if any(not row["passed"] for row in dataset_trials):
            raise ValueError(f"Task 6 has failed measured trials for dataset size {size}")

    dataset_descriptions = {}
    for size in sizes:
        dataset_descriptions[size] = validate_dataset_file(
            dataset_dir / f"synthetic-rag-v1-{size}-{seed}.json", size, seed)

    measured_requests = [row for row in requests if not row["warmup"]]
    measured_documents = [row for row in documents if not row["warmup"]]
    measured_events = [row for row in events if not row["warmup"]]
    measured_precision = [row for row in precision if not row["warmup"]]
    validate_event_timestamps(measured_events)
    if len(measured_requests) != len(sizes) * repetitions * 10:
        raise ValueError("Measured request count does not match the Task 6 scenarios")
    if len(measured_precision) != len(sizes) * repetitions:
        raise ValueError("Measured Precision@K count does not match the Task 6 design")
    if len(measured_documents) != sum(row["documentsReturned"] for row in measured_requests):
        raise ValueError("Document-level observations do not match retrieval responses")

    output_rows: list[dict[str, Any]] = []
    scalability_rows: list[dict[str, Any]] = []
    dataset_results: list[dict[str, Any]] = []
    latency_means: dict[tuple[int, str], float] = {}

    for size in sizes:
        ds_requests = [row for row in measured_requests if row["datasetSize"] == size]
        ds_documents = [row for row in measured_documents if row["datasetSize"] == size]
        ds_events = [row for row in measured_events if row["datasetSize"] == size]
        ds_precision = [row for row in measured_precision if row["datasetSize"] == size]
        ds_trials = [row for row in measured_trials if row["datasetSize"] == size]
        summary = next(row for row in task6_summaries if row["datasetSize"] == size)
        if len(ds_requests) != summary["totalRetrievalRequests"]:
            raise ValueError(f"Retrieval request count does not match Task 6 aggregate for {size}")

        dataset_metric_rows = []
        latency_blocks: dict[str, dict[int, list[float]]] = {}
        for metric, source, phase, cache_hit in LATENCY_METRICS:
            if source == "request":
                selected = [row for row in ds_requests if row["phase"] == phase and
                            (cache_hit is None or row["cacheHit"] is cache_hit)]
                values = [float(row["latencyMs"]) for row in selected]
            elif source == "event":
                selected = ds_events
                values = [float(row["eventProcessingLatencyMs"]) for row in selected]
            else:
                selected = [row for row in ds_events if row["revocationEnforcementLatencyMs"] is not None]
                values = [float(row["revocationEnforcementLatencyMs"]) for row in selected]
            blocks: dict[int, list[float]] = defaultdict(list)
            for row, value in zip(selected, values):
                blocks[row["repetition"]].append(value)
            if set(blocks) != set(range(1, repetitions + 1)):
                raise ValueError(f"Latency metric {metric} does not cover every measured repetition")
            row = metric_row(
                size, metric, "ms", values, dict(blocks),
                confidence_method="repetitionClusterBootstrapPercentile",
                resamples=bootstrap_resamples,
            )
            task6_metric = {
                "freshRetrievalLatencyMs": "freshRetrievalLatency",
                "cachedRetrievalLatencyMs": "cachedRetrievalLatency",
                "postRevocationRetrievalLatencyMs": "postRevocationRetrievalLatency",
                "revocationEventProcessingLatencyMs": "revocationEventProcessingLatency",
                "revocationEnforcementLatencyMs": "revocationEnforcementLatency",
            }.get(metric)
            if metric in TASK6_LATENCY_METRICS:
                aggregate = summary[task6_metric]
                check_close(row["mean"], aggregate["meanMs"], f"{size}/{metric}/mean")
                check_close(row["median"], aggregate["medianMs"], f"{size}/{metric}/median")
                check_close(row["p95"], aggregate["p95Ms"], f"{size}/{metric}/p95")
                check_close(row["p99"], aggregate["p99Ms"], f"{size}/{metric}/p99")
                check_close(
                    statistics.pstdev(values) if len(values) > 1 else None,
                    aggregate["standardDeviationMs"],
                    f"{size}/{metric}/populationStandardDeviation",
                )
            dataset_metric_rows.append(row)
            output_rows.append(row)
            latency_blocks[metric] = dict(blocks)
            latency_means[(size, metric)] = row["mean"]

        exposed_requests = sum(row["unauthorizedDocuments"] > 0 for row in ds_requests)
        unauthorized_documents = sum(not row["authorized"] for row in ds_documents)
        if unauthorized_documents != sum(row["unauthorizedDocuments"] for row in ds_requests):
            raise ValueError(f"Request and document unauthorized counts disagree for {size}")
        request_blocks: dict[int, list[float]] = defaultdict(list)
        grouped_request: dict[int, list[dict[str, Any]]] = defaultdict(list)
        for row in ds_requests:
            grouped_request[row["repetition"]].append(row)
        request_block_any_exposure = 0
        for repetition, rows in grouped_request.items():
            rate = sum(row["unauthorizedDocuments"] > 0 for row in rows) / len(rows)
            request_blocks[repetition].append(rate)
            request_block_any_exposure += any(row["unauthorizedDocuments"] > 0 for row in rows)
        request_row = metric_row(
            size, "unauthorizedRetrievalRate", "proportion",
            [float(row["unauthorizedDocuments"] > 0) for row in ds_requests],
            numerator=exposed_requests, denominator=len(ds_requests), confidence_method="wilsonBinomial",
        )
        request_row["clusterCount"] = len(request_blocks)
        request_row["clustersWithAnyExposure"] = request_block_any_exposure
        request_row["clusterAnyExposureCi95Low"], request_row["clusterAnyExposureCi95High"] = \
            wilson_interval(request_block_any_exposure, len(request_blocks))
        dataset_metric_rows.append(request_row)
        output_rows.append(request_row)

        returned_documents = len(ds_documents)
        document_row = metric_row(
            size, "unauthorizedDocumentExposureRate", "proportion",
            [float(not row["authorized"]) for row in ds_documents],
            numerator=unauthorized_documents, denominator=returned_documents,
            confidence_method="wilsonBinomial",
        )
        documents_by_repetition: dict[int, list[dict[str, Any]]] = defaultdict(list)
        for row in ds_documents:
            documents_by_repetition[row["repetition"]].append(row)
        document_blocks_with_exposure = sum(
            any(not row["authorized"] for row in rows)
            for rows in documents_by_repetition.values()
        )
        document_row["clusterCount"] = len(documents_by_repetition)
        document_row["clustersWithAnyExposure"] = document_blocks_with_exposure
        document_row["clusterAnyExposureCi95Low"], document_row["clusterAnyExposureCi95High"] = \
            wilson_interval(document_blocks_with_exposure, len(documents_by_repetition))
        dataset_metric_rows.append(document_row)
        output_rows.append(document_row)

        processed = sum(row["outcome"] == "PROCESSED" for row in ds_events)
        duplicates = sum(row["outcome"] == "DUPLICATE" for row in ds_events)
        failed = sum(row["outcome"] == "FAILED" for row in ds_events)
        if processed + duplicates + failed != len(ds_events):
            raise ValueError(f"Unclassified revocation events for dataset size {size}")
        event_categories = (
            ("processedRevocationEventRate", processed),
            ("duplicateRevocationEventRate", duplicates),
            ("failedRevocationEventRate", failed),
            ("revocationEventHandlingSuccessRate", processed + duplicates),
        )
        failure_blocks = defaultdict(list)
        for repetition in range(1, repetitions + 1):
            rep_events = [row for row in ds_events if row["repetition"] == repetition]
            if not rep_events:
                raise ValueError(f"No revocation event observations for repetition {repetition}")
            failure_blocks[repetition].append(any(row["outcome"] == "FAILED" for row in rep_events))
        failed_blocks = sum(values[0] for values in failure_blocks.values())
        all_events_handled_blocks = sum(
            all(event["outcome"] in ("PROCESSED", "DUPLICATE")
                for event in ds_events if event["repetition"] == repetition)
            for repetition in range(1, repetitions + 1)
        )
        event_metric_rows = []
        for metric, successes in event_categories:
            row = metric_row(
                size, metric, "proportion",
                [float(
                    (event["outcome"] == "PROCESSED" if metric == "processedRevocationEventRate" else
                     event["outcome"] == "DUPLICATE" if metric == "duplicateRevocationEventRate" else
                     event["outcome"] == "FAILED" if metric == "failedRevocationEventRate" else
                     event["outcome"] in ("PROCESSED", "DUPLICATE"))
                ) for event in ds_events],
                numerator=successes, denominator=len(ds_events), confidence_method="wilsonBinomial",
            )
            row["failedRepetitions"] = failed_blocks
            row["repetitionCount"] = len(failure_blocks)
            row["clusterAnyFailureCi95Low"], row["clusterAnyFailureCi95High"] = \
                wilson_interval(failed_blocks, len(failure_blocks))
            if metric == "revocationEventHandlingSuccessRate":
                row["clustersWithAllEventsHandled"] = all_events_handled_blocks
                row["clusterAllEventsHandledCi95Low"], row["clusterAllEventsHandledCi95High"] = \
                    wilson_interval(all_events_handled_blocks, len(failure_blocks))
            event_metric_rows.append(row)
            dataset_metric_rows.append(row)
            output_rows.append(row)

        precision_metric_rows = []
        for metric, value_field in (
            ("precisionAt5", "precisionAtK"),
            ("authorizedRelevantPrecisionAt5", "authorizedRelevantPrecisionAtK"),
        ):
            blocks: dict[int, list[float]] = defaultdict(list)
            for observation in ds_precision:
                if observation["k"] != 5:
                    raise ValueError("Task 6 precision observations must use K=5")
                blocks[observation["repetition"]].append(float(observation[value_field]))
            values = [value for block in blocks.values() for value in block]
            row = metric_row(
                size, metric, "proportion", values, dict(blocks),
                confidence_method="repetitionClusterBootstrapPercentile",
                resamples=bootstrap_resamples,
            )
            precision_metric_rows.append(row)
            dataset_metric_rows.append(row)
            output_rows.append(row)

        observed_precision_query_ids = sorted({row["queryId"] for row in ds_precision})
        if observed_precision_query_ids != ["QUERY-ENGINEERING"]:
            raise ValueError(f"Unexpected precision query set for dataset size {size}")
        if any(row["relevantRetrieved"] != 5 or row["authorizedRelevantRetrieved"] != 5
               for row in ds_precision):
            raise ValueError(f"Expected Task 6 precision observations are inconsistent for dataset {size}")

        ground_truth = dataset_descriptions[size]
        dataset_results.append({
            "datasetSize": size,
            "datasetId": ground_truth["datasetId"],
            "counts": {
                "measuredTrials": len(ds_trials),
                "repetitions": repetitions,
                "retrievalRequests": len(ds_requests),
                "returnedDocumentObservations": returned_documents,
                "revocationEvents": len(ds_events),
                "precisionObservations": len(ds_precision),
            },
            "security": {
                "requestsWithUnauthorizedResults": exposed_requests,
                "unauthorizedRetrievalRate": request_row,
                "unauthorizedDocumentsReturned": unauthorized_documents,
                "unauthorizedDocumentExposureRate": document_row,
                "revocationEventsGenerated": len(ds_events),
                "revocationEventsProcessed": processed,
                "duplicateRevocationEventsRecognized": duplicates,
                "revocationEventsFailed": failed,
                "eventMetrics": event_metric_rows,
            },
            "retrievalQuality": {
                "queryIdsEvaluated": observed_precision_query_ids,
                "precisionObservations": len(ds_precision),
                "metrics": precision_metric_rows,
            },
            "latencyMetrics": [row for row in dataset_metric_rows if row["unit"] == "ms"],
            "groundTruth": ground_truth,
        })

    baseline_size = min(sizes)
    for row in output_rows:
        if row["unit"] != "ms":
            continue
        metric = row["metric"]
        baseline_mean = latency_means[(baseline_size, metric)]
        scalability_rows.append({
            "datasetSize": row["datasetSize"],
            "metric": metric,
            "sampleCount": row["sampleCount"],
            "meanMs": row["mean"],
            "medianMs": row["median"],
            "sampleStandardDeviationMs": row["sampleStandardDeviation"],
            "minimumMs": row["minimum"],
            "maximumMs": row["maximum"],
            "p95Ms": row["p95"],
            "p99Ms": row["p99"],
            "ci95MeanLowMs": row["ci95Low"],
            "ci95MeanHighMs": row["ci95High"],
            "meanRatioTo100Documents": row["mean"] / baseline_mean if baseline_mean else None,
        })
    scalability_rows.sort(key=lambda row: (row["datasetSize"], row["metric"]))

    validation = {
        "datasetSizesValidated": sizes,
        "rawObservationCounts": {key: len(rows) for key, rows in raw.items()},
        "rawCsvRowsMatchedJson": raw_csv_count,
        "task6SummaryCsvMetricsMatchedJson": summary_csv_count,
        "measuredTrials": len(measured_trials),
        "warmupTrialsExcluded": len(warmup_trials),
        "measuredRequests": len(measured_requests),
        "measuredDocumentObservations": len(measured_documents),
        "measuredRevocationEvents": len(measured_events),
        "measuredPrecisionObservations": len(measured_precision),
        "allMeasuredTrialsPassed": True,
    }
    result = {
        "analysisVersion": "task7-v1",
        "sourceFiles": [
            "results/task6/repeated_experiment_raw.json",
            "results/task6/repeated_experiment_raw.csv",
            "results/task6/repeated_experiment_summary.json",
            "results/task6/repeated_experiment_summary.csv",
            "results/task6/reproducibility_metadata.json",
            "datasets/generated/synthetic-rag-v1-{size}-{seed}.json",
        ],
        "reproducibility": {
            "task6Seed": seed,
            "repetitionsPerDataset": repetitions,
            "warmupRunsPerDataset": warmup_runs,
            "task6ConfigurationId": metadata["configurationId"],
            "analysisBootstrapSeed": ANALYSIS_SEED,
            "bootstrapResamples": bootstrap_resamples,
        },
        "methods": {
            "warmups": "Task 6 observations marked warmup=true are excluded from all measured statistics.",
            "descriptiveStatistics": (
                "Mean, median, sample standard deviation (n-1), minimum, maximum, and nearest-rank P95/P99 "
                "are computed over individual measured observations."
            ),
            "latencyAndPrecisionConfidenceIntervals": (
                "95% percentile bootstrap intervals for means, resampling 30 repetition-level block means "
                "with replacement; 10,000 resamples by default and deterministic metric-specific seeds."
            ),
            "proportionConfidenceIntervals": (
                "95% Wilson score intervals over the request, returned-document, or revocation-event units. "
                "These nominal intervals assume independent Bernoulli units; repeated units within a repetition "
                "may be correlated. Cluster-level any-exposure/any-failure and all-events-handled Wilson intervals "
                "are included as a separate conservative view."
            ),
            "p95p99": "Nearest rank: sorted[ceil(p*n)-1].",
            "significance": "No hypothesis tests or statistical significance claims are made.",
        },
        "validation": validation,
        "datasets": dataset_results,
        "statisticalRows": output_rows,
        "scalabilityRows": scalability_rows,
    }
    return result, output_rows, scalability_rows


def format_number(value: float | None, digits: int = 4) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def format_ci(row: dict[str, Any], digits: int = 4) -> str:
    return f"{format_number(row['mean'], digits)} [{format_number(row['ci95Low'], digits)}, {format_number(row['ci95High'], digits)}]"


def generate_report(result: dict[str, Any]) -> str:
    validation = result["validation"]
    datasets = result["datasets"]
    output = [
        "# Task 7 — Statistical Analysis and Research Interpretation",
        "",
        "## Scope and evidence",
        "",
        "This analysis uses only the existing Task 6 observations, summaries, reproducibility metadata, "
        "and the four exported synthetic datasets. It does not rerun experiments or alter Task 6 data. "
        "All empirical claims below refer to this simulated in-memory lexical prototype.",
        "",
        "Primary evidence: [Task 6 raw JSON](../task6/repeated_experiment_raw.json), "
        "[Task 6 raw CSV](../task6/repeated_experiment_raw.csv), "
        "[Task 6 aggregates](../task6/repeated_experiment_summary.json), "
        "[reproducibility metadata](../task6/reproducibility_metadata.json), and "
        "[seeded dataset exports](../../datasets/generated/).",
        "",
        "## Data and validation",
        "",
        f"- Seed: `{result['reproducibility']['task6Seed']}`; 30 measured repetitions and one warm-up per dataset.",
        f"- Datasets: {', '.join(str(size) for size in validation['datasetSizesValidated'])} documents.",
        f"- Raw observations: {validation['rawObservationCounts']['trials']} scenario rows "
        f"({validation['measuredTrials']} measured, {validation['warmupTrialsExcluded']} warm-up), "
        f"{validation['rawObservationCounts']['requests']} requests, "
        f"{validation['rawObservationCounts']['documents']} document observations, "
        f"{validation['rawObservationCounts']['revocationEvents']} event observations, and "
        f"{validation['rawObservationCounts']['precisionObservations']} precision observations.",
        f"- Raw CSV payloads matched the JSON observations ({validation['rawCsvRowsMatchedJson']} rows); "
        f"the Task 6 summary CSV matched its JSON aggregate ({validation['task6SummaryCsvMetricsMatchedJson']} metrics).",
        "- All 840 measured scenarios passed; warm-ups are excluded below.",
        "",
        "## Methods",
        "",
        "Descriptive statistics are computed over individual measured observations: arithmetic mean, median, "
        "sample standard deviation (denominator n−1), minimum, maximum, and nearest-rank P95/P99 "
        "(`sorted[ceil(p·n)−1]`). Timing confidence intervals are 95% percentile bootstrap intervals for "
        "the mean, resampling the 30 repetition-level block means with replacement (10,000 deterministic "
        "resamples). This treats repetitions—not the nested requests/documents/events—as the resampling unit.",
        "",
        "Binary rates use 95% Wilson score intervals at the request, returned-document, or event unit. "
        "Those nominal intervals assume independent Bernoulli observations; repeated observations within a "
        "repetition may be correlated. Therefore, the report also records repetition-level counts of any "
        "unauthorized exposure or any failed event with Wilson intervals over the 30 repetition blocks. "
        "The confidence intervals quantify sampling uncertainty under these assumptions; they do not establish "
        "causality or statistical significance. Precision confidence intervals resample repetition blocks.",
        "",
        "## Security outcomes",
        "",
        "| Documents | Requests exposed / requests | Request exposure rate (95% Wilson CI) | Unauthorized documents / returned | Document exposure rate (95% Wilson CI) | Processed | Duplicate | Failed | Event handling success |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for dataset in datasets:
        security = dataset["security"]
        request = security["unauthorizedRetrievalRate"]
        document = security["unauthorizedDocumentExposureRate"]
        handled = next(row for row in security["eventMetrics"]
                       if row["metric"] == "revocationEventHandlingSuccessRate")
        event_ci = f"{handled['ci95Low']:.1%}–{handled['ci95High']:.1%}"
        block_ci = (
            f"{handled['clusterAllEventsHandledCi95Low']:.1%}–"
            f"{handled['clusterAllEventsHandledCi95High']:.1%}"
        )
        output.append(
            f"| {dataset['datasetSize']} | {request['numerator']} / {request['denominator']} | "
            f"{request['rate']:.4%} [{request['ci95Low']:.4%}, {request['ci95High']:.4%}] | "
            f"{document['numerator']} / {document['denominator']} | "
            f"{document['rate']:.4%} [{document['ci95Low']:.4%}, {document['ci95High']:.4%}] | "
            f"{security['revocationEventsProcessed']} | {security['duplicateRevocationEventsRecognized']} | "
            f"{security['revocationEventsFailed']} | "
            f"{handled['numerator']}/{handled['denominator']} ({handled['rate']:.1%}; event CI {event_ci}; "
            f"block {handled['clustersWithAllEventsHandled']}/{handled['repetitionCount']} "
            f"CI {block_ci}) |"
        )
    output.extend([
        "",
        "Across the measured data there were 1,200 retrieval requests and zero unauthorized results; "
        "the returned-document denominator is the number of returned document observations, not the corpus "
        "size. Per dataset, 180 events were newly processed and 30 were recognized as duplicates. The "
        "Task 6 success convention counts either processed or recognized duplicate handling as successful, "
        "so it yields 210/210 per dataset; it must not be interpreted as 210 new permission-state changes. "
        "No unauthorized retrieval or event failure was observed. In every dataset no repetition block had "
        "an exposure or failure (0/30; block-level Wilson upper bound 11.35%); all events were handled in "
        "30/30 blocks (block-level Wilson CI 88.65%–100%). These finite-run intervals do not establish a "
        "zero population risk.",
        "",
        "## Latency and scalability",
        "",
        "Means below are in milliseconds; brackets contain 95% repetition-cluster bootstrap confidence "
        "intervals for the mean. P95/P99, medians, sample standard deviations, minima, and maxima for every "
        "metric are in `statistical_summary.csv` and `scalability_comparison.csv`.",
        "",
        "| Documents | Fresh retrieval | Cached retrieval | After revocation | Event processing | Revocation enforcement |",
        "| ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    latency_by_dataset = {
        dataset["datasetSize"]: {row["metric"]: row for row in dataset["latencyMetrics"]}
        for dataset in datasets
    }
    metric_order = [metric for metric, _, _, _ in LATENCY_METRICS
                    if metric in TASK6_LATENCY_METRICS]
    for size in SIZES:
        rows = latency_by_dataset[size]
        output.append("| " + str(size) + " | " +
                      " | ".join(format_ci(rows[metric], 4) for metric in metric_order) + " |")
    output.extend([
        "",
        "The post-revocation phase combines different cache paths: one request per repetition follows "
        "explicit cache invalidation (cache miss), while two use a populated cache and revalidate it (cache hit). "
        "The split below is computed directly from each request's `cacheHit` field.",
        "",
        "| Documents | Cache-miss requests (n, mean [95% CI]) | Cache-hit revalidation requests (n, mean [95% CI]) |",
        "| ---: | ---: | ---: |",
    ])
    for size in SIZES:
        rows = latency_by_dataset[size]
        miss = rows["postRevocationCacheMissLatencyMs"]
        hit = rows["postRevocationCacheHitLatencyMs"]
        output.append(
            f"| {size} | {miss['sampleCount']}, {format_ci(miss, 4)} | "
            f"{hit['sampleCount']}, {format_ci(hit, 4)} |"
        )
    first = latency_by_dataset[SIZES[0]]
    last = latency_by_dataset[SIZES[-1]]
    output.extend([
        "",
        "Observed mean fresh and post-revocation latency increased with corpus size in this run. "
        f"The 10,000-document/100-document mean ratios were "
        f"{last['freshRetrievalLatencyMs']['mean'] / first['freshRetrievalLatencyMs']['mean']:.2f}× "
        "for fresh retrieval and "
        f"{last['postRevocationRetrievalLatencyMs']['mean'] / first['postRevocationRetrievalLatencyMs']['mean']:.2f}× "
        "after revocation. Cached means were lower in this run but did not increase monotonically; event "
        "processing means also did not increase with corpus size. The post-revocation cache-miss means "
        "increased with corpus size, while cache-hit revalidation means did not; the combined post-revocation "
        "trend is therefore not a pure estimate of cache revalidation cost. These are descriptive patterns, "
        "not significance claims. Revocation enforcement latency includes the time to the first denied response, "
        "so it includes retrieval work after the permission commit; it is distinct from the direct event "
        "processing duration.",
        "",
        "The observed zero exposure is consistent with the prototype revalidating authorization before "
        "returning cached documents, while the longer post-revocation and enforcement timings are consistent "
        "with the retrieval path doing additional work. The experiment is not a controlled decomposition of "
        "that cost, so this explanation is a mechanism-based interpretation rather than a causal estimate.",
        "",
        "## Retrieval quality and label difficulty",
        "",
        "All 120 measured Precision@5 observations and all 120 authorized-relevant Precision@5 observations "
        "equal 1.0. Each dataset contributes 30 precision observations, all for `QUERY-ENGINEERING`; every "
        "observation has five relevant documents and five authorized-relevant documents among the top five. "
        "The repetition-bootstrap intervals are consequently degenerate at 1.0 and should not be read as "
        "evidence of universal perfect retrieval.",
        "",
        "The dataset generator defines relevance by exact topic-ID equality, independently of permissions; "
        "each document's content starts with that topic's fixed query phrase. The retrieval scorer awards "
        "points for query-token substring matches in title/content, and nonmatching topic documents generally "
        "receive zero. Thus, the query is an exact lexical match, there are many relevant documents relative "
        "to K=5, and there are few hard negatives. The runner evaluates only the engineering query for "
        "Precision@5, despite six queries and ground-truth sets being present. This creates a narrow and "
        "likely easy benchmark; these are design-based explanations, not evidence that the scores were "
        "manipulated. Relevance labels were not changed.",
        "",
        "| Documents | Query | Query text | Relevant labels |",
        "| ---: | --- | --- | ---: |",
    ])
    for dataset in datasets:
        for query in dataset["groundTruth"]["queries"]:
            output.append(
                f"| {dataset['datasetSize']} | `{query['queryId']}` | `{query['queryText']}` | "
                f"{query['relevantDocumentCount']} |")
    output.extend([
        "",
        "Primary implementation evidence: the topic-to-label rule is in "
        "[`SyntheticDatasetGenerator`](../../src/main/java/edu/academic/rag/accesscontrol/dataset/SyntheticDatasetGenerator.java); "
        "the Precision@K denominator and label lookup are in "
        "[`SyntheticDataset.evaluatePrecisionAtK`](../../src/main/java/edu/academic/rag/accesscontrol/dataset/SyntheticDataset.java); "
        "the selected query and top-K are recorded in "
        "[`RepeatedExperimentRunner`](../../src/main/java/edu/academic/rag/accesscontrol/experiment/RepeatedExperimentRunner.java); "
        "and lexical token scoring is implemented in "
        "[`RetrievalService`](../../src/main/java/edu/academic/rag/accesscontrol/service/RetrievalService.java).",
        "",
        "## Interpretation and limitations",
        "",
        "- **Measured evidence:** the repeated run reported no unauthorized retrieval/document exposure; all tested revocation/cache scenarios passed; mean fresh and post-revocation timings rose with corpus size; cached timing remained lower but was non-monotonic; event processing showed no observed corpus-size increase.",
        "- **Security/latency trade-off:** dynamic revalidation coincided with continued denial of revoked content. Post-revocation/enforcement latencies were higher than cached-before-revocation means in the observed run, but no causal overhead estimate is isolated.",
        "- **Synthetic scope:** generated documents have fixed topic phrases, deterministic metadata, and a small set of users/queries. This does not model natural-language ambiguity, realistic corpus distributions, concurrency, network/storage effects, or adversarial document content.",
        "- **Retrieval scope:** the implementation performs in-memory lexical ranking, not vector search or an LLM-backed RAG pipeline. These timings do not measure prompt construction, model behavior, or prompt leakage.",
        "- **Statistical scope:** repetitions are only 30 per dataset and share fixed data/configuration; wall-clock timing is sensitive to local JVM/host noise. Intervals describe this run under the stated resampling assumptions and do not support claims of general performance or causal effects.",
        "",
        "## Reproduction",
        "",
        "From the repository root, run `python3 scripts/analyze_task7.py`. The script reads the existing Task 6 "
        "and dataset exports, validates CSV/JSON agreement and counts, and writes the three statistical outputs "
        "plus this report under `results/task7/`. It does not write to `results/task6/` or `datasets/generated/`.",
        "",
    ])
    return "\n".join(output)


def write_csv(path: Path, rows: list[dict[str, Any]], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASETS)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--bootstrap-resamples", type=int, default=BOOTSTRAP_RESAMPLES)
    args = parser.parse_args()
    if args.bootstrap_resamples < 1:
        parser.error("--bootstrap-resamples must be positive")

    result, summary_rows, scalability_rows = build_analysis(
        args.input_dir, args.dataset_dir, args.bootstrap_resamples)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "statistical_summary.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    summary_fields = [
        "datasetSize", "metric", "unit", "sampleCount", "mean", "median", "sampleStandardDeviation",
        "minimum", "maximum", "p95", "p99", "numerator", "denominator", "rate", "ci95Low", "ci95High",
        "confidenceMethod", "confidenceClusterCount", "clusterCount", "clustersWithAnyExposure",
        "clusterAnyExposureCi95Low", "clusterAnyExposureCi95High", "failedRepetitions", "repetitionCount",
        "clusterAnyFailureCi95Low", "clusterAnyFailureCi95High", "clustersWithAllEventsHandled",
        "clusterAllEventsHandledCi95Low", "clusterAllEventsHandledCi95High",
    ]
    write_csv(args.output_dir / "statistical_summary.csv", summary_rows, summary_fields)
    scalability_fields = [
        "datasetSize", "metric", "sampleCount", "meanMs", "medianMs", "sampleStandardDeviationMs",
        "minimumMs", "maximumMs", "p95Ms", "p99Ms", "ci95MeanLowMs", "ci95MeanHighMs",
        "meanRatioTo100Documents",
    ]
    write_csv(args.output_dir / "scalability_comparison.csv", scalability_rows, scalability_fields)
    (args.output_dir / "research_analysis.md").write_text(generate_report(result), encoding="utf-8")
    print(
        f"Analyzed {result['validation']['measuredTrials']} measured scenarios across "
        f"{len(result['validation']['datasetSizesValidated'])} datasets; wrote outputs to {args.output_dir}."
    )


if __name__ == "__main__":
    main()
