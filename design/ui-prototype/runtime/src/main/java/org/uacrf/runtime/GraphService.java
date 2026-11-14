package org.uacrf.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

final class GraphService implements AutoCloseable {
  final Config cfg;
  final Database db;
  final GraphStore store;
  static final Set<String> DEPENDENCIES =
      Set.of(
          "DEPENDS_ON",
          "CALLS",
          "READS_FROM",
          "WRITES_TO",
          "USES_MODEL",
          "USES_DATASET",
          "USES_IDENTITY",
          "USES_KEY",
          "USES_DNS",
          "RUNS_ON");

  GraphService(Config cfg, Database db, GraphStore store) {
    this.cfg = cfg;
    this.db = db;
    this.store = store;
  }

  ObjectNode context(Connection c, ObjectNode claims, String rid) throws SQLException {
    String raw =
        Admin.scalar(
            c,
            "SELECT uacrf_app.graph_context(?,?,?,?,?,?)",
            cfg.tenant(),
            cfg.environment(),
            claims.path("iss").asText(),
            claims.path("sub").asText(),
            claims.path("iat").asLong(),
            rid);
    return (ObjectNode) Json.read(raw);
  }

  ObjectNode reply(ObjectNode claims, String rid, String operation, ObjectNode params) {
    try (var c = db.connect()) {
      c.setAutoCommit(false);
      try {
        Admin.execute(c, "SET LOCAL statement_timeout='20s'");
        Admin.execute(c, "SET LOCAL lock_timeout='5s'");
        ObjectNode ctx = context(c, claims, rid);
        boolean usable = ctx.path("eligible").asBoolean() && ctx.path("projected").asBoolean();
        if (!usable && !operation.equals("graph-revision"))
          throw new Problem(409, "Graph is not accepted, current and projected");
        ObjectNode graph = null;
        if (usable) {
          JsonNode expected = Json.read(ctx.path("canonical_text").asText());
          graph = store.verified(expected, ctx.path("digest").asText());
        }
        ObjectNode result;
        if (operation.equals("graph-revision")) {
          result = ctx.deepCopy();
          for (String field :
              List.of("eligible", "projected", "node_count", "edge_count", "canonical_text"))
            result.remove(field);
          result
              .put("state", usable ? "AVAILABLE" : "PARTIAL")
              .put("runtime_verification", "NOT_ESTABLISHED");
          result.putArray("reasons");
          if (!usable)
            ((ArrayNode) result.get("reasons"))
                .add("Accepted graph evidence or completed projection unavailable");
        } else {
          result =
              Json.M.createObjectNode()
                  .put("graph_revision_id", rid)
                  .put("digest", ctx.path("digest").asText())
                  .put("source_watermark", ctx.path("source_watermark").asLong())
                  .put("state", "AVAILABLE")
                  .put("runtime_verification", "NOT_ESTABLISHED");
          if (operation.equals("graph-dependencies")) {
            result.set("nodes", graph.path("nodes"));
            result.set("edges", graph.path("edges"));
            result.put("completeness", "ACCEPTED_REVISION_ONLY");
          } else if (operation.equals("graph-impact")) {
            String affected = params.path("affected_id").asText();
            requireNode(graph, affected);
            var closure = walk(graph, affected, true, params.path("max_depth").asInt(8));
            result.set("affected_nodes", Json.M.valueToTree(closure.ids));
            result.set("witnesses", Json.M.valueToTree(closure.witnesses));
            result
                .put("completeness", closure.truncated ? "INCOMPLETE" : "ACCEPTED_REVISION_ONLY")
                .put("decision", "DIAGNOSTIC_ONLY");
          } else if (operation.equals("graph-independence")) {
            String primary = params.path("primary_id").asText(),
                recovery = params.path("recovery_id").asText();
            requireNode(graph, primary);
            requireNode(graph, recovery);
            if (primary.equals(recovery))
              throw new Problem(422, "Distinct primary and recovery nodes required");
            var a = walk(graph, primary, false, params.path("max_depth").asInt(8));
            var b = walk(graph, recovery, false, params.path("max_depth").asInt(8));
            Set<String> left = new TreeSet<>(a.ids);
            left.add(primary);
            Set<String> right = new TreeSet<>(b.ids);
            right.add(recovery);
            left.retainAll(right);
            result.set("shared_nodes", Json.M.valueToTree(left));
            result.set("primary_witnesses", Json.M.valueToTree(a.witnesses));
            result.set("recovery_witnesses", Json.M.valueToTree(b.witnesses));
            result
                .put(
                    "completeness",
                    a.truncated || b.truncated ? "INCOMPLETE" : "ACCEPTED_REVISION_ONLY")
                .put(
                    "relationship",
                    left.isEmpty()
                        ? "NO_SHARED_DEPENDENCIES_OBSERVED"
                        : "SHARED_DEPENDENCIES_FOUND")
                .put("independence", "NOT_CERTIFIED");
          } else throw new Problem(404, "Unknown graph operation");
        }
        ObjectNode after = context(c, claims, rid);
        if (!after.path("digest").equals(ctx.path("digest"))
            || (usable
                && (!after.path("eligible").asBoolean() || !after.path("projected").asBoolean())))
          throw new Problem(409, "Graph evidence or projection changed during read");
        c.commit();
        return result;
      } catch (Exception ex) {
        c.rollback();
        throw ex;
      }
    } catch (Problem p) {
      throw p;
    } catch (SQLException ex) {
      String state = ex.getSQLState();
      if (state != null && state.matches("U0[0-9]{3}"))
        throw new Problem(
            Integer.parseInt(state.substring(2)),
            "Graph scope, identity or current evidence rejected");
      throw new Problem(503, "PostgreSQL graph boundary unavailable");
    } catch (Exception ex) {
      throw new Problem(503, "Neo4j graph projection unavailable or inconsistent");
    }
  }

