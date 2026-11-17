package edu.academic.rag.accesscontrol.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.academic.rag.accesscontrol.model.ExperimentResult;
import edu.academic.rag.accesscontrol.model.ReproducibilitySnapshot;
import edu.academic.rag.accesscontrol.model.RevocationRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles experimental telemetry logging, summary presentation,
 * and persistent exports to CSV, JSON, and Reproducibility Snapshot formats.
 */
public class ExperimentLogger {
    private static final String RESULTS_DIR = "results";
    private static final String CSV_FILE = "results/experiment_results.csv";
    private static final String JSON_FILE = "results/experiment_results.json";
    private static final String REVOCATION_METRICS_CSV = "results/revocation_metrics.csv";
    private static final String REVOCATION_METRICS_JSON = "results/revocation_metrics.json";
    private static final String REVOCATION_SUMMARY_CSV = "results/revocation_summary.csv";
    private static final String REVOCATION_SUMMARY_JSON = "results/revocation_summary.json";
    private static final String SNAPSHOT_FILE = "results/reproducibility_snapshot.json";

    private final ObjectMapper objectMapper;

    public ExperimentLogger() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void logSummaryTable(List<ExperimentResult> results) {
        System.out.println("\n" + "=".repeat(110));
        System.out.println(" EXPERIMENTAL EVALUATION REPORT: REVOCATION-AWARE DYNAMIC ACCESS CONTROL FOR RAG ");
        System.out.println("=".repeat(110));
        System.out.printf("%-6s | %-32s | %-7s | %-8s | %-12s | %-8s | %-20s%n",
                "TEST", "SCENARIO NAME", "STATUS", "DOCS RET", "LATENCY (ms)", "CACHE", "AUTH DECISION");
        System.out.println("-".repeat(110));

        int passedCount = 0;
        double totalLatency = 0.0;

        for (ExperimentResult res : results) {
            if (res.isPassed()) passedCount++;
            totalLatency += res.getLatencyMs();

            String statusStr = res.isPassed() ? "[PASS]" : "[FAIL]";
            System.out.printf("%-6s | %-32s | %-7s | %-8d | %-12.3f | %-8s | %-20s%n",
                    res.getTestCaseId(),
                    truncate(res.getTestCaseName(), 32),
                    statusStr,
                    res.getDocumentsReturned(),
                    res.getLatencyMs(),
                    res.isCacheHit() ? "HIT" : "MISS",
                    truncate(res.getAuthorizationDecision(), 20));
        }

        System.out.println("-".repeat(110));
        double avgLatency = results.isEmpty() ? 0.0 : (totalLatency / results.size());
        System.out.printf("SUMMARY: %d / %d Tests Passed (%.1f%%) | Mean Latency: %.3f ms%n",
                passedCount, results.size(),
                results.isEmpty() ? 0.0 : (passedCount * 100.0 / results.size()),
                avgLatency);
        System.out.println("=".repeat(110) + "\n");
    }

