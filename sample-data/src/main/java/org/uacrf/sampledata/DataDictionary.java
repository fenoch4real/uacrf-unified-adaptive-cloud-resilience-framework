package org.uacrf.sampledata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Technical Approach data dictionary: node labels, relationship types, endpoint roles, aliases. */
final class DataDictionary {
  record NodeDef(String domain, String label, List<String> required, List<String> common) {
    String idProperty() {
      return required.getFirst();
    }

    Set<String> allowed() {
      Set<String> all = new LinkedHashSet<>(required);
      all.addAll(common);
      return all;
    }
  }

  record RelationshipDef(
      String domain, String type, String source, String target, List<String> properties, String cardinality,
      List<String> common) {
    Set<String> allowed() {
      Set<String> all = new LinkedHashSet<>(properties);
      all.addAll(common);
      return all;
    }
  }

  final Map<String, NodeDef> nodes = new LinkedHashMap<>();
  final Map<String, RelationshipDef> relationships = new LinkedHashMap<>();
  final Map<String, Set<String>> roles = new LinkedHashMap<>();
  final Set<String> aliasNames = new LinkedHashSet<>();
  final List<String> consistencyProblems = new ArrayList<>();

  static DataDictionary load(Path directory) throws IOException {
    DataDictionary dictionary = new DataDictionary();
    for (Map<String, String> row : readCsv(directory.resolve("nodes.csv"))) {
      NodeDef node = new NodeDef(row.get("domain"), row.get("label"), words(row.get("required_properties")),
          words(row.get("common_properties")));
      dictionary.nodes.put(node.label(), node);
    }
    for (Map<String, String> row : readCsv(directory.resolve("relationships.csv"))) {
      RelationshipDef relationship = new RelationshipDef(row.get("domain"), row.get("relationship"),
          row.get("source"), row.get("target"), words(row.get("properties")), row.get("cardinality"),
          words(row.get("common_properties")));
      dictionary.relationships.put(relationship.type(), relationship);
    }
    JsonMapper mapper = JsonMapper.builder().build();
    JsonNode roles = mapper.readTree(Files.readString(directory.resolve("endpoint_roles.json"))).get("roles");
    for (Map.Entry<String, JsonNode> entry : roles.properties()) {
      Set<String> labels = new LinkedHashSet<>();
      entry.getValue().forEach(label -> labels.add(label.asString()));
      dictionary.roles.put(entry.getKey(), labels);
    }
    JsonNode aliases = mapper.readTree(Files.readString(directory.resolve("field_aliases.json")));
    aliases.get("aliases").properties().forEach(entry -> dictionary.aliasNames.add(entry.getKey()));
    aliases.get("contextual_aliases").properties().forEach(entry -> dictionary.aliasNames.add(entry.getKey()));
    dictionary.checkAgainstSchema(mapper.readTree(Files.readString(directory.resolve("schema.json"))));
    return dictionary;
  }

  /** Labels an endpoint name can resolve to: a node label itself, or an endpoint role. */
  Set<String> resolve(String endpoint) {
    if (nodes.containsKey(endpoint)) {
      return Set.of(endpoint);
    }
    return roles.getOrDefault(endpoint, Set.of());
  }

  private void checkAgainstSchema(JsonNode schema) {
    JsonNode schemaNodes = schema.get("nodes");
    if (schemaNodes.size() != nodes.size()) {
      consistencyProblems.add("schema.json has " + schemaNodes.size() + " nodes but nodes.csv has " + nodes.size());
    }
    for (JsonNode node : schemaNodes) {
      NodeDef csv = nodes.get(node.get("label").asString());
      if (csv == null) {
        consistencyProblems.add("schema.json node " + node.get("label").asString() + " is not in nodes.csv");
        continue;
      }
      List<String> properties = new ArrayList<>();
      node.get("properties").forEach(p -> properties.add(p.asString()));
      if (!properties.equals(csv.required())) {
        consistencyProblems.add("required properties differ for " + csv.label());
      }
    }
    JsonNode schemaRelationships = schema.get("relationships");
    if (schemaRelationships.size() != relationships.size()) {
      consistencyProblems.add("schema.json has " + schemaRelationships.size() + " relationships but relationships.csv has "
          + relationships.size());
    }
    for (JsonNode relationship : schemaRelationships) {
      RelationshipDef csv = relationships.get(relationship.get("type").asString());
      if (csv == null) {
        consistencyProblems.add("schema.json relationship " + relationship.get("type").asString() + " is not in relationships.csv");
      } else if (!csv.source().equals(relationship.get("source").asString())
          || !csv.target().equals(relationship.get("target").asString())) {
        consistencyProblems.add("endpoints differ for " + csv.type());
      }
    }
  }

  private static List<String> words(String value) {
    return value == null || value.isBlank() ? List.of() : Arrays.asList(value.trim().split("\\s+"));
  }

  private static List<Map<String, String>> readCsv(Path file) throws IOException {
    List<List<String>> records = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    String text = Files.readString(file);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        record.add(field.toString());
        field.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        record.add(field.toString());
        field.setLength(0);
        records.add(record);
        record = new ArrayList<>();
      } else {
        field.append(c);
      }
    }
    if (field.length() > 0 || !record.isEmpty()) {
      record.add(field.toString());
      records.add(record);
    }
    List<String> header = records.getFirst();
    List<Map<String, String>> rows = new ArrayList<>();
    for (List<String> values : records.subList(1, records.size())) {
      if (values.size() == 1 && values.getFirst().isBlank()) {
        continue;
      }
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        row.put(header.get(i), i < values.size() ? values.get(i) : "");
      }
      rows.add(row);
    }
    return rows;
  }
}
