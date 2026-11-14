package org.uacrf.topology;

import java.util.List;

final class TopologyResponses {
  private TopologyResponses() {}
  record Error(String code, String message, String requestId) {}
  record Proposal(String id, String sourceType, String sourceId, String relationshipType,
      String targetType, String targetId, String provenanceKind, String sourceSystem,
      String sourceRevision, String verifiedAt, String status, String proposer, String reviewer, long revision) {}
  record Page<T>(List<T> items, String nextCursor) {}
  record Node(String entityType, String entityId) {}
  record Edge(String sourceType, String sourceId, String relationshipType, String targetType, String targetId, String proposalId) {}
  record Graph(String id, String scope, long revisionNumber, String digest, String acceptedBy, String acceptedAt, List<Node> nodes, List<Edge> edges) {}
  record Subgraph(String graphId, Node root, int depth, List<Node> nodes, List<Edge> edges) {}
  record Path(Node entity, String classification, List<Node> nodes) {}
  record Impact(String graphId, Node root, List<Path> paths, List<Node> sharedModels, List<Node> identities, List<Node> cloudResources, List<Node> regions, List<Node> failureDomains) {}
  record Receipt(String id, String scope, long revision, String digest, String status, String projectionStatus, boolean eligibility) {}
  record Reconciliation(String graphId, String projectionStatus, String expectedDigest, String actualDigest, boolean contentIntegrityValid, List<Node> missingNodes, List<Node> extraNodes, List<Edge> missingEdges, List<Edge> extraEdges) {}
}