    public void exportToCsv(List<ExperimentResult> results, String filePath) throws IOException {
        Path path = Paths.get(filePath != null ? filePath : CSV_FILE);
        ensureParentDirectory(path);

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("TestCaseId,TestCaseName,Scenario,UserId,Passed,LatencyMs,DocsReturned,CacheHit,AuthorizationDecision,Timestamp,Details\n");
            for (ExperimentResult res : results) {
                writer.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",%s,%.4f,%d,%s,\"%s\",\"%s\",\"%s\"\n",
                        escape(res.getTestCaseId()),
                        escape(res.getTestCaseName()),
                        escape(res.getScenario()),
                        escape(res.getUserId()),
                        res.isPassed(),
                        res.getLatencyMs(),
                        res.getDocumentsReturned(),
                        res.isCacheHit(),
                        escape(res.getAuthorizationDecision()),
                        DateTimeFormatter.ISO_INSTANT.format(res.getTimestamp()),
                        escape(res.getDetails())));
            }
        }
        System.out.println("[ExperimentLogger] CSV exported successfully to: " + path.toAbsolutePath());
    }

    public void exportToJson(List<ExperimentResult> results, String filePath) throws IOException {
        Path path = Paths.get(filePath != null ? filePath : JSON_FILE);
        ensureParentDirectory(path);

        objectMapper.writeValue(path.toFile(), results);
        System.out.println("[ExperimentLogger] JSON exported successfully to: " + path.toAbsolutePath());
    }

    public void exportReproducibilitySnapshot(ReproducibilitySnapshot snapshot, String filePath) throws IOException {
        Path path = Paths.get(filePath != null ? filePath : SNAPSHOT_FILE);
        ensureParentDirectory(path);

        objectMapper.writeValue(path.toFile(), snapshot);
        System.out.println("[ExperimentLogger] Reproducibility snapshot exported successfully to: " + path.toAbsolutePath());
    }

    public void exportReproducibilitySnapshot(ReproducibilitySnapshot snapshot) throws IOException {
        exportReproducibilitySnapshot(snapshot, SNAPSHOT_FILE);
    }

    public ReproducibilitySnapshot createSnapshot(List<ExperimentResult> results, List<RevocationRecord> revocationHistory) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", Instant.now().toString());
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("osName", System.getProperty("os.name"));
        metadata.put("osArch", System.getProperty("os.arch"));
        metadata.put("totalTests", results != null ? results.size() : 0);
        long passedCount = results != null ? results.stream().filter(ExperimentResult::isPassed).count() : 0;
        metadata.put("passedTests", passedCount);
        metadata.put("failedTests", (results != null ? results.size() : 0) - passedCount);
        double avgLatency = (results != null && !results.isEmpty())
                ? results.stream().mapToDouble(ExperimentResult::getLatencyMs).average().orElse(0.0)
                : 0.0;
        metadata.put("meanLatencyMs", avgLatency);

        return new ReproducibilitySnapshot(
                UUID.randomUUID().toString(),
                Instant.now(),
                metadata,
                revocationHistory != null ? new ArrayList<>(revocationHistory) : Collections.emptyList(),
                results != null ? new ArrayList<>(results) : Collections.emptyList()
        );
    }

    public ReproducibilitySnapshot exportAll(List<ExperimentResult> results, List<RevocationRecord> revocationHistory) throws IOException {
        exportToCsv(results, CSV_FILE);
        exportToJson(results, JSON_FILE);
        ReproducibilitySnapshot snapshot = createSnapshot(results, revocationHistory);
        exportReproducibilitySnapshot(snapshot, SNAPSHOT_FILE);
        return snapshot;
    }

    public ReproducibilitySnapshot exportAll(List<ExperimentResult> results,
                                           List<RevocationRecord> revocationHistory,
                                           List<RevocationMetric> revocationMetrics) throws IOException {
        exportToCsv(results, CSV_FILE);
        exportToJson(results, JSON_FILE);
        exportRevocationMetrics(revocationMetrics, REVOCATION_METRICS_CSV, REVOCATION_METRICS_JSON, REVOCATION_SUMMARY_CSV, REVOCATION_SUMMARY_JSON);
        ReproducibilitySnapshot snapshot = createSnapshot(results, revocationHistory);
        exportReproducibilitySnapshot(snapshot, SNAPSHOT_FILE);
        return snapshot;
    }

    public void exportAll(List<ExperimentResult> results) throws IOException {
        exportAll(results, Collections.emptyList());
    }

    public void exportRevocationMetrics(List<RevocationMetric> metrics) throws IOException {
        exportRevocationMetrics(metrics, REVOCATION_METRICS_CSV, REVOCATION_METRICS_JSON,
                REVOCATION_SUMMARY_CSV, REVOCATION_SUMMARY_JSON);
    }

    public void exportResearchMetrics(ResearchMetricsCollector metrics) throws IOException {
        Objects.requireNonNull(metrics, "metrics must not be null");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("retrievalObservations", metrics.getRetrievalObservations());
        raw.put("documentObservations", metrics.getDocumentObservations());
        raw.put("revocationObservations", metrics.getRevocationObservations());
        raw.put("precisionObservations", metrics.getPrecisionObservations());
        raw.put("latencyObservations", metrics.getLatencyObservations());

        Path rawJson = Paths.get("results/research_metrics_raw.json");
        Path rawCsv = Paths.get("results/research_metrics_raw.csv");
        Path summaryJson = Paths.get("results/research_metrics_summary.json");
        Path summaryCsv = Paths.get("results/research_metrics_summary.csv");
        for (Path path : List.of(rawJson, rawCsv, summaryJson, summaryCsv)) {
            ensureParentDirectory(path);
        }

        objectMapper.writeValue(rawJson.toFile(), raw);
        try (BufferedWriter writer = Files.newBufferedWriter(rawCsv)) {
            writer.write("observationType,payloadJson\n");
            writeObservationRows(writer, "retrieval", metrics.getRetrievalObservations());
            writeObservationRows(writer, "document", metrics.getDocumentObservations());
            writeObservationRows(writer, "revocation", metrics.getRevocationObservations());
            writeObservationRows(writer, "precision", metrics.getPrecisionObservations());
            writeObservationRows(writer, "latency", metrics.getLatencyObservations());
        }

        Map<String, Object> summary = metrics.summarize();
        objectMapper.writeValue(summaryJson.toFile(), summary);
        try (BufferedWriter writer = Files.newBufferedWriter(summaryCsv)) {
            writer.write("metric,valueJson\n");
            for (Map.Entry<String, Object> entry : summary.entrySet()) {
                writer.write(csv(entry.getKey()));
                writer.write(',');
                writer.write(csv(compactJson(entry.getValue())));
                writer.newLine();
            }
        }
    }

    private void writeObservationRows(BufferedWriter writer, String type, List<?> observations) throws IOException {
        for (Object observation : observations) {
            writer.write(csv(type));
            writer.write(',');
            writer.write(csv(compactJson(observation)));
            writer.newLine();
        }
    }

    private String compactJson(Object value) throws IOException {
        return objectMapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(value);
    }

    private String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    public void exportRevocationMetrics(List<RevocationMetric> metrics,
                                       String rawCsvPath,
                                       String rawJsonPath,
                                       String summaryCsvPath,
                                       String summaryJsonPath) throws IOException {
        ensureParentDirectory(Paths.get(rawCsvPath));
        ensureParentDirectory(Paths.get(rawJsonPath));
        ensureParentDirectory(Paths.get(summaryCsvPath));
        ensureParentDirectory(Paths.get(summaryJsonPath));

        List<RevocationMetric> safeMetrics = metrics == null ? Collections.emptyList() : new ArrayList<>(metrics);

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(rawCsvPath))) {
            writer.write("testCaseId,scenarioName,userId,totalRevocations,successfulRevocations,successRate,unauthorizedCachedExposure,freshRetrievalLatencyMs,cachedRetrievalLatencyMs,retrievalLatencyAfterRevocationMs,cacheRevalidationOverheadMs,details,timestamp\n");
            for (RevocationMetric metric : safeMetrics) {
                writer.write(String.format("%s,%s,%s,%d,%d,%.4f,%d,%.4f,%.4f,%.4f,%.4f,%s,%s\n",
                        escape(metric.testCaseId()),
                        escape(metric.scenarioName()),
                        escape(metric.userId()),
                        metric.totalRevocations(),
                        metric.successfulRevocations(),
                        metric.successRate(),
                        metric.unauthorizedCachedExposure(),
                        metric.freshRetrievalLatencyMs(),
                        metric.cachedRetrievalLatencyMs(),
                        metric.retrievalLatencyAfterRevocationMs(),
                        metric.cacheRevalidationOverheadMs(),
                        escape(metric.details()),
                        DateTimeFormatter.ISO_INSTANT.format(metric.timestamp())));
            }
        }

        objectMapper.writeValue(Paths.get(rawJsonPath).toFile(), safeMetrics);
        System.out.println("[ExperimentLogger] Revocation metrics exported successfully to: " + Paths.get(rawJsonPath).toAbsolutePath());

        Map<String, Object> summary = summarizeRevocationMetrics(safeMetrics);
        try (BufferedWriter summaryWriter = Files.newBufferedWriter(Paths.get(summaryCsvPath))) {
            summaryWriter.write("metric,value\n");
            for (Map.Entry<String, Object> entry : summary.entrySet()) {
                Object value = entry.getValue();
                summaryWriter.write(String.format("%s,%s\n",
                        escape(entry.getKey()),
                        escape(String.valueOf(value))));
            }
        }
        objectMapper.writeValue(Paths.get(summaryJsonPath).toFile(), summary);
        System.out.println("[ExperimentLogger] Revocation summary exported successfully to: " + Paths.get(summaryJsonPath).toAbsolutePath());
    }

    public Map<String, Object> summarizeRevocationMetrics(List<RevocationMetric> metrics) {
        List<RevocationMetric> safeMetrics = metrics == null ? Collections.emptyList() : new ArrayList<>(metrics);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRevocationTestCases", safeMetrics.size());
        summary.put("successfulRevocationEnforcementCount",
                safeMetrics.stream().filter(metric -> metric.successfulRevocations() > 0 || metric.successRate() >= 1.0).count());
        summary.put("revocationEnforcementSuccessRate",
                safeMetrics.isEmpty() ? 0.0 : safeMetrics.stream().mapToDouble(RevocationMetric::successRate).average().orElse(0.0));
        summary.put("unauthorizedCachedResultExposureCount",
                safeMetrics.stream().mapToLong(RevocationMetric::unauthorizedCachedExposure).sum());
        summary.put("meanFreshRetrievalLatencyMs",
                safeMetrics.isEmpty() ? 0.0 : safeMetrics.stream().mapToDouble(RevocationMetric::freshRetrievalLatencyMs).average().orElse(0.0));
        summary.put("meanCacheRevalidationOverheadMs",
                safeMetrics.isEmpty() ? 0.0 : safeMetrics.stream().mapToDouble(RevocationMetric::cacheRevalidationOverheadMs).average().orElse(0.0));
        summary.put("timestamp", Instant.now().toString());
        return summary;
    }

    public ReproducibilitySnapshot loadReproducibilitySnapshot(String filePath) throws IOException {
        Path path = Paths.get(filePath != null ? filePath : SNAPSHOT_FILE);
        return objectMapper.readValue(path.toFile(), ReproducibilitySnapshot.class);
    }

    private void ensureParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    public record RevocationMetric(String testCaseId,
                                  String scenarioName,
                                  String userId,
                                  int totalRevocations,
                                  int successfulRevocations,
                                  double successRate,
                                  long unauthorizedCachedExposure,
                                  double freshRetrievalLatencyMs,
                                  double cachedRetrievalLatencyMs,
                                  double retrievalLatencyAfterRevocationMs,
                                  double cacheRevalidationOverheadMs,
                                  String details,
                                  Instant timestamp) {
    }
}
