package org.uacrf.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.uacrf.topology.GraphCommandWorker.EdgeValue;

class GraphCommandWorkerTests {
  @Test void reconcilesExactMembershipWithAllGraphWorkloadsUsingCountsOnly() {
    var report=GraphCommandWorker.inventoryReconciliation(
        Map.of("owned_agent_workload_ids",List.of("matched","missing")),
        List.of(new TopologyResponses.Node("agent_workload","matched"),
            new TopologyResponses.Node("agent_workload","outside-agent"),
            new TopologyResponses.Node("service","missing")));

    assertEquals("COMPARABLE",report.get("status"));
    assertEquals(2,report.get("service_membership_count"));
    assertEquals(2,report.get("graph_workload_node_count"));
    assertEquals(1,report.get("matched_membership_count"));
    assertEquals(1,report.get("catalog_membership_missing_from_graph_count"));
    assertEquals(1,report.get("graph_workload_nodes_outside_service_membership_count"));
    assertEquals(true,report.get("advisory_only"));
    assertTrue(((String)report.get("limitation")).contains("not a complete provider inventory"));
    assertTrue(((String)report.get("limitation")).contains("not classified as defects"));
    assertTrue(((String)report.get("limitation")).contains("no freshness or completeness claim"));
    assertTrue(report.values().stream().noneMatch(value->value instanceof List<?>));
    assertTrue(!report.toString().contains("outside-agent"));
  }

  @Test void distinguishesUnknownMembershipFromKnownEmptyMembership() {
    var nodes=List.of(new TopologyResponses.Node("agent_workload","graph-only"));
    var unknown=GraphCommandWorker.inventoryReconciliation(Map.of(),nodes);
    assertEquals("INDETERMINATE",unknown.get("status"));
    assertEquals(1,unknown.get("graph_workload_node_count"));
    for (String key:List.of("service_membership_count","matched_membership_count",
        "catalog_membership_missing_from_graph_count",
        "graph_workload_nodes_outside_service_membership_count"))
      assertNull(unknown.get(key));

    var empty=GraphCommandWorker.inventoryReconciliation(
        Map.of("owned_agent_workload_ids",List.of()),nodes);
    assertEquals("COMPARABLE",empty.get("status"));
    assertEquals(0,empty.get("service_membership_count"));
    assertEquals(0,empty.get("matched_membership_count"));
    assertEquals(0,empty.get("catalog_membership_missing_from_graph_count"));
    assertEquals(1,empty.get("graph_workload_nodes_outside_service_membership_count"));
  }

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
