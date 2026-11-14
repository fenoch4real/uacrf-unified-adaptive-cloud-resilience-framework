package org.uacrf.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.springframework.stereotype.Component;
import org.uacrf.topology.TopologyResponses.Edge;
import org.uacrf.topology.TopologyResponses.Node;

@Component
class GraphProjectionStore {
  private static final String NODE_LABEL = "UacrfTopologyNode";
  private static final String EDGE_TYPE = "UACRF_TOPOLOGY_EDGE";
  private final Driver driver;

  GraphProjectionStore(Driver driver) { this.driver = driver; }

  void write(Snapshot graph) {
    try (Session session = driver.session()) {
      session.executeWrite(tx -> {
        tx.run("UNWIND $nodes AS row MERGE (n:" + NODE_LABEL + " {tenant_id: row.tenant_id, environment_id: row.environment_id, revision_id: row.revision_id, entity_type: row.entity_type, entity_id: row.entity_id}) SET n.revision_number = row.revision_number",
            Map.of("nodes", graph.nodes().stream().map(node -> nodeMap(graph, node)).toList())).consume();
        tx.run("UNWIND $edges AS row MATCH (s:" + NODE_LABEL + " {tenant_id: row.tenant_id, environment_id: row.environment_id, revision_id: row.revision_id, entity_type: row.source_type, entity_id: row.source_id}), (t:" + NODE_LABEL + " {tenant_id: row.tenant_id, environment_id: row.environment_id, revision_id: row.revision_id, entity_type: row.target_type, entity_id: row.target_id}) MERGE (s)-[e:" + EDGE_TYPE + " {tenant_id: row.tenant_id, environment_id: row.environment_id, revision_id: row.revision_id, source_type: row.source_type, source_id: row.source_id, relationship_type: row.relationship_type, target_type: row.target_type, target_id: row.target_id}]->(t) SET e.revision_number = row.revision_number, e.proposal_id = row.proposal_id",
            Map.of("edges", graph.edges().stream().map(edge -> edgeMap(graph, edge)).toList())).consume();
        return null;
      });
    }
  }

  GraphData read(String tenant, String environment, String revisionId) {
    try (Session session = driver.session()) {
      return session.executeRead(tx -> read(tx, tenant, environment, revisionId));
    }
  }

  private GraphData read(TransactionContext tx, String tenant, String environment, String revisionId) {
    Map<String, Object> scope = Map.of("tenant", tenant, "environment", environment, "revision", revisionId);
    List<Node> nodes = new ArrayList<>();
    boolean contentIntegrityValid = true;
    for (Record row : tx.run("MATCH (n:" + NODE_LABEL + " {tenant_id: $tenant, environment_id: $environment, revision_id: $revision}) RETURN n.entity_type AS entity_type, n.entity_id AS entity_id ORDER BY entity_type, entity_id", scope).list()) {
      String entityType = row.get("entity_type").asString(null), entityId = row.get("entity_id").asString(null);
      if (entityType == null || entityId == null) contentIntegrityValid = false;
      else nodes.add(new Node(entityType, entityId));
    }
    List<Edge> edges = new ArrayList<>();
    String edgeQuery = "MATCH (s)-[e:" + EDGE_TYPE + " {tenant_id: $tenant, environment_id: $environment, revision_id: $revision}]->(t) "
        + "RETURN e.source_type AS source_type, e.source_id AS source_id, e.relationship_type AS relationship_type, "
        + "e.target_type AS target_type, e.target_id AS target_id, e.proposal_id AS proposal_id, "
        + "coalesce((s.tenant_id = $tenant AND s.environment_id = $environment AND s.revision_id = $revision "
        + "AND s.entity_type = e.source_type AND s.entity_id = e.source_id AND t.tenant_id = $tenant "
        + "AND t.environment_id = $environment AND t.revision_id = $revision AND t.entity_type = e.target_type "
        + "AND t.entity_id = e.target_id), false) AS endpoints_valid "
        + "ORDER BY source_type, source_id, relationship_type, target_type, target_id";
    for (Record row : tx.run(edgeQuery, scope).list()) {
      contentIntegrityValid &= row.get("endpoints_valid").asBoolean();
      String sourceType = row.get("source_type").asString(null), sourceId = row.get("source_id").asString(null);
      String relationshipType = row.get("relationship_type").asString(null), targetType = row.get("target_type").asString(null), targetId = row.get("target_id").asString(null);
      if (sourceType == null || sourceId == null || relationshipType == null || targetType == null || targetId == null) {
        contentIntegrityValid = false;
      } else {
        edges.add(new Edge(sourceType, sourceId, relationshipType, targetType, targetId, row.get("proposal_id").asString(null)));
      }
    }
    return new GraphData(List.copyOf(nodes), List.copyOf(edges), contentIntegrityValid);
  }

  private static Map<String, Object> nodeMap(Snapshot graph, Node node) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("tenant_id", graph.tenant()); value.put("environment_id", graph.environment()); value.put("revision_id", graph.id());
    value.put("revision_number", graph.revision()); value.put("entity_type", node.entityType()); value.put("entity_id", node.entityId());
    return value;
  }

  private static Map<String, Object> edgeMap(Snapshot graph, Edge edge) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("tenant_id", graph.tenant()); value.put("environment_id", graph.environment()); value.put("revision_id", graph.id());
    value.put("revision_number", graph.revision()); value.put("source_type", edge.sourceType()); value.put("source_id", edge.sourceId());
    value.put("relationship_type", edge.relationshipType()); value.put("target_type", edge.targetType()); value.put("target_id", edge.targetId()); value.put("proposal_id", edge.proposalId());
    return value;
  }

  record Snapshot(String tenant, String environment, String id, long revision, String digest, List<Node> nodes, List<Edge> edges) {}
  record GraphData(List<Node> nodes, List<Edge> edges, boolean contentIntegrityValid) {}
}
