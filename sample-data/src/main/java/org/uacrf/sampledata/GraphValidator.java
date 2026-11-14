package org.uacrf.sampledata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a graph fixture against the Technical Approach data dictionary: known labels and types,
 * required plus common properties (nothing else), snake_case names, no persisted aliases, endpoint
 * roles, cardinality, uniqueness, tenant scoping, and the synthetic-fixture provenance marker.
 */
final class GraphValidator {
  static final String SYNTHETIC = "SYNTHETIC_FIXTURE";
  private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

  record Result(List<String> problems, Set<String> labels, Set<String> types) {}

  private final DataDictionary dictionary;

  GraphValidator(DataDictionary dictionary) {
    this.dictionary = dictionary;
  }

  @SuppressWarnings("unchecked")
  Result validate(Map<String, Object> graph, String tenantId) {
    List<String> problems = new ArrayList<>(dictionary.consistencyProblems);
    Set<String> labels = new LinkedHashSet<>();
    Set<String> types = new LinkedHashSet<>();
    Map<String, String> labelById = new HashMap<>();

    for (Map<String, Object> node : (List<Map<String, Object>>) graph.get("nodes")) {
      String label = (String) node.get("label");
      String id = (String) node.get("id");
      DataDictionary.NodeDef def = dictionary.nodes.get(label);
      if (def == null) {
        problems.add("unknown node label " + label);
        continue;
      }
      labels.add(label);
      if (labelById.put(label + "/" + id, label) != null) {
        problems.add("duplicate node " + label + "/" + id);
      }
      Map<String, Object> properties = (Map<String, Object>) node.get("properties");
      checkProperties("node " + label + "/" + id, properties, def.allowed(), def.required(), def.common(),
          label.equals("Tenant"), problems);
      if (!id.equals(properties.get(def.idProperty()))) {
        problems.add("node " + label + "/" + id + " does not carry its id in " + def.idProperty());
      }
      if (!tenantId.equals(properties.get("tenant_id"))) {
        problems.add("node " + label + "/" + id + " is outside tenant " + tenantId);
      }
    }

    Map<String, String> byId = new HashMap<>();
    for (String key : labelById.keySet()) {
      String id = key.substring(key.indexOf('/') + 1);
      if (byId.put(id, labelById.get(key)) != null) {
        problems.add("node id " + id + " is used by more than one label, so relationships would be ambiguous");
      }
    }

    Set<String> relationshipIds = new HashSet<>();
    Map<String, Integer> outgoing = new HashMap<>();
    Map<String, Integer> incoming = new HashMap<>();
    for (Map<String, Object> relationship : (List<Map<String, Object>>) graph.get("relationships")) {
      String type = (String) relationship.get("type");
      DataDictionary.RelationshipDef def = dictionary.relationships.get(type);
      if (def == null) {
        problems.add("unknown relationship type " + type);
        continue;
      }
      types.add(type);
      Map<String, Object> properties = (Map<String, Object>) relationship.get("properties");
      String description = "relationship " + type + "/" + relationship.get("id");
      checkProperties(description, properties, def.allowed(), def.properties(), def.common(), false, problems);
      if (!relationshipIds.add((String) properties.get("relationship_id"))) {
        problems.add(description + " repeats relationship_id " + properties.get("relationship_id"));
      }
      String sourceId = (String) relationship.get("source");
      String targetId = (String) relationship.get("target");
      String sourceLabel = byId.get(sourceId);
      String targetLabel = byId.get(targetId);
      if (sourceLabel == null || targetLabel == null) {
        problems.add(description + " has a dangling endpoint " + sourceId + " -> " + targetId);
        continue;
      }
      if (!dictionary.resolve(def.source()).contains(sourceLabel)) {
        problems.add(description + " source " + sourceLabel + " is not allowed by " + def.source());
      }
      if (!dictionary.resolve(def.target()).contains(targetLabel)) {
        problems.add(description + " target " + targetLabel + " is not allowed by " + def.target());
      }
      outgoing.merge(type + "|" + sourceId, 1, Integer::sum);
      incoming.merge(type + "|" + targetId, 1, Integer::sum);
    }

    for (DataDictionary.RelationshipDef def : dictionary.relationships.values()) {
      boolean singleTarget = def.cardinality().endsWith(":1");
      boolean singleSource = def.cardinality().startsWith("1:");
      if (singleTarget) {
        outgoing.forEach((key, count) -> {
          if (key.startsWith(def.type() + "|") && count > 1) {
            problems.add(def.type() + " is " + def.cardinality() + " but " + key + " has " + count + " targets");
          }
        });
      }
      if (singleSource) {
        incoming.forEach((key, count) -> {
          if (key.startsWith(def.type() + "|") && count > 1) {
            problems.add(def.type() + " is " + def.cardinality() + " but " + key + " has " + count + " sources");
          }
        });
      }
    }
    return new Result(problems, labels, types);
  }

  /** Labels and relationship types the dictionary defines that the fixture never uses. */
  List<String> coverageGaps(Result result) {
    List<String> gaps = new ArrayList<>();
    dictionary.nodes.keySet().stream().filter(l -> !result.labels().contains(l)).forEach(l -> gaps.add("node " + l));
    dictionary.relationships.keySet().stream().filter(t -> !result.types().contains(t))
        .forEach(t -> gaps.add("relationship " + t));
    return gaps;
  }

  private void checkProperties(
      String description, Map<String, Object> properties, Set<String> allowed, List<String> required,
      List<String> common, boolean isTenant, List<String> problems) {
    for (String key : properties.keySet()) {
      if (!SNAKE_CASE.matcher(key).matches()) {
        problems.add(description + " property " + key + " is not snake_case");
      }
      if (dictionary.aliasNames.contains(key) && !allowed.contains(key)) {
        problems.add(description + " persists ingestion-only alias " + key);
      }
      if (!allowed.contains(key)) {
        problems.add(description + " has undefined property " + key);
      }
    }
    Set<String> expected = new LinkedHashSet<>(required);
    expected.addAll(common);
    for (String key : expected) {
      if (!properties.containsKey(key)) {
        problems.add(description + " is missing " + key);
      } else if (properties.get(key) == null && !nullable(key, isTenant)) {
        problems.add(description + " has null " + key);
      }
    }
    if (!SYNTHETIC.equals(properties.get("source_kind"))) {
      problems.add(description + " is not marked " + SYNTHETIC);
    }
  }

  private static boolean nullable(String key, boolean isTenant) {
    return key.equals("valid_to") || key.equals("effective_to") || (isTenant && key.equals("environment_id"));
  }
}
