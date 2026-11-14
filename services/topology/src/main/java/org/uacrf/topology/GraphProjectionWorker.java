package org.uacrf.topology;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.uacrf.topology.GraphProjectionStore.GraphData;
import org.uacrf.topology.GraphProjectionStore.Snapshot;
import org.uacrf.topology.TopologyResponses.Edge;
import org.uacrf.topology.TopologyResponses.Node;

@Component
@ConditionalOnProperty(name = "uacrf.topology.projection.enabled", havingValue = "true")
class GraphProjectionWorker {
  private static final Logger LOG = LoggerFactory.getLogger(GraphProjectionWorker.class);
  private static final String SCHEMA = "uacrf_topology.";
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final GraphProjectionStore store;
  private final ObjectMapper json;
  private final String owner;
  private final long leaseMillis;
  private final long retryBaseMillis;
  private final int batchSize;

  GraphProjectionWorker(JdbcTemplate db, TransactionTemplate transactions, GraphProjectionStore store, ObjectMapper json,
      @Value("${uacrf.topology.projection.owner:}") String configuredOwner,
      @Value("${uacrf.topology.projection.lease-ms:60000}") long leaseMillis,
      @Value("${uacrf.topology.projection.retry-base-ms:1000}") long retryBaseMillis,
      @Value("${uacrf.topology.projection.batch-size:10}") int batchSize) {
    this.db = db; this.transactions = transactions; this.store = store; this.json = json;
    this.owner = configuredOwner.isBlank() ? UUID.randomUUID().toString() : configuredOwner;
    this.leaseMillis = leaseMillis; this.retryBaseMillis = retryBaseMillis; this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${uacrf.topology.projection.interval-ms:5000}")
  void scheduled() {
    for (int i = 0; i < batchSize; i++) {
      try { if (!projectNext()) return; }
      catch (RuntimeException error) { LOG.warn("S06 projection cycle failed; row remains retryable: {}", error.toString()); return; }
    }
  }

  boolean projectNext() {
    Claim claim = claim();
    if (claim == null) return false;
    try {
      Snapshot snapshot = snapshot(claim);
      store.write(snapshot);
      GraphData actual = store.read(snapshot.tenant(), snapshot.environment(), snapshot.id());
      String actualDigest = GraphDigest.digest(actual.nodes(), actual.edges());
      if (!actual.contentIntegrityValid() || actual.nodes().size() != snapshot.nodes().size()
          || actual.edges().size() != snapshot.edges().size() || !snapshot.digest().equals(actualDigest)) {
        finishDrift(claim, actual.nodes().size(), actual.edges().size(), actualDigest);
      } else {
        finishProjected(claim, snapshot, actual.nodes().size(), actual.edges().size(), actualDigest);
      }
    } catch (RuntimeException error) {
      markFailed(claim, error);
      LOG.warn("S06 graph projection failed for revision {}: {}", claim.id(), error.toString());
    }
    return true;
  }

  Claim claim() { return transactions.execute(status -> claimLocked()); }

  private Claim claimLocked() {
    List<Map<String, Object>> rows = db.queryForList("SELECT tenant_id, environment_id, graph_id, attempt_count FROM " + SCHEMA + "graph_projections WHERE status = 'PENDING' OR (status = 'FAILED' AND next_attempt_at <= now()) OR (status = 'PROJECTING' AND lease_expires_at <= now()) ORDER BY created_at, tenant_id, environment_id, graph_id LIMIT 1 FOR UPDATE SKIP LOCKED");
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.getFirst();
    String tenant = (String) row.get("tenant_id"), environment = (String) row.get("environment_id"), id = (String) row.get("graph_id");
    int changed = db.update("UPDATE " + SCHEMA + "graph_projections SET status='PROJECTING', lease_owner=?, lease_expires_at=clock_timestamp() + (? * interval '1 millisecond'), attempt_count=attempt_count+1, updated_at=now() WHERE tenant_id=? AND environment_id=? AND graph_id=?", owner, leaseMillis, tenant, environment, id);
    return changed == 1 ? new Claim(tenant, environment, id, ((Number) row.get("attempt_count")).intValue() + 1) : null;
  }

  private Snapshot snapshot(Claim claim) {
    Map<String, Object> revision = db.queryForMap("SELECT revision_number, digest FROM " + SCHEMA + "graph_revisions WHERE tenant_id=? AND environment_id=? AND id=?", claim.tenant(), claim.environment(), claim.id());
    List<Node> nodes = db.query("SELECT entity_type, entity_id FROM " + SCHEMA + "graph_nodes WHERE tenant_id=? AND environment_id=? AND graph_id=?", (rs, n) -> new Node(rs.getString(1), rs.getString(2)), claim.tenant(), claim.environment(), claim.id());
    List<Edge> edges = db.query("SELECT source_type, source_id, relationship_type, target_type, target_id, proposal_id FROM " + SCHEMA + "graph_edges WHERE tenant_id=? AND environment_id=? AND graph_id=?", (rs, n) -> new Edge(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), claim.tenant(), claim.environment(), claim.id());
    return new Snapshot(claim.tenant(), claim.environment(), claim.id(), ((Number) revision.get("revision_number")).longValue(), (String) revision.get("digest"), nodes, edges);
  }

  private void finishProjected(Claim claim, Snapshot graph, int nodeCount, int edgeCount, String digest) {
    transactions.executeWithoutResult(status -> {
    int changed = db.update("UPDATE " + SCHEMA + "graph_projections SET status='PROJECTED', lease_owner=NULL, lease_expires_at=NULL, projected_node_count=?, projected_edge_count=?, verified_digest=?, last_error=NULL, next_attempt_at=now(), updated_at=now() WHERE tenant_id=? AND environment_id=? AND graph_id=? AND status='PROJECTING' AND lease_owner=? AND lease_expires_at > clock_timestamp()", nodeCount, edgeCount, digest, claim.tenant(), claim.environment(), claim.id(), owner);
    if (changed == 1) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("owned_resource_id", graph.id()); payload.put("owned_revision", graph.revision()); payload.put("owned_digest", graph.digest());
      payload.put("minimal_outcome_metadata", Map.of("projection_status", "PROJECTED", "projected_node_count", nodeCount, "projected_edge_count", edgeCount, "verified_digest", digest));
      String body = json.writeValueAsString(payload);
      db.update("INSERT INTO " + SCHEMA + "outbox_event (event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,event_type,payload,payload_digest) VALUES (?,?,?,'GRAPH',?,?,?,?::jsonb,?)", UUID.randomUUID(), claim.tenant(), claim.environment(), graph.id(), graph.revision(), "uacrf.s06.graph.projected.v1", body, GraphDigest.sha256(body));
    }
    });
  }

