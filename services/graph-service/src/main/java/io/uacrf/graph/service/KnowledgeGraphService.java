package io.uacrf.graph.service;

import io.uacrf.contracts.graph.Neo4jOntology;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.graph.api.GraphDomainException;
import io.uacrf.graph.api.GraphDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class KnowledgeGraphService {

    private static final String NON_CAUSATION_NOTICE =
            "Dependency shows reliance. It does not prove the cause of this incident.";

    private final GraphRepository repository;
    private final Neo4jGraphGateway neo4jGateway;

    public KnowledgeGraphService(GraphRepository repository, Neo4jGraphGateway neo4jGateway) {
        this.repository = repository;
        this.neo4jGateway = neo4jGateway;
    }

    public GraphRevisionDto currentRevision(UUID tenantId, UUID environmentId) {
        return repository.latestRevision(tenantId, environmentId)
                .orElseThrow(() -> new GraphDomainException(
                        HttpStatus.NOT_FOUND, "GRAPH_REVISION_NOT_FOUND", "No graph revision exists for tenant/environment"));
    }

    public DependencyGraphResponse getBoundedDependencies(UUID tenantId, UUID environmentId,
                                                        String focusCanonicalId, int depth, boolean includeUncertain) {
        int clampedDepth = Math.max(1, Math.min(3, depth));
        List<GraphNodeDto> allNodes = repository.listNodes(tenantId, environmentId);
        List<GraphEdgeDto> allEdges = repository.listEdges(tenantId, environmentId, includeUncertain);

        // Bounded BFS traversal from focusCanonicalId (or include full journey-service view if focus is omitted)
        Set<String> visited = new LinkedHashSet<>();
        if (focusCanonicalId == null || focusCanonicalId.isBlank()) {
            allNodes.stream()
                    .filter(n -> !Set.of("ModelEndpoint", "ModelVersion", "FeatureStore", "ModelFallback").contains(n.nodeLabel()))
                    .forEach(n -> visited.add(n.canonicalId()));
        } else {
            visited.add(focusCanonicalId);
            Set<String> frontier = new LinkedHashSet<>(List.of(focusCanonicalId));
            for (int hop = 0; hop < clampedDepth && !frontier.isEmpty(); hop++) {
                Set<String> next = new LinkedHashSet<>();
                for (GraphEdgeDto e : allEdges) {
                    if (frontier.contains(e.sourceCanonicalId()) && !visited.contains(e.targetCanonicalId())) {
                        visited.add(e.targetCanonicalId());
                        next.add(e.targetCanonicalId());
                    }
                    if (frontier.contains(e.targetCanonicalId()) && !visited.contains(e.sourceCanonicalId())) {
                        visited.add(e.sourceCanonicalId());
                        next.add(e.sourceCanonicalId());
                    }
                }
                frontier = next;
            }
        }

        List<GraphNodeDto> filteredNodes = allNodes.stream()
                .filter(n -> visited.contains(n.canonicalId()))
                .toList();
        List<GraphEdgeDto> filteredEdges = allEdges.stream()
                .filter(e -> visited.contains(e.sourceCanonicalId()) && visited.contains(e.targetCanonicalId()))
                .toList();

        String rev = repository.latestRevision(tenantId, environmentId)
                .map(GraphRevisionDto::revisionDigest)
                .orElse("rev-initial");

        return new DependencyGraphResponse(
                tenantId, environmentId,
                focusCanonicalId != null ? focusCanonicalId : "ALL_SERVICES",
                clampedDepth, includeUncertain, rev, NON_CAUSATION_NOTICE,
                filteredNodes, filteredEdges
        );
    }

    public DependencyGraphResponse getAiLineage(UUID tenantId, UUID environmentId, String serviceCanonicalId) {
        String focus = serviceCanonicalId != null && !serviceCanonicalId.isBlank()
                ? serviceCanonicalId : "urn:uacrf:svc:payment";
        List<GraphNodeDto> allNodes = repository.listNodes(tenantId, environmentId);
        List<GraphEdgeDto> allEdges = repository.listEdges(tenantId, environmentId, true);

        Set<String> aiRels = Set.of("CALLS", "SERVES_MODEL", "USES_FEATURES_FROM", "FALLBACK_TO", "USES_MODEL");
        List<GraphEdgeDto> aiEdges = allEdges.stream()
                .filter(e -> aiRels.contains(e.relationshipType()))
                .toList();

        Set<String> participating = new LinkedHashSet<>();
        participating.add(focus);
        aiEdges.forEach(e -> {
            participating.add(e.sourceCanonicalId());
            participating.add(e.targetCanonicalId());
        });

        List<GraphNodeDto> aiNodes = allNodes.stream()
                .filter(n -> participating.contains(n.canonicalId()))
                .toList();

        String rev = repository.latestRevision(tenantId, environmentId)
                .map(GraphRevisionDto::revisionDigest)
                .orElse("rev-initial");

        return new DependencyGraphResponse(
                tenantId, environmentId, focus, 2, true, rev,
                "AI availability, model quality, subgroup safety, and fallback readiness require separate evidence.",
                aiNodes, aiEdges
        );
    }

    @Transactional
    public GraphRevisionDto projectEdges(ProjectEdgesRequest req) {
        for (ProjectEdgeInput edge : req.edges()) {
            Neo4jOntology.requireNodeLabel(edge.sourceLabel());
            Neo4jOntology.requireNodeLabel(edge.targetLabel());
            Neo4jOntology.requireRelationshipType(edge.relationshipType());
            repository.upsertNode(req.tenantId(), req.environmentId(), edge.sourceCanonicalId(),
                    edge.sourceLabel(), edge.sourceDisplay(), edge.sourceLabel());
            repository.upsertNode(req.tenantId(), req.environmentId(), edge.targetCanonicalId(),
                    edge.targetLabel(), edge.targetDisplay(), edge.targetLabel());
            repository.upsertEdge(req.tenantId(), req.environmentId(), edge.sourceCanonicalId(),
                    edge.relationshipType(), edge.targetCanonicalId(), edge.importance(),
                    edge.edgeState(), edge.observationSource(), edge.ownerConfirmed());
        }

        List<GraphNodeDto> nodes = repository.listNodes(req.tenantId(), req.environmentId());
        List<GraphEdgeDto> edges = repository.listEdges(req.tenantId(), req.environmentId(), true);
        String digest = "rev-" + CanonicalJson.digestObject(Map.of(
                "nodes", nodes.stream().map(GraphNodeDto::canonicalId).toList(),
                "edges", edges.stream().map(e -> e.sourceCanonicalId() + "->" + e.relationshipType() + "->" + e.targetCanonicalId() + ":" + e.edgeState()).toList()
        )).substring(0, 16);

        neo4jGateway.projectToNeo4j(req.tenantId(), req.environmentId(), req.edges(), digest);

        GraphRevisionDto revision = repository.insertRevision(req.tenantId(), req.environmentId(), digest, nodes.size(), edges.size());
        repository.insertOutbox(
                req.tenantId(), req.environmentId(),
                "graph.revision.published", "GraphRevision", revision.revisionId().toString(),
                UUID.randomUUID(), CanonicalJson.toJson(revision)
        );
        return revision;
    }

    @Transactional
    public void confirmEdge(UUID tenantId, UUID environmentId, UUID edgeId) {
        int updated = repository.confirmEdge(tenantId, environmentId, edgeId);
        if (updated == 0) {
            throw new GraphDomainException(HttpStatus.NOT_FOUND, "EDGE_NOT_FOUND", "Edge not found: " + edgeId);
        }
    }
}
