#!/usr/bin/env python3
"""Generate README figures from the existing Task 6 and Task 7 exports."""

from __future__ import annotations

import json
import math
import statistics
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


ROOT = Path(__file__).resolve().parents[1]
TASK6_DIR = ROOT / "results" / "task6"
TASK7_DIR = ROOT / "results" / "task7"
OUTPUT_DIR = ROOT / "assets" / "graphs"
SIZES = (100, 1_000, 5_000, 10_000)
SIZE_LABELS = ("100", "1,000", "5,000", "10,000")
PALETTE = ("#0072B2", "#E69F00", "#009E73", "#CC79A7", "#D55E00")
LATENCY_TASK6_KEYS = {
    "freshRetrievalLatencyMs": "freshRetrievalLatency",
    "cachedRetrievalLatencyMs": "cachedRetrievalLatency",
    "postRevocationRetrievalLatencyMs": "postRevocationRetrievalLatency",
    "revocationEventProcessingLatencyMs": "revocationEventProcessingLatency",
    "revocationEnforcementLatencyMs": "revocationEnforcementLatency",
}


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def confidence(statistics_by_key: dict[tuple[int, str], dict[str, Any]],
               size: int, metric: str) -> tuple[float, float]:
    row = statistics_by_key[(size, metric)]
    return row["ci95Low"], row["ci95High"]


def raw_latency_samples(raw: dict[str, Any], dataset_size: int, metric: str) -> list[float]:
    if metric in (
        "freshRetrievalLatencyMs",
        "cachedRetrievalLatencyMs",
        "postRevocationRetrievalLatencyMs",
        "postRevocationCacheHitLatencyMs",
        "postRevocationCacheMissLatencyMs",
    ):
        phase = {
            "freshRetrievalLatencyMs": "FRESH",
            "cachedRetrievalLatencyMs": "CACHED",
            "postRevocationRetrievalLatencyMs": "AFTER_REVOCATION",
            "postRevocationCacheHitLatencyMs": "AFTER_REVOCATION",
            "postRevocationCacheMissLatencyMs": "AFTER_REVOCATION",
        }[metric]
        cache_hit = {
            "postRevocationCacheHitLatencyMs": True,
            "postRevocationCacheMissLatencyMs": False,
        }.get(metric)
        return [
            float(row["latencyMs"]) for row in raw["requests"]
            if not row["warmup"] and row["datasetSize"] == dataset_size and row["phase"] == phase
            and (cache_hit is None or row["cacheHit"] is cache_hit)
        ]
    if metric == "revocationEventProcessingLatencyMs":
        return [
            float(row["eventProcessingLatencyMs"]) for row in raw["revocationEvents"]
            if not row["warmup"] and row["datasetSize"] == dataset_size
        ]
    if metric == "revocationEnforcementLatencyMs":
        return [
            float(row["revocationEnforcementLatencyMs"]) for row in raw["revocationEvents"]
            if not row["warmup"] and row["datasetSize"] == dataset_size
            and row["revocationEnforcementLatencyMs"] is not None
        ]
    raise ValueError(f"Unknown latency metric: {metric}")


def plot_mean_latency(
    sizes: tuple[int, ...],
    metric: str,
    raw: dict[str, Any],
    task6_summaries: dict[int, dict[str, Any]],
    statistical_rows: dict[tuple[int, str], dict[str, Any]],
) -> tuple[list[float], list[tuple[float, float]]]:
    means = []
    intervals = []
    task6_key = LATENCY_TASK6_KEYS[metric]
    for size in sizes:
        aggregate = task6_summaries[size][task6_key]
        row = statistical_rows[(size, metric)]
        values = raw_latency_samples(raw, size, metric)
        raw_mean = statistics.mean(values)
        if aggregate["sampleCount"] != len(values) or row["sampleCount"] != len(values):
            raise ValueError(f"Task 6 and Task 7 samples differ for {size}/{metric}")
        if not math.isclose(raw_mean, aggregate["meanMs"], rel_tol=1e-10):
            raise ValueError(f"Task 6 raw and aggregate means differ for {size}/{metric}")
        means.append(raw_mean)
        intervals.append(confidence(statistical_rows, size, metric))
    return means, intervals


def mean_ci_error(means: list[float], intervals: list[tuple[float, float]]) -> np.ndarray:
    lower = [max(0.0, mean - interval[0]) for mean, interval in zip(means, intervals)]
    upper = [max(0.0, interval[1] - mean) for mean, interval in zip(means, intervals)]
    return np.array([lower, upper])


def style_axes(ax: Any, title: str, ylabel: str) -> None:
    ax.set_title(title, fontsize=12, weight="bold", pad=12)
    ax.set_ylabel(ylabel)
    ax.set_xlabel("Synthetic corpus size (documents)")
    ax.set_xticks(range(len(SIZES)), SIZE_LABELS)
    ax.grid(True, which="major", axis="y", alpha=0.25)
    ax.set_axisbelow(True)


