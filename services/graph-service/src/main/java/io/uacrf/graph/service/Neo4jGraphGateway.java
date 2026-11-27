package io.uacrf.graph.service;

import io.uacrf.contracts.graph.Neo4jOntology;
import io.uacrf.graph.api.GraphDtos.ProjectEdgeInput;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exclusive Neo4j writer gateway for S06. Every Cypher write is strictly checked
 * against the canonical 58 node labels and 55 relationship types before execution.
 */
@Component
public class Neo4jGraphGateway {

    private final boolean enabled;
    private final String uri;
    private final String username;
    private final String password;
    private volatile Driver driver;

    public Neo4jGraphGateway(
            @Value("${uacrf.graph.neo4j.enabled:true}") boolean enabled,
            @Value("${uacrf.graph.neo4j.uri}") String uri,
            @Value("${uacrf.graph.neo4j.username}") String username,
            @Value("${uacrf.graph.neo4j.password}") String password) {
        this.enabled = enabled;
        this.uri = uri;
        this.username = username;
        this.password = password;
    }

    private Driver getOrCreateDriver() {
        if (driver == null) {
            synchronized (this) {
                if (driver == null) {
                    driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
                }
            }
        }
        return driver;
    }

    public boolean projectToNeo4j(UUID tenantId, UUID environmentId, List<ProjectEdgeInput> edges, String revisionDigest) {
        if (!enabled) {
            return false;
        }
        // Always validate entire batch against canonical 58-label / 55-relationship ontology first
        for (ProjectEdgeInput edge : edges) {
            Neo4jOntology.requireNodeLabel(edge.sourceLabel());
            Neo4jOntology.requireNodeLabel(edge.targetLabel());
            Neo4jOntology.requireRelationshipType(edge.relationshipType());
            Neo4jOntology.validatePropertyKeys(Set.of("importance", "edgeState", "observationSource", "ownerConfirmed"));
        }
        try (var session = getOrCreateDriver().session()) {
            session.executeWrite(tx -> {
                for (ProjectEdgeInput edge : edges) {
                    String srcLabel = Neo4jOntology.requireNodeLabel(edge.sourceLabel()).name();
                    String tgtLabel = Neo4jOntology.requireNodeLabel(edge.targetLabel()).name();
                    String relType = Neo4jOntology.requireRelationshipType(edge.relationshipType()).name();

                    String cypher = String.format("""
                            MERGE (s:%s {tenantId: $tenantId, environmentId: $envId, canonicalId: $srcId})
                            SET s.displayName = $srcDisplay, s.graphRevision = $rev
                            MERGE (t:%s {tenantId: $tenantId, environmentId: $envId, canonicalId: $tgtId})
                            SET t.displayName = $tgtDisplay, t.graphRevision = $rev
                            MERGE (s)-[r:%s {tenantId: $tenantId, environmentId: $envId}]->(t)
                            SET r.importance = $importance,
                                r.edgeState = $edgeState,
                                r.observationSource = $obsSource,
                                r.ownerConfirmed = $ownerConfirmed,
                                r.graphRevision = $rev
                            """, srcLabel, tgtLabel, relType);

                    tx.run(cypher, Values.parameters(
                            "tenantId", tenantId.toString(),
                            "envId", environmentId.toString(),
                            "srcId", edge.sourceCanonicalId(),
                            "srcDisplay", edge.sourceDisplay(),
                            "tgtId", edge.targetCanonicalId(),
                            "tgtDisplay", edge.targetDisplay(),
                            "importance", edge.importance(),
                            "edgeState", edge.edgeState(),
                            "obsSource", edge.observationSource(),
                            "ownerConfirmed", edge.ownerConfirmed(),
                            "rev", revisionDigest
                    )).consume();
                }
                return null;
            });
            return true;
        } catch (Exception ex) {
            // Neo4j container may be stopped in Postgres-only unit checks; Postgres projection + outbox remain durable
            return false;
        }
    }

    public long countProjectedNodesInNeo4j(UUID tenantId, UUID environmentId) {
        if (!enabled) {
            return 0L;
        }
        try (var session = getOrCreateDriver().session()) {
            return session.executeRead(tx -> {
                var res = tx.run(
                        "MATCH (n {tenantId: $tenantId, environmentId: $envId}) RETURN count(n) AS cnt",
                        Map.of("tenantId", tenantId.toString(), "envId", environmentId.toString())
                );
                return res.single().get("cnt").asLong();
            });
        } catch (Exception ex) {
            return -1L;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (driver != null) {
            driver.close();
        }
    }
}
