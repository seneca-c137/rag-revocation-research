package edu.academic.rag.accesscontrol.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exports repeated-run observations, aggregates, and reproducibility metadata.
 */
public final class RepeatedExperimentLogger {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void export(RepeatedExperimentRunner.Results results, Path outputDirectory) throws IOException {
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Files.createDirectories(outputDirectory);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("trials", results.trials());
        raw.put("requests", results.requests());
        raw.put("documents", results.documents());
        raw.put("revocationEvents", results.revocationEvents());
        raw.put("precisionObservations", results.precisionObservations());
        objectMapper.writeValue(outputDirectory.resolve("repeated_experiment_raw.json").toFile(), raw);
        objectMapper.writeValue(outputDirectory.resolve("repeated_experiment_summary.json").toFile(),
                results.datasetSummaries());
        objectMapper.writeValue(outputDirectory.resolve("reproducibility_metadata.json").toFile(),
                results.metadata());

        writeRawCsv(results, outputDirectory.resolve("repeated_experiment_raw.csv"));
        writeSummaryCsv(results.datasetSummaries(), outputDirectory.resolve("repeated_experiment_summary.csv"));
    }

    private void writeRawCsv(RepeatedExperimentRunner.Results results, Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("observationType,payloadJson\n");
            writeRows(writer, "trial", results.trials());
            writeRows(writer, "request", results.requests());
            writeRows(writer, "document", results.documents());
            writeRows(writer, "revocation", results.revocationEvents());
            writeRows(writer, "precision", results.precisionObservations());
        }
    }

    private void writeRows(BufferedWriter writer, String type, List<?> observations) throws IOException {
        for (Object observation : observations) {
            writer.write(csv(type));
            writer.write(',');
            writer.write(csv(compactJson(observation)));
            writer.newLine();
        }
    }

    private void writeSummaryCsv(List<RepeatedExperimentRunner.DatasetSummary> summaries,
                                 Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("datasetSize,metric,sampleCount,value\n");
            for (RepeatedExperimentRunner.DatasetSummary summary : summaries) {
                writeMetric(writer, summary.datasetSize(), "unauthorizedRetrievalRate",
                        summary.totalRetrievalRequests(), summary.unauthorizedRetrievalRate());
                writeMetric(writer, summary.datasetSize(), "unauthorizedDocumentExposureRate",
                        summary.documentsReturned(), summary.unauthorizedDocumentExposureRate());
                writeMetric(writer, summary.datasetSize(), "revocationEventProcessingSuccessRate",
                        summary.generatedRevocationEvents(), summary.revocationEventProcessingSuccessRate());
                writeMetric(writer, summary.datasetSize(), "meanPrecisionAtK",
                        summary.precisionSampleCount(), summary.meanPrecisionAtK());
                writeMetric(writer, summary.datasetSize(), "meanAuthorizedRelevantPrecisionAtK",
                        summary.precisionSampleCount(), summary.meanAuthorizedRelevantPrecisionAtK());
                writeLatency(writer, summary.datasetSize(), "freshRetrieval", summary.freshRetrievalLatency());
                writeLatency(writer, summary.datasetSize(), "cachedRetrieval", summary.cachedRetrievalLatency());
                writeLatency(writer, summary.datasetSize(), "postRevocationRetrieval",
                        summary.postRevocationRetrievalLatency());
                writeLatency(writer, summary.datasetSize(), "revocationEventProcessing",
                        summary.revocationEventProcessingLatency());
                writeLatency(writer, summary.datasetSize(), "revocationEnforcement",
                        summary.revocationEnforcementLatency());
            }
        }
    }

    private void writeLatency(BufferedWriter writer, int datasetSize, String prefix,
                              RepeatedExperimentRunner.LatencyStatistics statistics) throws IOException {
        writeMetric(writer, datasetSize, prefix + ".meanMs", statistics.sampleCount(), statistics.meanMs());
        writeMetric(writer, datasetSize, prefix + ".medianMs", statistics.sampleCount(), statistics.medianMs());
        writeMetric(writer, datasetSize, prefix + ".standardDeviationMs",
                statistics.sampleCount(), statistics.standardDeviationMs());
        writeMetric(writer, datasetSize, prefix + ".p95Ms", statistics.sampleCount(), statistics.p95Ms());
        writeMetric(writer, datasetSize, prefix + ".p99Ms", statistics.sampleCount(), statistics.p99Ms());
    }

    private void writeMetric(BufferedWriter writer, int datasetSize, String metric,
                             long sampleCount, Number value) throws IOException {
        writer.write(Integer.toString(datasetSize));
        writer.write(',');
        writer.write(csv(metric));
        writer.write(',');
        writer.write(Long.toString(sampleCount));
        writer.write(',');
        writer.write(value == null ? "" : value.toString());
        writer.newLine();
    }

    private String compactJson(Object value) throws IOException {
        return objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value);
    }

    private String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }
}
