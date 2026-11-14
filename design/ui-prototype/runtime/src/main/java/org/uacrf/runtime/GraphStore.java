package org.uacrf.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import org.neo4j.driver.*;

/** Neo4j materialized view of an accepted SQL snapshot. No arbitrary Cypher is accepted. */
final class GraphStore implements AutoCloseable {
  final Driver driver;
  final String database;
  final Map<String, String> queries = new HashMap<>();
  final TransactionConfig timeout =
      TransactionConfig.builder().withTimeout(Duration.ofSeconds(15)).build();

  GraphStore(GraphConfig cfg, Path root) {
    driver = cfg.connect();
    database = cfg.database();
    try {
      String source = Files.readString(root.resolve("database/neo4j/002_projection.cypher"));
      for (String part : source.split("// name: ")) {
        if (!part.startsWith("project_")) continue;
        int i = part.indexOf('\n');
        String name = part.substring(0, i).trim();
        String text =
            part.substring(i + 1).replaceAll("(?m)^//.*$", "").trim().replaceAll(";$", "");
        queries.put(name, text);
      }
      if (queries.isEmpty()) throw new IllegalArgumentException("Projection allowlist missing");
    } catch (Exception e) {
      driver.close();
      throw new IllegalStateException("Graph query allowlist unavailable", e);
    }
  }

  void constraints(Path root) throws Exception {
    try (var s = driver.session(SessionConfig.forDatabase(database))) {
      for (String stmt :
          Files.readString(root.resolve("database/neo4j/001_constraints.cypher")).split(";")) {
        String q = stmt.replaceAll("(?m)^//.*$", "").trim();
        if (!q.isEmpty()) s.run(q).consume();
      }
    }
  }

  Map<String, Object> params(JsonNode snapshot) {
    return Map.of(
        "tenant_id",
        snapshot.path("tenant_id").asText(),
        "environment_id",
        snapshot.path("environment_id").asText(),
        "graph_revision",
        snapshot.path("graph_revision").asText());
  }

  static Map<String, Object> properties(JsonNode n) {
    Map<String, Object> out = new HashMap<>();
    n.fields()
        .forEachRemaining(
            e -> {
              JsonNode v = e.getValue();
              if (v.isNull()) return;
              if (v.isTextual()) out.put(e.getKey(), v.asText());
              else if (v.isIntegralNumber()) out.put(e.getKey(), v.asLong());
              else if (v.isNumber()) out.put(e.getKey(), v.asDouble());
              else if (v.isBoolean()) out.put(e.getKey(), v.asBoolean());
              else throw new IllegalArgumentException("Nested Neo4j property rejected");
            });
    return out;
  }

  void assertConstraints() {
    try (var session = driver.session(SessionConfig.forDatabase(database))) {
      var rows =
          session
              .run(
                  "SHOW CONSTRAINTS YIELD name,type,labelsOrTypes,properties RETURN"
                      + " name,type,labelsOrTypes,properties")
              .list();
      for (var expected :
          Map.of(
                  "uacrf_projection_identity",
                  List.of("tenant_id", "environment_id", "graph_revision", "canonical_id"),
                  "uacrf_projection_batch",
                  List.of("tenant_id", "environment_id", "graph_revision"))
              .entrySet()) {
        var row =
            rows.stream()
                .filter(r -> r.get("name").asString().equals(expected.getKey()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Neo4j projection uniqueness constraints must be installed by an"
                                + " administrator"));
        String label =
            expected.getKey().equals("uacrf_projection_identity") ? "UACRFEntity" : "UACRFBatch";
        if (!row.get("type").asString().equals("UNIQUENESS")
            || !row.get("labelsOrTypes").asList(v -> v.asString()).equals(List.of(label))
            || !row.get("properties").asList(v -> v.asString()).equals(expected.getValue()))
          throw new IllegalStateException("Neo4j constraint definition mismatch");
      }
    }
  }