  static void requireNode(JsonNode graph, String id) {
    for (var n : graph.path("nodes")) if (id.equals(n.path("canonical_id").asText())) return;
    throw new Problem(404, "Node unavailable in accepted graph");
  }

  record Closure(List<String> ids, List<Map<String, Object>> witnesses, boolean truncated) {}

  static Closure walk(JsonNode graph, String root, boolean reverse, int maxDepth) {
    Map<String, List<JsonNode>> adjacency = new TreeMap<>();
    for (var edge : graph.path("edges"))
      if (DEPENDENCIES.contains(edge.path("type").asText()))
        adjacency
            .computeIfAbsent(
                edge.path(reverse ? "target_id" : "source_id").asText(), k -> new ArrayList<>())
            .add(edge);
    record Step(String node, List<String> nodes, List<String> edges, int depth) {}
    var queue = new ArrayDeque<Step>();
    queue.add(new Step(root, List.of(root), List.of(), 0));
    Set<String> visited = new LinkedHashSet<>();
    visited.add(root);
    List<Map<String, Object>> witnesses = new ArrayList<>();
    boolean truncated = false;
    while (!queue.isEmpty()) {
      var step = queue.remove();
      for (var edge : adjacency.getOrDefault(step.node, List.of())) {
        String next = edge.path(reverse ? "source_id" : "target_id").asText();
        if (visited.contains(next)) continue;
        if (step.depth >= maxDepth || visited.size() >= 1000) {
          truncated = true;
          continue;
        }
        visited.add(next);
        var nodes = new ArrayList<>(step.nodes);
        nodes.add(next);
        var edges = new ArrayList<>(step.edges);
        edges.add(edge.path("relationship_id").asText());
        witnesses.add(Map.of("node_id", next, "node_path", nodes, "edge_path", edges));
        queue.add(new Step(next, nodes, edges, step.depth + 1));
      }
    }
    visited.remove(root);
    return new Closure(new ArrayList<>(visited), witnesses, truncated);
  }

  @Override
  public void close() {
    store.close();
  }
}
