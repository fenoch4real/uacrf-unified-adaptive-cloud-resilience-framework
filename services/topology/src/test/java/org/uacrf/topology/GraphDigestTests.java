package org.uacrf.topology;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.uacrf.topology.TopologyResponses.Edge;
import org.uacrf.topology.TopologyResponses.Node;

class GraphDigestTests {
  @Test void provenanceChangesNewDigestButNotHistoricalDigest() {
    var nodes=List.of(new Node("agent_workload","a"),new Node("agent_workload","b"));
    var first=List.of(new Edge("agent_workload","a","DEPENDS_ON","agent_workload","b","p1"));
    var second=List.of(new Edge("agent_workload","a","DEPENDS_ON","agent_workload","b","p2"));
    assertNotEquals(GraphDigest.digest(nodes,first),GraphDigest.digest(nodes,second));
    assertEquals(GraphDigest.digest(nodes,first,1),GraphDigest.digest(nodes,second,1));
    assertNotEquals(GraphDigest.digest(nodes,first),GraphDigest.digest(nodes,first,1));
  }
}
