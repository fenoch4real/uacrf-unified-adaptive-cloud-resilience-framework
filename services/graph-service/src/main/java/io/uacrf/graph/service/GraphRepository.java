package io.uacrf.graph.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.graph.api.GraphDtos.GraphEdgeDto;
import io.uacrf.graph.api.GraphDtos.GraphNodeDto;
import io.uacrf.graph.api.GraphDtos.GraphRevisionDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GraphRepository {

    private final JdbcTemplate jdbc;

    public GraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GraphNodeDto> listNodes(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT node_id, canonical_id, node_label, display_name, health_state, provider_tag,
                       COALESCE(properties_json->>'subtitle', node_label) AS subtitle
                FROM graph.projected_node
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY display_name
                """, this::mapNode, tenantId, environmentId);
    }

    public List<GraphEdgeDto> listEdges(UUID tenantId, UUID environmentId, boolean includeUncertain) {
        return jdbc.query("""
                SELECT edge_id, source_canonical_id, relationship_type, target_canonical_id,
                       importance, edge_state, observation_source, owner_confirmed, last_observed_at
                FROM graph.projected_edge
                WHERE tenant_id = ? AND environment_id = ?
                  AND (? = TRUE OR edge_state <> 'UNCERTAIN')
                ORDER BY source_canonical_id, target_canonical_id
                """, this::mapEdge, tenantId, environmentId, includeUncertain);
    }

    public void upsertNode(UUID tenantId, UUID environmentId, String canonicalId,
                           String nodeLabel, String displayName, String subtitle) {
        String props = CanonicalJson.toJson(Map.of("subtitle", subtitle != null ? subtitle : nodeLabel));
        jdbc.update("""
                INSERT INTO graph.projected_node (
                    node_id, tenant_id, environment_id, canonical_id, node_label, display_name,
                    health_state, provider_tag, properties_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'UNKNOWN', 'SHARED', ?::jsonb, now())
                ON CONFLICT (tenant_id, environment_id, canonical_id)
                DO UPDATE SET node_label = EXCLUDED.node_label,
                              display_name = EXCLUDED.display_name,
                              updated_at = now()
                """, UUID.randomUUID(), tenantId, environmentId, canonicalId, nodeLabel, displayName, props);
    }

    public void upsertEdge(UUID tenantId, UUID environmentId, String sourceCanonicalId, String relationshipType,
                           String targetCanonicalId, String importance, String edgeState,
                           String observationSource, boolean ownerConfirmed) {
        jdbc.update("""
                INSERT INTO graph.projected_edge (
                    edge_id, tenant_id, environment_id, source_canonical_id, relationship_type,
                    target_canonical_id, importance, edge_state, observation_source, owner_confirmed, last_observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id, environment_id, source_canonical_id, relationship_type, target_canonical_id)
                DO UPDATE SET importance = EXCLUDED.importance,
                              edge_state = EXCLUDED.edge_state,
                              observation_source = EXCLUDED.observation_source,
                              owner_confirmed = EXCLUDED.owner_confirmed,
                              last_observed_at = now()
                """,
                UUID.randomUUID(), tenantId, environmentId, sourceCanonicalId, relationshipType,
                targetCanonicalId, importance, edgeState, observationSource, ownerConfirmed);
    }

    public int confirmEdge(UUID tenantId, UUID environmentId, UUID edgeId) {
        return jdbc.update("""
                UPDATE graph.projected_edge
                SET owner_confirmed = TRUE, edge_state = 'CONFIRMED', last_observed_at = now()
                WHERE tenant_id = ? AND environment_id = ? AND edge_id = ?
                """, tenantId, environmentId, edgeId);
    }

    public GraphRevisionDto insertRevision(UUID tenantId, UUID environmentId, String digest, int nodeCount, int edgeCount) {
        UUID revId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO graph.graph_revision (revision_id, tenant_id, environment_id, revision_digest, node_count, edge_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, revId, tenantId, environmentId, digest, nodeCount, edgeCount, Timestamp.from(now));
        return new GraphRevisionDto(revId, tenantId, environmentId, digest, nodeCount, edgeCount, now);
    }

    public Optional<GraphRevisionDto> latestRevision(UUID tenantId, UUID environmentId) {
        List<GraphRevisionDto> list = jdbc.query("""
                SELECT revision_id, tenant_id, environment_id, revision_digest, node_count, edge_count, created_at
                FROM graph.graph_revision
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, rowNum) -> new GraphRevisionDto(
                rs.getObject("revision_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("revision_digest"),
                rs.getInt("node_count"),
                rs.getInt("edge_count"),
                rs.getTimestamp("created_at").toInstant()
        ), tenantId, environmentId);
        return list.stream().findFirst();
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private GraphNodeDto mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new GraphNodeDto(
                rs.getObject("node_id", UUID.class),
                rs.getString("canonical_id"),
                rs.getString("node_label"),
                rs.getString("display_name"),
                rs.getString("health_state"),
                rs.getString("provider_tag"),
                rs.getString("subtitle")
        );
    }

    private GraphEdgeDto mapEdge(ResultSet rs, int rowNum) throws SQLException {
        return new GraphEdgeDto(
                rs.getObject("edge_id", UUID.class),
                rs.getString("source_canonical_id"),
                rs.getString("relationship_type"),
                rs.getString("target_canonical_id"),
                rs.getString("importance"),
                rs.getString("edge_state"),
                rs.getString("observation_source"),
                rs.getBoolean("owner_confirmed"),
                rs.getTimestamp("last_observed_at").toInstant()
        );
    }
}
