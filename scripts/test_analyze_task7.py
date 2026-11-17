import math
import unittest

import analyze_task7


class Task7AnalysisTest(unittest.TestCase):
    def test_descriptive_statistics_use_sample_deviation_and_nearest_rank(self):
        result = analyze_task7.descriptive([1, 2, 3, 4, 5])

        self.assertEqual(result["mean"], 3)
        self.assertEqual(result["median"], 3)
        self.assertAlmostEqual(result["sampleStandardDeviation"], math.sqrt(2.5))
        self.assertEqual(result["minimum"], 1)
        self.assertEqual(result["maximum"], 5)
        self.assertEqual(result["p95"], 5)
        self.assertEqual(result["p99"], 5)

    def test_wilson_intervals_cover_zero_and_all_success_edges(self):
        low, high = analyze_task7.wilson_interval(0, 300)
        self.assertEqual(low, 0)
        self.assertGreater(high, 0)
        self.assertLess(high, 0.02)

        low, high = analyze_task7.wilson_interval(30, 30)
        self.assertLess(low, 1)
        self.assertEqual(high, 1)

    def test_raw_analysis_validates_counts_and_separates_post_revocation_cache_paths(self):
        result, rows, scalability = analyze_task7.build_analysis(
            analyze_task7.DEFAULT_INPUT, analyze_task7.DEFAULT_DATASETS, 100)

        self.assertEqual(result["validation"]["measuredTrials"], 840)
        self.assertEqual(result["validation"]["warmupTrialsExcluded"], 28)
        self.assertEqual(len(rows), 60)
        self.assertEqual(len(scalability), 28)
        for size in analyze_task7.SIZES:
            metrics = {row["metric"]: row for row in rows if row["datasetSize"] == size}
            self.assertEqual(metrics["unauthorizedRetrievalRate"]["numerator"], 0)
            self.assertEqual(metrics["postRevocationCacheMissLatencyMs"]["sampleCount"], 30)
            self.assertEqual(metrics["postRevocationCacheHitLatencyMs"]["sampleCount"], 60)
            self.assertEqual(metrics["precisionAt5"]["mean"], 1.0)


if __name__ == "__main__":
    unittest.main()