  private void finishDrift(Claim claim, int nodeCount, int edgeCount, String digest) {
    transactions.executeWithoutResult(status -> db.update("UPDATE " + SCHEMA + "graph_projections SET status='DRIFTED', lease_owner=NULL, lease_expires_at=NULL, projected_node_count=?, projected_edge_count=?, verified_digest=?, last_error='Projected graph content did not match the accepted revision digest', updated_at=now() WHERE tenant_id=? AND environment_id=? AND graph_id=? AND status='PROJECTING' AND lease_owner=? AND lease_expires_at > clock_timestamp()", nodeCount, edgeCount, digest, claim.tenant(), claim.environment(), claim.id(), owner));
  }

  private void markFailed(Claim claim, RuntimeException error) {
    long exponent = Math.min(20, Math.max(0, claim.attempt() - 1));
    long delay = Math.min(Duration.ofMinutes(5).toMillis(), retryBaseMillis * (1L << exponent));
    String rawMessage = error.toString();
    final String message = rawMessage.length() > 1000 ? rawMessage.substring(0, 1000) : rawMessage;
    transactions.executeWithoutResult(status -> db.update("UPDATE " + SCHEMA + "graph_projections SET status='FAILED', lease_owner=NULL, lease_expires_at=NULL, last_error=?, next_attempt_at=now() + (? * interval '1 millisecond'), updated_at=now() WHERE tenant_id=? AND environment_id=? AND graph_id=? AND status='PROJECTING' AND lease_owner=? AND lease_expires_at > clock_timestamp()", message, delay, claim.tenant(), claim.environment(), claim.id(), owner));
  }

  private record Claim(String tenant, String environment, String id, int attempt) {}
}