  String project(ObjectNode claim) {
    assertConstraints();
    String canonical = claim.path("canonical_text").asText(),
        digest = claim.path("digest").asText();
    if (!Json.hash(canonical).equals(digest))
      throw new IllegalArgumentException("SQL canonical snapshot digest mismatch");
    JsonNode snapshot = Json.read(canonical);
    if (!snapshot.equals(claim.path("snapshot"))
        || !snapshot.path("protocol").asText().equals("UACRF_GRAPH_V1"))
      throw new IllegalArgumentException("Snapshot protocol mismatch");
    int nodes = snapshot.path("nodes").size(), edges = snapshot.path("edges").size();
    if (nodes == 0
        || nodes > 10000
        || edges > 75000
        || canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16777216)
      throw new IllegalArgumentException("Projection capacity exceeded");
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    Set<String> ids = new HashSet<>(), edgeIds = new HashSet<>();
    for (var n : snapshot.path("nodes")) {
      String id = n.path("canonical_id").asText(), key = "project_" + n.path("label").asText();
      if (id.isBlank() || !ids.add(id) || !queries.containsKey(key))
        throw new IllegalArgumentException("Unknown or duplicate graph node");
      var props = properties(n.path("properties"));
      if (!Collections.disjoint(
          props.keySet(), Set.of("tenant_id", "environment_id", "graph_revision", "canonical_id")))
        throw new IllegalArgumentException("Reserved graph identity property");
      groups
          .computeIfAbsent(key, k -> new ArrayList<>())
          .add(Map.of("canonical_id", id, "properties", props));
    }
    for (var e : snapshot.path("edges")) {
      String id = e.path("relationship_id").asText(), key = "project_" + e.path("type").asText();
      if (id.isBlank()
          || !edgeIds.add(id)
          || !queries.containsKey(key)
          || !ids.contains(e.path("source_id").asText())
          || !ids.contains(e.path("target_id").asText()))
        throw new IllegalArgumentException("Invalid graph relationship");
      var props = properties(e.path("properties"));
      if (!Collections.disjoint(
          props.keySet(),
          Set.of("tenant_id", "environment_id", "graph_revision", "relationship_id")))
        throw new IllegalArgumentException("Reserved edge identity property");
      groups
          .computeIfAbsent(key, k -> new ArrayList<>())
          .add(
              Map.of(
                  "relationship_id",
                  id,
                  "source_id",
                  e.path("source_id").asText(),
                  "target_id",
                  e.path("target_id").asText(),
                  "properties",
                  props));
    }
    Map<String, Object> p = new HashMap<>(params(snapshot));
    p.put("digest", digest);
    p.put("source_watermark", snapshot.path("source_watermark").asLong());
    p.put("node_count", nodes);
    p.put("edge_count", edges);
    try (var session = driver.session(SessionConfig.forDatabase(database))) {
      return session.executeWrite(
          tx -> {
            var batch =
                tx.run(
                        "MERGE (b:UACRFBatch"
                            + " {tenant_id:$tenant_id,environment_id:$environment_id,graph_revision:$graph_revision})"
                            + " ON CREATE SET"
                            + " b.digest=$digest,b.source_watermark=$source_watermark,b.state='BUILDING'"
                            + " SET b.lock=coalesce(b.lock,0)+1 RETURN b",
                        p)
                    .single()
                    .get("b")
                    .asNode();
            if (!batch.get("digest").asString().equals(digest)
                || batch.get("source_watermark").asLong()
                    != snapshot.path("source_watermark").asLong())
              throw new IllegalStateException("Conflicting graph receipt");
            if (batch.get("state").asString().equals("SEALED")) {
              JsonNode stored = read(tx, p);
              if (!matches(snapshot, stored)
                  || batch.get("node_count").asInt() != nodes
                  || batch.get("edge_count").asInt() != edges)
                throw new IllegalStateException("Sealed graph content mismatch");
              return "DUPLICATE";
            }
            if (!batch.get("state").asString().equals("BUILDING"))
              throw new IllegalStateException("Closed graph batch");
            for (var group : groups.entrySet()) {
              var args = new HashMap<>(p);
              args.put("rows", group.getValue());
              long matched =
                  tx.run(queries.get(group.getKey()), args).single().get("matched_rows").asLong();
              if (matched != group.getValue().size())
                throw new IllegalStateException("Projection endpoint count mismatch");
            }
            if (!matches(snapshot, read(tx, p)))
              throw new IllegalStateException("Graph content verification failed");
            tx.run(
                    "MATCH (b:UACRFBatch"
                        + " {tenant_id:$tenant_id,environment_id:$environment_id,graph_revision:$graph_revision})"
                        + " SET b.state='SEALED',b.node_count=$node_count,b.edge_count=$edge_count"
                        + " RETURN b",
                    p)
                .consume();
            return "SEALED";
          },
          timeout);
    }
  }

