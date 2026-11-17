package edu.academic.rag.accesscontrol.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import edu.academic.rag.accesscontrol.model.ClassificationLevel;
import edu.academic.rag.accesscontrol.model.Document;
import edu.academic.rag.accesscontrol.model.DocumentMetadata;
import edu.academic.rag.accesscontrol.model.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Writes deterministic dataset JSON without model creation timestamps.
 */
public final class SyntheticDatasetExporter {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Path export(SyntheticDataset dataset, Path outputDirectory) throws IOException {
        Objects.requireNonNull(dataset, "dataset must not be null");
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(dataset.metadata().datasetId() + ".json");
        objectMapper.writeValue(output.toFile(), toExportPayload(dataset));
        return output;
    }

    public SyntheticDataset load(Path inputFile) throws IOException {
        Objects.requireNonNull(inputFile, "inputFile must not be null");
        JsonNode root = objectMapper.readTree(inputFile.toFile());
        JsonNode metadataNode = root.path("metadata");
        SyntheticDataset.Metadata metadata = new SyntheticDataset.Metadata(
                requiredText(metadataNode, "datasetId"),
                requiredInt(metadataNode, "documentCount"),
                requiredLong(metadataNode, "seed"),
                requiredInt(metadataNode, "userCount"),
                requiredInt(metadataNode, "queryCount"),
                requiredText(metadataNode, "generatorVersion"),
                requiredText(metadataNode, "relevanceRule")
        );

        List<Document> documents = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "documents")) {
            JsonNode attributesNode = requiredObject(node, "customAttributes");
            DocumentMetadata.Builder builder = DocumentMetadata.builder()
                    .department(requiredText(node, "department"))
                    .classificationLevel(ClassificationLevel.valueOf(requiredText(node, "classificationLevel")))
                    .allowedRoles(readTextArray(node, "allowedRoles").toArray(String[]::new))
                    .allowedUsers(readTextArray(node, "allowedUsers").toArray(String[]::new));
            Iterator<Map.Entry<String, JsonNode>> attributes = attributesNode.fields();
            while (attributes.hasNext()) {
                Map.Entry<String, JsonNode> entry = attributes.next();
                builder.attribute(entry.getKey(), entry.getValue().asText());
            }
            documents.add(new Document(
                    requiredText(node, "documentId"),
                    requiredText(node, "title"),
                    requiredText(node, "content"),
                    builder.build()));
        }

        List<User> users = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "users")) {
            User user = new User(requiredText(node, "userId"), requiredText(node, "name"),
                    requiredText(node, "department"),
                    ClassificationLevel.valueOf(requiredText(node, "clearanceLevel")));
            user.setRoles(readTextArray(node, "roles"));
            user.setActive(node.path("active").asBoolean());
            users.add(user);
        }

        List<SyntheticDataset.Query> queries = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "queries")) {
            queries.add(new SyntheticDataset.Query(requiredText(node, "queryId"),
                    requiredText(node, "queryText"), requiredText(node, "topicId")));
        }
        JsonNode labelsNode = requiredObject(root, "relevanceGroundTruth");
        Map<String, Set<String>> labels = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> labelEntries = labelsNode.fields();
        while (labelEntries.hasNext()) {
            Map.Entry<String, JsonNode> entry = labelEntries.next();
            if (!entry.getValue().isArray()) {
                throw new IOException("Ground-truth labels must be arrays for query " + entry.getKey());
            }
            Set<String> documentIds = new LinkedHashSet<>();
            for (JsonNode documentId : entry.getValue()) {
                documentIds.add(documentId.asText());
            }
            labels.put(entry.getKey(), documentIds);
        }
        if (documents.size() != metadata.documentCount() || users.size() != metadata.userCount() ||
                queries.size() != metadata.queryCount()) {
            throw new IOException("Dataset counts do not match exported metadata in " + inputFile);
        }
        return new SyntheticDataset(metadata, documents, users, queries, labels);
    }

    public List<Path> exportAll(Map<Integer, SyntheticDataset> datasets, Path outputDirectory) throws IOException {
        Objects.requireNonNull(datasets, "datasets must not be null");
        List<Path> files = new ArrayList<>();
        for (SyntheticDataset dataset : datasets.values()) {
            files.add(export(dataset, outputDirectory));
        }
        return List.copyOf(files);
    }

    private ExportPayload toExportPayload(SyntheticDataset dataset) {
        List<ExportDocument> documents = dataset.documents().stream()
                .map(this::toExportDocument).toList();
        List<ExportUser> users = dataset.users().stream()
                .map(this::toExportUser).toList();
        return new ExportPayload(dataset.metadata(), documents, users, dataset.queries(),
                dataset.relevanceGroundTruth());
    }

    private ExportDocument toExportDocument(Document document) {
        DocumentMetadata metadata = document.getMetadata();
        Map<String, String> attributes = new TreeMap<>(metadata.getCustomAttributes());
        return new ExportDocument(
                document.getDocumentId(),
                document.getTitle(),
                document.getContent(),
                metadata.getDepartment(),
                metadata.getClassificationLevel().name(),
                metadata.getAllowedRoles().stream().sorted().toList(),
                metadata.getAllowedUsers().stream().sorted().toList(),
                attributes
        );
    }

    private ExportUser toExportUser(User user) {
        return new ExportUser(user.getUserId(), user.getName(), user.getDepartment(),
                user.getClearanceLevel().name(), user.getRoles().stream().sorted().toList(), user.isActive());
    }

    private String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Missing or invalid text field: " + field);
        }
        return value.asText();
    }

    private int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt()) {
            throw new IOException("Missing or invalid integer field: " + field);
        }
        return value.asInt();
    }

    private long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.canConvertToLong()) {
            throw new IOException("Missing or invalid long field: " + field);
        }
        return value.asLong();
    }

    private JsonNode requiredArray(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw new IOException("Missing or invalid array field: " + field);
        }
        return value;
    }

    private JsonNode requiredObject(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isObject()) {
            throw new IOException("Missing or invalid object field: " + field);
        }
        return value;
    }

    private List<String> readTextArray(JsonNode node, String field) throws IOException {
        List<String> values = new ArrayList<>();
        for (JsonNode value : requiredArray(node, field)) {
            if (!value.isTextual()) {
                throw new IOException("Non-text value in array field: " + field);
            }
            values.add(value.asText());
        }
        return values;
    }

    private record ExportPayload(SyntheticDataset.Metadata metadata, List<ExportDocument> documents,
                                 List<ExportUser> users, List<SyntheticDataset.Query> queries,
                                 Map<String, Set<String>> relevanceGroundTruth) {}

    private record ExportDocument(String documentId, String title, String content, String department,
                                  String classificationLevel, List<String> allowedRoles,
                                  List<String> allowedUsers, Map<String, String> customAttributes) {}

    private record ExportUser(String userId, String name, String department, String clearanceLevel,
                              List<String> roles, boolean active) {}

    public static void main(String[] args) throws IOException {
        Path outputDirectory = args.length > 0 ? Path.of(args[0]) : Path.of("datasets/generated");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : SyntheticDatasetGenerator.DEFAULT_SEED;
        SyntheticDatasetGenerator generator = new SyntheticDatasetGenerator();
        Map<Integer, SyntheticDataset> datasets = generator.generateAll(seed);
        List<Path> exports = new SyntheticDatasetExporter().exportAll(datasets, outputDirectory);
        exports.forEach(path -> System.out.println("Exported " + path));
    }
}
