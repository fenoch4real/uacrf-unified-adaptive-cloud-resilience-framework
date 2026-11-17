package org.uacrf.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.uacrf.topology.GraphCommandWorker.EdgeValue;

class GraphCommandWorkerTests {
  @Test void classifiesReadEdgeAdditionsRemovalsAndSameSourceReplacements() {
    EdgeValue original=edge("agent","database-a");
    EdgeValue replacement=edge("agent","database-b");

    var addition=GraphCommandWorker.compareReadEdges(Set.of(),Set.of(original));
    assertEquals("widening",addition.classification());
    assertEquals(Set.of(original),addition.additions());

    var removal=GraphCommandWorker.compareReadEdges(Set.of(original),Set.of());
    assertEquals("narrowing",removal.classification());
    assertEquals(Set.of(original),removal.removals());

    var targetChange=GraphCommandWorker.compareReadEdges(Set.of(original),Set.of(replacement));
    assertEquals("target-changing",targetChange.classification());
    assertEquals(1,targetChange.targetChanges().size());
    assertEquals(original.asMap(),targetChange.targetChanges().getFirst().get("from"));
    assertEquals(replacement.asMap(),targetChange.targetChanges().getFirst().get("to"));
  }

  @Test void leavesMixedAndUnpairedReadDeltasIndeterminate() {
    EdgeValue oldA=edge("agent","database-a");
    EdgeValue oldB=edge("agent","database-b");
    EdgeValue newA=edge("agent","database-c");
    var mixed=GraphCommandWorker.compareReadEdges(Set.of(oldA,oldB),Set.of(newA));

    assertEquals("indeterminate",mixed.classification());
    assertTrue(mixed.mixed());
    assertEquals(Set.of(oldA,oldB),mixed.removals());
    assertEquals(Set.of(newA),mixed.additions());
    assertTrue(mixed.targetChanges().isEmpty());

    EdgeValue replacement=edge("other-agent","database-d");
    EdgeValue replacementTarget=edge("other-agent","database-e");
    var pairedAndUnpaired=GraphCommandWorker.compareReadEdges(
        Set.of(oldA,replacement),Set.of(newA,replacementTarget,edge("third-agent","database-f")));
    assertEquals("indeterminate",pairedAndUnpaired.classification());
    assertTrue(pairedAndUnpaired.mixed());
  }

  private static EdgeValue edge(String source,String target) {
    return new EdgeValue("agent_workload",source,"READS_FROM","shared_resource",target);
  }
}
