package edu.academic.rag.accesscontrol.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.academic.rag.accesscontrol.model.ExperimentResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Handles experimental telemetry logging, summary presentation,
 * and persistent exports to CSV and JSON formats.
 */
public class ExperimentLogger {
    private static final String RESULTS_DIR = "results";
    private static final String CSV_FILE = "results/experiment_results.csv";
    private static final String JSON_FILE = "results/experiment_results.json";

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

    public void exportAll(List<ExperimentResult> results) throws IOException {
        exportToCsv(results, CSV_FILE);
        exportToJson(results, JSON_FILE);
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
}
