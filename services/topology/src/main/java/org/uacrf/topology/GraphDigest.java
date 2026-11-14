package org.uacrf.topology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import org.uacrf.topology.TopologyResponses.Edge;
import org.uacrf.topology.TopologyResponses.Node;

final class GraphDigest {
  private GraphDigest() {}

  static String digest(Collection<Node> nodes, Collection<Edge> edges) {
    TreeSet<Node> sortedNodes = new TreeSet<>((a, b) -> compare(List.of(a.entityType(), a.entityId()), List.of(b.entityType(), b.entityId())));
    sortedNodes.addAll(nodes);
    TreeSet<Edge> sortedEdges = new TreeSet<>((a, b) -> compare(
        List.of(a.sourceType(), a.sourceId(), a.relationshipType(), a.targetType(), a.targetId()),
        List.of(b.sourceType(), b.sourceId(), b.relationshipType(), b.targetType(), b.targetId())));
    sortedEdges.addAll(edges);
    StringBuilder canonical = new StringBuilder();
    for (Node node : sortedNodes) canonical.append("N").append(part(node.entityType())).append(part(node.entityId()));
    for (Edge edge : sortedEdges) canonical.append("E").append(part(edge.sourceType())).append(part(edge.sourceId()))
        .append(part(edge.relationshipType())).append(part(edge.targetType())).append(part(edge.targetId()));
    return sha256(canonical.toString());
  }

  static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  private static String part(String value) { return value.length() + ":" + value; }
  private static int compare(List<String> left, List<String> right) {
    for (int i = 0; i < left.size(); i++) {
      int compared = left.get(i).compareTo(right.get(i));
      if (compared != 0) return compared;
    }
    return 0;
  }
}