  ObjectNode verified(JsonNode expected, String digest) {
    try (var session = driver.session(SessionConfig.forDatabase(database))) {
      return session.executeRead(
          tx -> {
            Map<String, Object> p = params(expected);
            var rows =
                tx.run(
                        "MATCH (b:UACRFBatch"
                            + " {tenant_id:$tenant_id,environment_id:$environment_id,graph_revision:$graph_revision})"
                            + " RETURN b",
                        p)
                    .list();
            if (rows.size() != 1) throw new Problem(503, "Graph projection missing");
            var b = rows.get(0).get("b").asNode();
            if (!b.get("state").asString().equals("SEALED")
                || !b.get("digest").asString().equals(digest)
                || b.get("source_watermark").asLong() != expected.path("source_watermark").asLong()
                || b.get("node_count").asInt() != expected.path("nodes").size()
                || b.get("edge_count").asInt() != expected.path("edges").size())
              throw new Problem(503, "Graph projection receipt mismatch");
            ObjectNode actual = read(tx, p);
            if (!matches(expected, actual))
              throw new Problem(503, "Graph projection content mismatch");
            return actual;
          },
          timeout);
    }
  }

  static ObjectNode read(TransactionContext tx, Map<String, Object> p) {
    ObjectNode out = Json.M.createObjectNode();
    ArrayNode nodes = out.putArray("nodes"), edges = out.putArray("edges");
    var ns =
        tx.run(
                "MATCH (n:UACRFEntity"
                    + " {tenant_id:$tenant_id,environment_id:$environment_id,graph_revision:$graph_revision})"
                    + " RETURN n ORDER BY n.canonical_id LIMIT 10001",
                p)
            .list();
    if (ns.size() > 10000) throw new Problem(503, "Graph capacity exceeded");
    for (var row : ns) {
      var n = row.get("n").asNode();
      var props = new HashMap<>(n.asMap());
      String id = (String) props.remove("canonical_id");
      props.remove("tenant_id");
      props.remove("environment_id");
      props.remove("graph_revision");
      var labels = new ArrayList<String>();
      for (var label : n.labels()) if (!label.equals("UACRFEntity")) labels.add(label);
      if (labels.size() != 1) throw new Problem(503, "Graph label mismatch");
      nodes
          .addObject()
          .put("canonical_id", id)
          .put("label", labels.get(0))
          .set("properties", Json.M.valueToTree(props));
    }
    var es =
        tx.run(
                "MATCH (s:UACRFEntity)-[r]->(d:UACRFEntity) WHERE r.tenant_id=$tenant_id AND"
                    + " r.environment_id=$environment_id AND r.graph_revision=$graph_revision"
                    + " RETURN s,r,d ORDER BY r.relationship_id LIMIT 75001",
                p)
            .list();
    if (es.size() > 75000) throw new Problem(503, "Graph capacity exceeded");
    for (var row : es) {
      var s = row.get("s").asNode();
      var d = row.get("d").asNode();
      for (var n : List.of(s, d))
        if (!n.get("tenant_id").asString().equals(p.get("tenant_id"))
            || !n.get("environment_id").asString().equals(p.get("environment_id"))
            || !n.get("graph_revision").asString().equals(p.get("graph_revision")))
          throw new Problem(503, "Cross-scope graph edge rejected");
      var r = row.get("r").asRelationship();
      var props = new HashMap<>(r.asMap());
      String id = (String) props.remove("relationship_id");
      props.remove("tenant_id");
      props.remove("environment_id");
      props.remove("graph_revision");
      edges
          .addObject()
          .put("relationship_id", id)
          .put("source_id", s.get("canonical_id").asString())
          .put("target_id", d.get("canonical_id").asString())
          .put("type", r.type())
          .set("properties", Json.M.valueToTree(props));
    }
    return out;
  }

  static boolean matches(JsonNode expected, JsonNode actual) {
    return normalized(expected.path("nodes")).equals(normalized(actual.path("nodes")))
        && normalized(expected.path("edges")).equals(normalized(actual.path("edges")));
  }

  static JsonNode normalized(JsonNode n) {
    if (n.isObject()) {
      var o = Json.M.createObjectNode();
      n.fields()
          .forEachRemaining(
              e -> {
                if (!e.getValue().isNull()) o.set(e.getKey(), normalized(e.getValue()));
              });
      return o;
    }
    if (n.isArray()) {
      var a = Json.M.createArrayNode();
      for (var v : n) a.add(normalized(v));
      return a;
    }
    if (n.isNumber())
      return Json.M.getNodeFactory().numberNode(n.decimalValue().stripTrailingZeros());
    return n;
  }

  @Override
  public void close() {
    driver.close();
  }
}