def save_figure(fig: Any, filename: str) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT_DIR / filename, dpi=180, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    raw = load_json(TASK6_DIR / "repeated_experiment_raw.json")
    task6 = load_json(TASK6_DIR / "repeated_experiment_summary.json")
    metadata = load_json(TASK6_DIR / "reproducibility_metadata.json")
    task7 = load_json(TASK7_DIR / "statistical_summary.json")
    if metadata["datasetSizes"] != list(SIZES) or metadata["repetitions"] != 30:
        raise ValueError("Unexpected Task 6 dataset or repetition configuration")
    if sum(not row["warmup"] for row in raw["trials"]) != 840:
        raise ValueError("Task 6 raw observations do not contain 840 measured scenarios")

    task6_summaries = {row["datasetSize"]: row for row in task6}
    statistical_rows = {
        (row["datasetSize"], row["metric"]): row for row in task7["statisticalRows"]
    }
    if set(task6_summaries) != set(SIZES):
        raise ValueError("Task 6 aggregates do not contain all four dataset sizes")

    x = list(range(len(SIZES)))
    categories = (
        ("freshRetrievalLatencyMs", "Fresh retrieval", PALETTE[0]),
        ("cachedRetrievalLatencyMs", "Cached before revocation", PALETTE[1]),
        ("postRevocationRetrievalLatencyMs", "After revocation (combined)", PALETTE[2]),
    )
    fig, ax = plt.subplots(figsize=(8.6, 5.2))
    for metric, label, color in categories:
        means, intervals = plot_mean_latency(SIZES, metric, raw, task6_summaries, statistical_rows)
        ax.errorbar(
            x, means, yerr=mean_ci_error(means, intervals), color=color, marker="o",
            linewidth=2, capsize=4, markersize=6, label=label,
        )
    ax.set_yscale("log")
    style_axes(ax, "Retrieval latency by corpus size", "Mean latency (ms; log scale)")
    ax.legend(frameon=False, loc="best")
    ax.text(
        0.01, -0.23,
        "Task 6 simulated lexical retrieval; bars show 95% repetition-cluster bootstrap CIs.",
        transform=ax.transAxes, fontsize=8, color="#555555",
    )
    fig.tight_layout()
    save_figure(fig, "retrieval-latency.png")

    fig, ax = plt.subplots(figsize=(8.6, 5.2))
    for offset, (metric, label, color) in enumerate((
        ("postRevocationCacheMissLatencyMs", "Cache miss after invalidation", PALETTE[3]),
        ("postRevocationCacheHitLatencyMs", "Cache hit with revalidation", PALETTE[4]),
    )):
        means = []
        intervals = [confidence(statistical_rows, size, metric) for size in SIZES]
        for size in SIZES:
            values = raw_latency_samples(raw, size, metric)
            mean = statistics.mean(values)
            stat_row = statistical_rows[(size, metric)]
            if len(values) != stat_row["sampleCount"] or not math.isclose(
                    mean, stat_row["mean"], rel_tol=1e-10):
                raise ValueError(f"Raw post-revocation cache latency differs for {size}/{metric}")
            means.append(mean)
        ax.errorbar(
            x, means, yerr=mean_ci_error(means, intervals), color=color, marker="s" if offset else "o",
            linewidth=2, capsize=4, markersize=6, label=label,
        )
    ax.set_yscale("log")
    style_axes(ax, "Post-revocation retrieval: cache path", "Mean latency (ms; log scale)")
    ax.legend(frameon=False, loc="best")
    ax.text(
        0.01, -0.23,
        "Per repetition: one invalidated-cache miss and two cached revalidation hits.",
        transform=ax.transAxes, fontsize=8, color="#555555",
    )
    fig.tight_layout()
    save_figure(fig, "post-revocation-cache-paths.png")

    for metric, filename, title in (
        ("revocationEventProcessingLatencyMs", "revocation-processing-latency.png",
         "Revocation event processing latency"),
        ("revocationEnforcementLatencyMs", "revocation-enforcement-latency.png",
         "Revocation enforcement latency"),
    ):
        means, intervals = plot_mean_latency(SIZES, metric, raw, task6_summaries, statistical_rows)
        fig, ax = plt.subplots(figsize=(8.6, 5.2))
        ax.errorbar(
            x, means, yerr=mean_ci_error(means, intervals), color=PALETTE[0], marker="o",
            linewidth=2, capsize=4, markersize=6,
        )
        style_axes(ax, title, "Mean latency (ms)")
        ax.text(
            0.01, -0.23,
            "Task 6 simulated prototype; error bars are 95% repetition-cluster bootstrap CIs.",
            transform=ax.transAxes, fontsize=8, color="#555555",
        )
        fig.tight_layout()
        save_figure(fig, filename)

    request_rows = [row for row in raw["requests"] if not row["warmup"]]
    document_rows = [row for row in raw["documents"] if not row["warmup"]]
    fig, ax = plt.subplots(figsize=(8.6, 5.2))
    request_rates = []
    request_upper = []
    document_rates = []
    document_upper = []
    for size in SIZES:
        requests = [row for row in request_rows if row["datasetSize"] == size]
        documents = [row for row in document_rows if row["datasetSize"] == size]
        request_count = sum(row["unauthorizedDocuments"] > 0 for row in requests)
        unauthorized_documents = sum(not row["authorized"] for row in documents)
        request_row = statistical_rows[(size, "unauthorizedRetrievalRate")]
        document_row = statistical_rows[(size, "unauthorizedDocumentExposureRate")]
        if request_row["numerator"] != request_count or document_row["numerator"] != unauthorized_documents:
            raise ValueError(f"Raw security rates differ from Task 7 summary for {size}")
        request_rates.append(request_count / len(requests))
        request_upper.append(request_row["ci95High"])
        document_rates.append(unauthorized_documents / len(documents))
        document_upper.append(document_row["ci95High"])
    width = 0.13
    for positions, rates, uppers, label, color in (
        ([value - width for value in x], request_rates, request_upper,
         "Request-level exposure", PALETTE[0]),
        ([value + width for value in x], document_rates, document_upper,
         "Document-level exposure", PALETTE[1]),
    ):
        ax.errorbar(
            positions, [rate * 100 for rate in rates],
            yerr=[[0.0 for _ in rates], [max(0.0, upper - rate) * 100
                                         for rate, upper in zip(rates, uppers)]],
            fmt="o", capsize=4, markersize=6, color=color, label=label,
        )
    ax.set_ylim(0, 1.4)
    ax.set_ylabel("Observed exposure rate (%)")
    ax.set_xlabel("Synthetic corpus size (documents)")
    ax.set_title("Unauthorized retrieval and document exposure", fontsize=12, weight="bold", pad=12)
    ax.set_xticks(x, SIZE_LABELS)
    ax.grid(True, axis="y", alpha=0.25)
    ax.set_axisbelow(True)
    ax.legend(frameon=False)
    ax.text(
        0.01, -0.23,
        "Observed rate is 0 for every size; whiskers show nominal 95% Wilson intervals.",
        transform=ax.transAxes, fontsize=8, color="#555555",
    )
    fig.tight_layout()
    save_figure(fig, "unauthorized-exposure-rates.png")

    fig, ax = plt.subplots(figsize=(8.6, 5.2))
    bar_width = 0.32
    for offset, (metric, label, color) in enumerate((
        ("precisionAt5", "Precision@5", PALETTE[0]),
        ("authorizedRelevantPrecisionAt5", "Authorized-relevant Precision@5", PALETTE[2]),
    )):
        means = []
        intervals = []
        for size in SIZES:
            observations = [
                row for row in raw["precisionObservations"]
                if not row["warmup"] and row["datasetSize"] == size
            ]
            key = "precisionAtK" if metric == "precisionAt5" else "authorizedRelevantPrecisionAtK"
            mean = statistics.mean(row[key] for row in observations)
            task7_row = statistical_rows[(size, metric)]
            if not math.isclose(mean, task7_row["mean"], rel_tol=1e-12):
                raise ValueError(f"Raw precision differs from Task 7 summary for {size}/{metric}")
            means.append(mean)
            intervals.append((task7_row["ci95Low"], task7_row["ci95High"]))
        positions = [value + (offset - 0.5) * bar_width for value in x]
        errors = mean_ci_error(means, intervals)
        ax.bar(
            positions, means, width=bar_width, color=color, alpha=0.82, label=label,
            yerr=errors, capsize=4, edgecolor="white", linewidth=0.8,
        )
    ax.set_ylim(0, 1.08)
    ax.set_ylabel("Mean Precision@5")
    ax.set_xlabel("Synthetic corpus size (documents)")
    ax.set_title("Retrieval relevance results", fontsize=12, weight="bold", pad=12)
    ax.set_xticks(x, SIZE_LABELS)
    ax.grid(True, axis="y", alpha=0.25)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, loc="lower right")
    ax.text(
        0.01, -0.23,
        "30 measured observations per dataset; the runner evaluates QUERY-ENGINEERING only.",
        transform=ax.transAxes, fontsize=8, color="#555555",
    )
    fig.tight_layout()
    save_figure(fig, "precision-at-5.png")

    print(f"Generated six figures from the existing Task 6 observations in {OUTPUT_DIR}.")


if __name__ == "__main__":
    main()
