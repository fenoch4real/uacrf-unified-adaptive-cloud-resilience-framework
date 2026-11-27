package io.uacrf.graph.service;

import io.uacrf.graph.api.GraphDtos.GraphNodeDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Anti-Tamper Graph Reconciliation Scanner (FR-GPH-007):
 * Compares PostgreSQL authoritative graph inventory against live Neo4j node counts to detect
 * out-of-band graph mutations (PROJECTED vs. DRIFTED).
 */
@Service
public class GraphDriftReconciler {

    private final GraphRepository repository;
    private final Neo4jGraphGateway neo4jGateway;

    public GraphDriftReconciler(GraphRepository repository, Neo4jGraphGateway neo4jGateway) {
        this.repository = repository;
        this.neo4jGateway = neo4jGateway;
    }

    public ReconciliationReport reconcile(UUID tenantId, UUID environmentId) {
        List<GraphNodeDto> pgNodes = repository.listNodes(tenantId, environmentId);
        long neoCount = neo4jGateway.countProjectedNodesInNeo4j(tenantId, environmentId);
        String rev = repository.latestRevision(tenantId, environmentId)
                .map(r -> r.revisionDigest())
                .orElse("rev-unknown");

        if (neoCount < 0) {
            return new ReconciliationReport(tenantId, environmentId, rev, pgNodes.size(), neoCount,
                    "UNKNOWN", "Neo4j unreachable during drift reconciliation scan.", Instant.now());
        }

        boolean synchronizedState = neoCount == pgNodes.size();
        String status = synchronizedState ? "PROJECTED" : "DRIFTED";
        String message = synchronizedState
                ? "Neo4j live topology matches PostgreSQL canonical projection manifest."
                : "DRIFT DETECTED: PostgreSQL expects " + pgNodes.size() + " nodes, Neo4j has " + neoCount + ".";

        return new ReconciliationReport(tenantId, environmentId, rev, pgNodes.size(), neoCount, status, message, Instant.now());
    }

    public record ReconciliationReport(
            UUID tenantId,
            UUID environmentId,
            String revisionDigest,
            int postgresqlNodeCount,
            long neo4jNodeCount,
            String status,
            String message,
            Instant checkedAt
    ) {
    }
}
