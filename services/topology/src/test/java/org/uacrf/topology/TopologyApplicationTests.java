package org.uacrf.topology;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.neo4j.Neo4jContainer;
import org.uacrf.security.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TopologyApplicationTests {
  static final String KEY_ID="topology-test-key";
  static final KeyPair KEYS=keys();
  static final JwtEncoder ENCODER=DelegationJwtEncoderFactory.create(KEYS,KEY_ID);
  static final List<String> ALL=List.of("topology.graph.read","topology.proposal.write","topology.proposal.review","topology.graph.accept");
  @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81").withDatabaseName("uacrf_topology").withUsername("topology_test").withPassword("topology_test");
  @Container static final Neo4jContainer NEO4J=neo4jContainer();
  private static Neo4jContainer neo4jContainer() {
    Neo4jContainer container=new Neo4jContainer("neo4j:5.26.0-community").withAdminPassword("topology_test_password");
    container.setPortBindings(List.of("55689:7687"));
    return container;
  }

  @Test void asyncSubgraphAdmissionIsDurableIdempotentAndExplicitlyIncomplete() throws Exception {
    String alice=token("alice","command-admission","*",ALL), bob=token("bob","command-admission","*",ALL);
    createAndApprove(alice,bob,proposal("command-edge","service","svc","DEPENDS_ON","agent_workload","agent","AUTHORED",""),"command-edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    String body=selectionRequest(graph.get("id").asString(),"first reason");
    HttpResponse<String> admitted=postCommand("/v1/graphs/subgraphs:select",bob,"selection-key",body);
    assertEquals(202,admitted.statusCode());
    JsonNode receipt=json.readTree(admitted.body());
    assertEquals("PENDING",receipt.get("status").asString());
    assertEquals(false,receipt.get("domain_completion").asBoolean());
    assertEquals("/v1/commands/"+receipt.get("command_id").asString(),receipt.get("status_resource").asString());
    assertEquals(receipt.get("status_resource").asString(),admitted.headers().firstValue("Location").orElseThrow());
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.graph_command_outbox WHERE command_id=?::uuid",Integer.class,receipt.get("command_id").asString()));
    assertEquals(graph.get("digest").asString(),db.queryForObject(
        "SELECT source_binding->>'graph_digest' FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,receipt.get("command_id").asString()));

    HttpResponse<String> replay=postCommand("/v1/graphs/subgraphs:select",bob,"selection-key",body);
    assertEquals(202,replay.statusCode());
    assertEquals(receipt.get("command_id").asString(),json.readTree(replay.body()).get("command_id").asString());
    assertEquals(409,postCommand("/v1/graphs/subgraphs:select",bob,"selection-key",
        selectionRequest(graph.get("id").asString(),"different reason")).statusCode());
    org.mockito.Mockito.verify(proofs,org.mockito.Mockito.times(1)).prepareServiceRevision(
        org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("svc"),org.mockito.ArgumentMatchers.eq("1"),
        org.mockito.ArgumentMatchers.eq("a".repeat(64)),org.mockito.ArgumentMatchers.eq(TopologyProofClient.SUBGRAPH_SELECTION),
        org.mockito.ArgumentMatchers.any());

    HttpResponse<String> pending=get("/v1/commands/"+receipt.get("command_id").asString(),bob);
    assertEquals(200,pending.statusCode());
    assertEquals("ACCEPTED",json.readTree(pending.body()).get("status").asString());
    String scopedBob=token("bob","command-admission","svc",List.of("topology.graph.read"));
    String otherScope=token("bob","command-admission","other-service",List.of("topology.graph.read"));
    assertEquals(202,postCommand("/v1/graphs/subgraphs:select",scopedBob,"selection-key",body).statusCode());
    assertEquals(200,get("/v1/commands/"+receipt.get("command_id").asString(),scopedBob).statusCode());
    assertEquals(403,postCommand("/v1/graphs/subgraphs:select",otherScope,"selection-key",body).statusCode());
    assertEquals(404,get("/v1/commands/"+receipt.get("command_id").asString(),otherScope).statusCode());
    while (graphCommandWorker.processNext()) {
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),bob).body()).get("status").asString())) break;
    }
    JsonNode status=json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),bob).body());
    assertEquals("SUCCEEDED",status.get("status").asString());
    assertEquals(true,status.get("terminal").asBoolean());
    assertEquals(true,status.get("domain_completion").asBoolean());
    assertEquals("INCOMPLETE",status.get("advisory_result").get("completeness").asString());
    assertThat(status.get("advisory_result").get("summary").asString())
        .contains("SLOs","customer journeys","customer segments","controls","fallbacks","recovery paths");
    assertThat(status.has("result")).isFalse();
    JsonNode result=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,receipt.get("command_id").asString()));
    assertThat(result.get("result_digest").asString()).matches("[a-f0-9]{64}");
    assertThat(result.get("s04_summary_coverage").get("complete").asBoolean()).isFalse();
    assertEquals(result.get("result_digest").asString(),
        status.get("advisory_result").get("result_digest").asString());
    assertEquals(404,get("/v1/commands/"+receipt.get("command_id").asString(),alice).statusCode());
  }

  @Test void preservesObservedAtFromBoundedQualifiedSummaryWithoutClaimingFreshness() throws Exception {
    String alice=token("alice","command-evidence","*",ALL), bob=token("bob","command-evidence","*",ALL);
    createAndApprove(alice,bob,proposal("evidence-edge","service","svc","DEPENDS_ON","agent_workload","agent","AUTHORED",""),"evidence-edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    String digest="e".repeat(64), observedAt="2024-01-02T03:04:05Z";
    Map<String,Object> summary=Map.of("evidence_id","evidence-1","source_class","PROVIDER",
        "source_ref","provider://bounded-reference","sequence",1,"observed_at",observedAt,
        "kind","metric","qualification_state","QUALIFIED","revision",1,"payload_digest",digest);
    db.update("INSERT INTO uacrf_topology.proposal_evidence "
        +"(tenant_id,environment_id,proposal_id,source_version,target_version,evidence_id,evidence_digest,evidence_revision,source_receipt,target_receipt,summary) "
        +"VALUES ('command-evidence','env-1','evidence-edge','v1','v1','evidence-1',?,1,'{}'::jsonb,'{}'::jsonb,?::jsonb)",
        digest,json.writeValueAsString(summary));
    org.mockito.Mockito.when(proofs.prepare(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq(TopologyProofClient.SUBGRAPH_SELECTION),
        org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call->{
          TopologyProofClient.Binding expected=call.getArgument(2);
          return new TopologyProofClient.Binding(expected.sourceId(),expected.sourceVersion(),
              expected.targetId(),expected.targetVersion(),expected.evidenceId(),expected.evidenceDigest(),
              expected.evidenceRevision(),expected.sourceReceipt(),expected.targetReceipt(),summary);
        });
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode admitted=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"old-evidence",
        selectionRequest(graph.get("id").asString(),"old qualified evidence")).body());
    while (graphCommandWorker.processNext()) {
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+admitted.get("command_id").asString(),bob).body()).get("status").asString())) break;
    }
    JsonNode result=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,admitted.get("command_id").asString()));
    assertEquals("INCOMPLETE",result.get("outcome").asString());
    assertEquals(observedAt,result.get("verified_s04_summaries").get(0).get("observed_at").asString());
    assertThat(result.get("freshness_limitation").asString())
        .contains("no evidence-expiration policy","QUALIFIED does not certify freshness");
    assertThat(result.get("unsupported_domains").toString())
        .contains("S02 service-revision snapshots do not bind journey membership");
  }

  @Test void recordsStaleEvidenceSummaryAsExplicitIncompleteCoverage() throws Exception {
    String alice=token("alice","stale-summary","*",ALL), bob=token("bob","stale-summary","*",ALL);
    createAndApprove(alice,bob,proposal("stale-summary-edge","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"stale-summary-edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    db.update("INSERT INTO uacrf_topology.proposal_evidence "
        +"(tenant_id,environment_id,proposal_id,source_version,target_version,evidence_id,evidence_digest,evidence_revision,source_receipt,target_receipt,summary) "
        +"VALUES ('stale-summary','env-1','stale-summary-edge','v1','v1','stale-evidence',?,1,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb)",
        "e".repeat(64));
    org.mockito.Mockito.when(proofs.prepare(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq(TopologyProofClient.SUBGRAPH_SELECTION),
        org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
        .thenThrow(new TopologyException(org.springframework.http.HttpStatus.CONFLICT,
            "PROOF_STALE","The evidence proof is stale."));
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode admitted=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"stale-summary-command",
        selectionRequest(graph.get("id").asString(),"stale evidence")).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+admitted.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    JsonNode result=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,admitted.get("command_id").asString()));
    JsonNode coverage=result.get("s04_summary_coverage");
    assertEquals(false,coverage.get("complete").asBoolean());
    assertEquals(1,coverage.get("unverified_summary_count").asInt());
    assertThat(coverage.get("reason_codes").toString()).contains("SUMMARY_STALE","SUMMARY_COVERAGE_GAP");
  }

  @Test void validatesAllowlistOpaqueCatalogReferenceAndRequestShape() throws Exception {
    String actor=token("alice","command-validation","*",ALL);
    String badQuery=selectionRequest("graph","bad reason").replace("action-relevant-dependencies.v1","arbitrary-query");
    assertEquals(422,postCommand("/v1/graphs/subgraphs:select",actor,"bad-query",badQuery).statusCode());
    String badRef=selectionRequest("graph","bad reason").replace(
        "catalog://service/svc/revision/1","https://catalog.example/svc");
    assertEquals(400,postCommand("/v1/graphs/subgraphs:select",actor,"bad-ref",badRef).statusCode());
    String missingVersion=selectionRequest("graph","bad reason").replace(
        "catalog://service/svc/revision/1","catalog://service/svc/revision/");
    assertEquals(409,postCommand("/v1/graphs/subgraphs:select",actor,"missing-version",missingVersion).statusCode());
    String badDigest=selectionRequest("graph","bad reason").replace("a".repeat(64),"bad");
    assertEquals(400,postCommand("/v1/graphs/subgraphs:select",actor,"bad-digest",badDigest).statusCode());
    assertEquals(400,postCommand("/v1/graphs/subgraphs:select",actor,null,
        selectionRequest("graph","bad reason")).statusCode());
  }

  @Test void v1SelectionStaysUnchangedAndV2IncludesOnlyAcceptedReachableReads() throws Exception {
    String alice=token("alice","reads-selection","*",ALL), bob=token("bob","reads-selection","*",ALL);
    assertThat(post("/v1/dependency-proposals",alice,proposal("reads-invalid-source",
        "shared_resource","resource","READS_FROM","shared_resource","other","AUTHORED","")).statusCode())
        .isBetween(400,499);
    assertThat(post("/v1/dependency-proposals",alice,proposal("reads-invalid-target",
        "service","svc","READS_FROM","model","model-1","AUTHORED","")).statusCode())
        .isBetween(400,499);
    createAndApprove(alice,bob,proposal("reads-root","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"reads-root");
    createAndApprove(alice,bob,proposal("reads-accepted","agent_workload","agent","READS_FROM",
        "shared_resource","accepted-data","AUTHORED",""),"reads-accepted");
    createAndApprove(alice,bob,proposal("reads-unreachable-root","service","outsider","DEPENDS_ON",
        "agent_workload","other","AUTHORED",""),"reads-unreachable-root");
    createAndApprove(alice,bob,proposal("reads-unreachable","agent_workload","other","READS_FROM",
        "shared_resource","unreachable-data","AUTHORED",""),"reads-unreachable");
    assertEquals(201,post("/v1/dependency-proposals",alice,proposal("reads-pending",
        "agent_workload","agent","READS_FROM","shared_resource","pending-data","AUTHORED","")).statusCode());
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);

    JsonNode v1=runSelection(bob,"reads-v1",selectionRequest(graph.get("id").asString(),
        "legacy selection"));
    JsonNode v2=runSelection(bob,"reads-v2",selectionRequest(graph.get("id").asString(),
        "read-aware selection","action-relevant-dependencies.v2",0));
    assertEquals("action-relevant-dependencies.v1",v1.get("query_id").asString());
    assertThat(v1.get("edges").toString()).doesNotContain("READS_FROM","accepted-data");
    assertThat(v1.get("nodes").toString()).doesNotContain("accepted-data");

    assertEquals("action-relevant-dependencies.v2",v2.get("query_id").asString());
    assertThat(v2.get("edges").toString()).contains("READS_FROM","accepted-data");
    assertThat(v2.get("edges").toString()).doesNotContain("unreachable-data","pending-data");
    assertThat(v2.get("nodes").toString()).contains("accepted-data");
    assertThat(v2.get("nodes").toString()).doesNotContain("unreachable-data","pending-data");
    for (JsonNode selection:List.of(v1,v2)) {
      JsonNode reconciliation=selection.get("inventory_reconciliation");
      assertEquals("COMPARABLE",reconciliation.get("status").asString());
      assertEquals(1,reconciliation.get("service_membership_count").asInt());
      assertEquals(2,reconciliation.get("graph_workload_node_count").asInt());
      assertEquals(1,reconciliation.get("matched_membership_count").asInt());
      assertEquals(0,reconciliation.get("catalog_membership_missing_from_graph_count").asInt());
      assertEquals(1,reconciliation.get("graph_workload_nodes_outside_service_membership_count").asInt());
      assertThat(reconciliation.toString()).doesNotContain("other");
      String snapshotJson=db.queryForObject("SELECT selection_snapshot::text FROM "
          +"uacrf_topology.graph_domain_revision WHERE selection_digest=?",
          String.class,selection.get("result_digest").asString());
      JsonNode snapshot=json.readTree(snapshotJson);
      assertEquals(reconciliation,snapshot.get("inventory_reconciliation"));
      assertEquals(selection.get("result_digest").asString(),
          GraphDigest.sha256(OwnerReceiptSignature.canonicalize(snapshotJson)));
    }
    String commandId=db.queryForObject("SELECT command_id::text FROM "
        +"uacrf_topology.graph_domain_revision WHERE selection_digest=?",
        String.class,v2.get("result_digest").asString());
    JsonNode publicStatus=json.readTree(get("/v1/commands/"+commandId,bob).body());
    assertEquals(v2.get("inventory_reconciliation"),
        publicStatus.get("advisory_result").get("inventory_reconciliation"));
    String context=db.queryForObject("SELECT source_binding->>'query_id' FROM "
        +"uacrf_topology.graph_domain_revision WHERE selection_digest=?",
        String.class,v2.get("result_digest").asString());
    assertEquals("action-relevant-dependencies.v2",context);
  }

  @Test void differenceRecomputesTheRetainedQueryVersionAndClassifiesV2ReadAdditions() throws Exception {
    String alice=token("alice","reads-classification","*",ALL),
        bob=token("bob","reads-classification","*",ALL);
    createAndApprove(alice,bob,proposal("reads-classification-root","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"reads-classification-root");
    JsonNode authorized=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode retainedV2=runSelection(bob,"reads-classification-v2",
        selectionRequest(authorized.get("id").asString(),"v2 select",
            "action-relevant-dependencies.v2",0));
    String v2Digest=retainedV2.get("result_digest").asString();
    JsonNode retainedV1=runSelection(bob,"reads-classification-v1",
        selectionRequest(authorized.get("id").asString(),"v1 select"));
    String v1Digest=retainedV1.get("result_digest").asString();

    createAndApprove(alice,bob,proposal("reads-classification-added","agent_workload","agent",
        "READS_FROM","shared_resource","data-store","AUTHORED",""),"reads-classification-added");
    JsonNode current=json.readTree(post("/v1/graphs:accept",bob,accept(1)).body());
    whenCaseReceipt(TopologyProofClient.DIFFERENCE_CLASSIFICATION);

    JsonNode v2Receipt=json.readTree(postCommand("/v1/graphs/differences:classify",bob,
        "reads-classification-v2",classifyRequest("svc",authorized.get("id").asString(),
            current.get("id").asString(),v2Digest,0)).body());
    JsonNode v2Result=runClassification(bob,v2Receipt);
    assertEquals("widening",v2Result.get("classification").asString());
    assertThat(v2Result.get("read_additions").toString()).contains("data-store");
    assertThat(v2Result.get("changes").toString()).contains("data_read_added");

    JsonNode v1Receipt=json.readTree(postCommand("/v1/graphs/differences:classify",bob,
        "reads-classification-v1",classifyRequest("svc",authorized.get("id").asString(),
            current.get("id").asString(),v1Digest,1)).body());
    JsonNode v1Result=runClassification(bob,v1Receipt);
    assertEquals("indeterminate",v1Result.get("classification").asString());
    assertThat(v1Result.has("read_additions")).isFalse();
    String recomputedQuery=db.queryForObject("SELECT comparison_snapshot->'current_selection_snapshot'->>'query_id' "
        +"FROM uacrf_topology.graph_domain_revision WHERE command_id=?::uuid",
        String.class,v1Receipt.get("command_id").asString());
    assertEquals("action-relevant-dependencies.v1",recomputedQuery);
  }

  @Test void v3SelectionBindsCaseWorkloadObjectivesAndMakesChangedSetIndeterminate() throws Exception {
    String alice=token("alice","slo-context","*",ALL), bob=token("bob","slo-context","*",ALL);
    createAndApprove(alice,bob,proposal("slo-context-root","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"slo-context-root");
    JsonNode authorized=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    String sloRequest=selectionRequest(authorized.get("id").asString(),"SLO context",
        "action-relevant-dependencies.v3",0);
    assertEquals(409,postCommand("/v1/graphs/subgraphs:select",bob,"slo-context-unmatched",
        sloRequest).statusCode());
    org.mockito.Mockito.verify(proofs,org.mockito.Mockito.never())
        .prepareObjectiveSnapshot(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    whenCaseReceiptForWorkload(TopologyProofClient.SUBGRAPH_SELECTION,"agent");
    org.mockito.Mockito.when(proofs.prepareObjectiveSnapshot(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("agent"),
        org.mockito.ArgumentMatchers.eq(TopologyProofClient.SUBGRAPH_SELECTION),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(objectiveSnapshot("agent",TopologyProofClient.SUBGRAPH_SELECTION,"a".repeat(64)));

    JsonNode selection=runSelection(bob,"slo-context-selection",sloRequest);
    assertEquals("a".repeat(64),selection.get("slo_objectives").get("set_digest").asString());
    assertThat(selection.get("supported_domains").toString())
        .contains("owner-derived context only");
    assertThat(selection.get("edges").toString()).doesNotContain("GOVERNED_BY_SLO");
    String selectionCommandId=db.queryForObject("SELECT command_id::text FROM "
        +"uacrf_topology.graph_domain_revision WHERE selection_digest=?",
        String.class,selection.get("result_digest").asString());
    JsonNode publicSelection=json.readTree(get("/v1/commands/"+selectionCommandId,bob).body());
    assertEquals(selection.get("slo_objectives"),
        publicSelection.get("advisory_result").get("slo_objectives"));

    createAndApprove(alice,bob,proposal("slo-context-read","agent_workload","agent",
        "READS_FROM","shared_resource","data-store","AUTHORED",""),"slo-context-read");
    JsonNode current=json.readTree(post("/v1/graphs:accept",bob,accept(1)).body());
    whenCaseReceiptForWorkload(TopologyProofClient.DIFFERENCE_CLASSIFICATION,"agent");
    org.mockito.Mockito.when(proofs.prepareObjectiveSnapshot(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("agent"),
        org.mockito.ArgumentMatchers.eq(TopologyProofClient.DIFFERENCE_CLASSIFICATION),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(objectiveSnapshot("agent",TopologyProofClient.DIFFERENCE_CLASSIFICATION,"b".repeat(64)));

    JsonNode receipt=json.readTree(postCommand("/v1/graphs/differences:classify",bob,
        "slo-context-classification",classifyRequest("svc",authorized.get("id").asString(),
            current.get("id").asString(),selection.get("result_digest").asString(),0)).body());
    JsonNode result=runClassification(bob,receipt);
    assertEquals("indeterminate",result.get("classification").asString());
    assertEquals(true,result.get("objective_set_change").get("changed").asBoolean());
    assertThat(result.get("read_additions").toString()).contains("data-store");
    assertThat(result.get("supported_rules").toString()).contains("changed S05 active-objective set");
    JsonNode publicClassification=json.readTree(get("/v1/commands/"
        +receipt.get("command_id").asString(),bob).body());
    assertEquals(result.get("objective_set_change"),
        publicClassification.get("advisory_result").get("objective_set_change"));
    String recomputedQuery=db.queryForObject("SELECT comparison_snapshot->'current_selection_snapshot'->>'query_id' "
        +"FROM uacrf_topology.graph_domain_revision WHERE command_id=?::uuid",
        String.class,receipt.get("command_id").asString());
    assertEquals("action-relevant-dependencies.v3",recomputedQuery);
  }

  @Test void selectionRevisionFenceAllowsMatchingPositiveAndRejectsStaleRevision() throws Exception {
    String alice=token("alice","selection-revisions","*",ALL), bob=token("bob","selection-revisions","*",ALL);
    createAndApprove(alice,bob,proposal("revision-edge","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"revision-edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode first=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"selection-r0",
        selectionRequest(graph.get("id").asString(),"first")).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+first.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    assertEquals("SUCCEEDED",json.readTree(get("/v1/commands/"+first.get("command_id").asString(),bob)
        .body()).get("status").asString());

    JsonNode second=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"selection-r1",
        selectionRequest(graph.get("id").asString(),"second",1)).body());
    assertEquals("PENDING",second.get("status").asString());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+second.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    assertEquals(2,db.queryForObject("SELECT current_revision FROM uacrf_topology.graph_domain_head "
        +"WHERE tenant_id='selection-revisions' AND operation='SUBGRAPH_SELECTION'",
        Integer.class));
    createAndApprove(alice,bob,proposal("revision-edge-2","agent_workload","agent","DEPENDS_ON",
        "shared_resource","database","AUTHORED",""),"revision-edge-2");
    JsonNode updatedGraph=json.readTree(post("/v1/graphs:accept",bob,accept(1)).body());
    assertThat(updatedGraph.get("id").asString()).isNotEqualTo(graph.get("id").asString());
    JsonNode third=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"selection-r2",
        selectionRequest(updatedGraph.get("id").asString(),"updated graph context",2)).body());
    assertEquals("PENDING",third.get("status").asString());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+third.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    assertEquals("SUCCEEDED",json.readTree(get("/v1/commands/"+third.get("command_id").asString(),bob)
        .body()).get("status").asString());
    assertEquals(3,db.queryForObject("SELECT current_revision FROM uacrf_topology.graph_domain_head "
        +"WHERE tenant_id='selection-revisions' AND operation='SUBGRAPH_SELECTION'",
        Integer.class));
    assertEquals(409,postCommand("/v1/graphs/subgraphs:select",bob,"selection-stale",
        selectionRequest(graph.get("id").asString(),"stale",1)).statusCode());
  }

  @Test void classifiesRetainedSelectionDependencyDeltaAndFencesRevision() throws Exception {
    String alice=token("alice","classification-delta","*",ALL),
        bob=token("bob","classification-delta","*",ALL);
    createAndApprove(alice,bob,proposal("delta-base","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"delta-base");
    JsonNode authorized=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode selectionReceipt=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"delta-selection",
        selectionRequest(authorized.get("id").asString(),"select")).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+selectionReceipt.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    JsonNode selectionResult=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,selectionReceipt.get("command_id").asString()));
    String selectionDigest=selectionResult.get("result_digest").asString();
    assertThat(selectionDigest).matches("[a-f0-9]{64}");

    createAndApprove(alice,bob,proposal("delta-added","agent_workload","agent","DEPENDS_ON",
        "shared_resource","database","AUTHORED",""),"delta-added");
    JsonNode current=json.readTree(post("/v1/graphs:accept",bob,accept(1)).body());
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.graph_edges WHERE graph_id=? "
        +"AND source_type='agent_workload' AND source_id='agent' AND relationship_type='DEPENDS_ON'",
        Integer.class,current.get("id").asString()));
    whenCaseReceipt(TopologyProofClient.DIFFERENCE_CLASSIFICATION);
    String compareBody=classifyRequest("svc",authorized.get("id").asString(),current.get("id").asString(),
        selectionDigest,0);
    assertEquals(409,postCommand("/v1/graphs/differences:classify",bob,"wrong-selection",
        compareBody.replace(selectionDigest,"f".repeat(64))).statusCode());

    JsonNode first=json.readTree(postCommand("/v1/graphs/differences:classify",bob,"classify-r0",
        compareBody).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+first.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    JsonNode status=json.readTree(get("/v1/commands/"+first.get("command_id").asString(),bob).body());
    assertEquals("SUCCEEDED",status.get("status").asString());
    assertEquals("INDETERMINATE",status.get("advisory_result").get("completeness").asString());
    assertEquals(selectionDigest,status.get("advisory_result").get("selection_digest").asString());
    JsonNode result=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,first.get("command_id").asString()));
    assertEquals("widening",result.get("classification").asString(),result.toString());
    assertEquals(selectionDigest,result.get("selection_digest").asString());
    assertThat(result.get("changes").get(0).get("change_type").asString()).isEqualTo("dependency_added");
    assertEquals("database",result.get("changes").get(0).get("after").get("target_id").asString());
    assertEquals(false,result.get("complete").asBoolean());
    assertThat(result.get("unsupported_domains").toString())
        .contains("SLOs","customer journeys","controls","recovery paths");

    createAndApprove(alice,bob,proposal("delta-added-2","agent_workload","agent","DEPENDS_ON",
        "shared_resource","cache","AUTHORED",""),"delta-added-2");
    JsonNode updatedCurrent=json.readTree(post("/v1/graphs:accept",bob,accept(2)).body());
    assertThat(updatedCurrent.get("id").asString()).isNotEqualTo(current.get("id").asString());
    JsonNode second=json.readTree(postCommand("/v1/graphs/differences:classify",bob,"classify-r1",
        classifyRequest("svc",authorized.get("id").asString(),updatedCurrent.get("id").asString(),
            selectionDigest,1)).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+second.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    assertEquals(2,db.queryForObject("SELECT current_revision FROM uacrf_topology.graph_domain_head "
        +"WHERE tenant_id='classification-delta' AND operation='DIFFERENCE_CLASSIFICATION'",
        Integer.class));
    assertEquals(409,postCommand("/v1/graphs/differences:classify",bob,"classify-stale",
        classifyRequest("svc",authorized.get("id").asString(),updatedCurrent.get("id").asString(),
            selectionDigest,1)).statusCode());
  }

  @Test void classifiesSupportedFailureDomainDeltaAsTargetChanging() throws Exception {
    String alice=token("alice","failure-domain-delta","*",ALL),
        bob=token("bob","failure-domain-delta","*",ALL);
    createAndApprove(alice,bob,proposal("failure-base","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"failure-base");
    JsonNode authorized=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode selectionReceipt=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"failure-selection",
        selectionRequest(authorized.get("id").asString(),"select")).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+selectionReceipt.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    JsonNode selectionResult=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,selectionReceipt.get("command_id").asString()));

    createAndApprove(alice,bob,proposal("failure-added","agent_workload","agent","MEMBER_OF_FAILURE_DOMAIN",
        "failure_domain","zone-a","AUTHORED",""),"failure-added");
    JsonNode current=json.readTree(post("/v1/graphs:accept",bob,accept(1)).body());
    whenCaseReceipt(TopologyProofClient.DIFFERENCE_CLASSIFICATION);
    JsonNode receipt=json.readTree(postCommand("/v1/graphs/differences:classify",bob,"failure-classify",
        classifyRequest("svc",authorized.get("id").asString(),current.get("id").asString(),
            selectionResult.get("result_digest").asString(),0)).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    JsonNode result=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,receipt.get("command_id").asString()));
    assertEquals("target-changing",result.get("classification").asString());
    assertEquals("failure_domain_membership_added",
        result.get("changes").get(0).get("change_type").asString());
    assertEquals("MEMBER_OF_FAILURE_DOMAIN",
        result.get("changes").get(0).get("after").get("relationship_type").asString());
    assertEquals("zone-a",result.get("changes").get(0).get("after").get("target_id").asString());
    assertEquals(false,result.get("complete").asBoolean());
  }

  @Test void canonicalizesLegacyFailureDomainRelationshipOnProposal() throws Exception {
    String alice=token("alice","legacy-failure-domain","*",ALL),
        bob=token("bob","legacy-failure-domain","*",ALL);
    HttpResponse<String> proposed=post("/v1/dependency-proposals",alice,
        proposal("legacy-failure-domain-edge","agent_workload","agent","IN_FAILURE_DOMAIN",
            "failure_domain","zone-a","AUTHORED",""));
    assertEquals(201,proposed.statusCode());
    assertEquals("MEMBER_OF_FAILURE_DOMAIN",
        json.readTree(proposed.body()).get("relationship_type").asString());
    assertEquals(200,post("/v1/dependency-proposals/legacy-failure-domain-edge:review",bob,
        review(1)).statusCode());
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    assertEquals("MEMBER_OF_FAILURE_DOMAIN",graph.get("edges").get(0)
        .get("relationship_type").asString());
  }

  @Test void stalePinnedGraphBecomesIndeterminateAndNeverClaimsClassification() throws Exception {
    String alice=token("alice","command-stale","*",ALL), bob=token("bob","command-stale","*",ALL);
    createAndApprove(alice,bob,proposal("stale-edge","service","svc","DEPENDS_ON","agent_workload","agent","AUTHORED",""),"stale-edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    stubCommandProofs("svc","1",TopologyProofClient.SUBGRAPH_SELECTION);
    JsonNode selection=json.readTree(postCommand("/v1/graphs/subgraphs:select",bob,"stale-selection",
        selectionRequest(graph.get("id").asString(),"select")).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+selection.get("command_id").asString(),bob)
          .body()).get("status").asString())) break;
    JsonNode selectionResult=json.readTree(db.queryForObject(
        "SELECT result::text FROM uacrf_topology.graph_command WHERE command_id=?::uuid",
        String.class,selection.get("command_id").asString()));
    String digest=selectionResult.get("result_digest").asString();
    whenCaseReceipt(TopologyProofClient.DIFFERENCE_CLASSIFICATION);
    String body=classifyRequest("svc",graph.get("id").asString(),graph.get("id").asString(),digest,0);
    JsonNode receipt=json.readTree(postCommand("/v1/graphs/differences:classify",bob,"difference-key",body).body());
    assertEquals("PENDING",receipt.get("status").asString());
    db.update("UPDATE uacrf_topology.graph_revisions SET digest=? WHERE id=?","b".repeat(64),graph.get("id").asString());
    while (graphCommandWorker.processNext()) {
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),bob).body()).get("status").asString())) break;
    }
    JsonNode status=json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),bob).body());
    assertEquals("FAILED",status.get("status").asString());
    assertEquals(true,status.get("terminal").asBoolean());
    assertEquals(false,status.get("domain_completion").asBoolean());
    assertThat(status.has("advisory_result")).isFalse();
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",POSTGRES::getJdbcUrl); p.add("spring.datasource.username",POSTGRES::getUsername); p.add("spring.datasource.password",POSTGRES::getPassword);
    p.add("uacrf.topology.neo4j.uri",NEO4J::getBoltUrl); p.add("uacrf.topology.neo4j.user",()->"neo4j"); p.add("uacrf.topology.neo4j.password",NEO4J::getAdminPassword);
    p.add("uacrf.topology.neo4j.connection-timeout-ms",()->"500"); p.add("uacrf.topology.neo4j.max-transaction-retry-ms",()->"1000");
    p.add("uacrf.topology.projection.enabled",()->"true"); p.add("uacrf.topology.projection.interval-ms",()->"3600000"); p.add("uacrf.topology.projection.retry-base-ms",()->"10");
    p.add("uacrf.topology.commands.interval-ms",()->"3600000");
    p.add("uacrf.delegation.public-key-base64",()->Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())); p.add("uacrf.delegation.key-id",()->KEY_ID);
  }
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired GraphProjectionWorker projectionWorker;
  @Autowired GraphCommandWorker graphCommandWorker;
  @Autowired Driver neo4j;
  @MockitoBean TopologyProofClient proofs;
  final HttpClient http=HttpClient.newHttpClient();

  @Test void lifecycleConcurrencyIsolationAndReceipt() throws Exception {
    String writer=token("alice","life","*",ALL), reviewer=token("bob","life","*",ALL), other=token("bob","other","*",ALL);
    assertEquals(201,post("/v1/dependency-proposals",writer,proposal("p1","service","svc","DEPENDS_ON","agent_workload","agent","AUTHORED","")).statusCode());
    assertEquals(409,post("/v1/dependency-proposals/p1:review",writer,review(1)).statusCode());
    assertEquals(409,post("/v1/dependency-proposals/p1:review",reviewer,review(2)).statusCode());
    assertThat(post("/v1/dependency-proposals/p1:review",reviewer,review(1)).body()).contains("ACCEPTED");
    HttpResponse<String> accepted=post("/v1/graphs:accept",reviewer,accept(0)); assertEquals(200,accepted.statusCode());
    JsonNode graph=json.readTree(accepted.body()); String id=graph.get("id").asString(), digest=graph.get("digest").asString();
    assertEquals(409,post("/v1/graphs:accept",reviewer,accept(0)).statusCode());
    JsonNode duplicate=json.readTree(post("/v1/graphs:accept",reviewer,accept(1)).body()); assertEquals(id,duplicate.get("id").asString());
    assertEquals(404,get("/v1/graphs/"+id,other).statusCode()); assertEquals(404,get("/v1/dependency-proposals/p1",other).statusCode());
    assertEquals(400,get("/v1/graphs/"+id+"/subgraph?root_type=service&root_id=svc&depth=6",reviewer).statusCode());
    assertThat(get("/v1/graphs/"+id+"/subgraph?root_type=service&root_id=svc&depth=1",reviewer).body()).contains("agent_workload");
    assertThat(get("/v1/graphs/"+id+"/impact?entity_type=service&entity_id=svc",reviewer).body()).contains("MIXED");
    JsonNode receipt=json.readTree(post("/internal/v1/graphs/"+id+":receipt",reviewer,"{}").body());
    assertEquals(digest,receipt.get("digest").asString()); assertEquals("PENDING",receipt.get("projection_status").asString()); assertThat(receipt.get("eligibility").asBoolean()).isFalse();
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.outbox_event WHERE tenant_id='life' AND event_type='uacrf.s06.graph.accepted.v1'",Integer.class));
  }

  @Test void genericTopologyReadsRequireGlobalScope() throws Exception {
    String writer=token("alice","read-scope","*",ALL), reviewer=token("bob","read-scope","*",ALL);
    createAndApprove(writer,reviewer,proposal("scope-proposal","service","svc","DEPENDS_ON",
        "agent_workload","agent","AUTHORED",""),"scope-proposal");
    String graphId=json.readTree(post("/v1/graphs:accept",reviewer,accept(0)).body()).get("id").asString();
    String scoped=token("reader","read-scope","svc",List.of("topology.graph.read"));
    String other=token("reader","read-scope","other-service",List.of("topology.graph.read"));
    for (String reader:List.of(scoped,other)) {
      for (String path:List.of(
          "/v1/dependency-proposals",
          "/v1/dependency-proposals/scope-proposal",
          "/v1/graphs/"+graphId,
          "/v1/graphs/"+graphId+"/reconciliation",
          "/v1/graphs/"+graphId+"/subgraph?root_type=service&root_id=svc&depth=1",
          "/v1/graphs/"+graphId+"/impact?entity_type=service&entity_id=svc")) {
        assertEquals(403,get(path,reader).statusCode(),path);
      }
      assertEquals(403,post("/internal/v1/graphs/"+graphId+":receipt",reader,"{}").statusCode());
    }
    assertEquals(200,get("/v1/dependency-proposals/scope-proposal",reviewer).statusCode());
    assertEquals(200,get("/v1/graphs/"+graphId,reviewer).statusCode());
    assertEquals(200,post("/internal/v1/graphs/"+graphId+":receipt",reviewer,"{}").statusCode());
  }
  @Test void validatesProvenanceRolesAndDatabaseInvariant() throws Exception {
    String actor=token("alice","validation","*",ALL);
    assertEquals(400,post("/v1/dependency-proposals",actor,proposal("badrole","model","m","USES_MODEL","service","s","AUTHORED","")).statusCode());
    assertEquals(400,post("/v1/dependency-proposals",actor,proposal("observed","service","s","USES_MODEL","model","m","OBSERVED","")).statusCode());
    assertEquals(400,post("/v1/dependency-proposals",actor,proposal("authored","service","s","USES_MODEL","model","m","AUTHORED",",\"source_system\":\"x\"")).statusCode());
    assertEquals(403,post("/v1/dependency-proposals",token("alice","validation","svc",ALL),proposal("scope","service","s","USES_MODEL","model","m","AUTHORED","")).statusCode());
    assertEquals(403,post("/v1/dependency-proposals",token("alice","validation","*",List.of("topology.graph.read")),proposal("role","service","s","USES_MODEL","model","m","AUTHORED","")).statusCode());
    assertThatThrownBy(()->db.update("INSERT INTO uacrf_topology.dependency_proposals (tenant_id,environment_id,id,source_type,source_id,relationship_type,target_type,target_id,provenance_kind,status,proposer,revision) VALUES ('validation','env-1','db','service','s','USES_MODEL','model','m','OBSERVED','PROPOSED','x',1)" )).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(()->db.update("INSERT INTO uacrf_topology.dependency_proposals (tenant_id,environment_id,id,source_type,source_id,relationship_type,target_type,target_id,provenance_kind,source_system,status,proposer,revision) VALUES ('validation','env-1','db2','service','s','USES_MODEL','model','m','AUTHORED','x','PROPOSED','x',1)" )).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void recordsConflictAndCarriesEdgesIntoNextImmutableRevision() throws Exception {
    String alice=token("alice","conflict","*",ALL), bob=token("bob","conflict","*",ALL);
    createAndApprove(alice,bob,proposal("a","service","s","USES_MODEL","model","m","AUTHORED",""),"a");
    JsonNode first=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body()); String id=first.get("id").asString();
    createAndApprove(alice,bob,proposal("b","service","s","USES_MODEL","model","m","OBSERVED",",\"source_system\":\"provider\",\"source_revision\":\"r1\",\"verified_at\":\"2026-01-01T00:00:00Z\""),"b");
    assertEquals(id,json.readTree(post("/v1/graphs:accept",bob,accept(1)).body()).get("id").asString());
    post("/v1/graphs:accept",bob,accept(1));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.graph_edge_conflicts WHERE tenant_id='conflict'",Integer.class));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.outbox_event WHERE tenant_id='conflict' AND event_type='uacrf.s06.graph.conflict.v1'",Integer.class));
    createAndApprove(alice,bob,proposal("c","service","s","USES_IDENTITY","identity","i","AUTHORED",""),"c");
    JsonNode second=json.readTree(post("/v1/graphs:accept",bob,accept(1)).body()); assertThat(second.get("id").asString()).isNotEqualTo(id); assertEquals(2,second.get("edges").size());
    assertEquals(1,json.readTree(get("/v1/graphs/"+id,bob).body()).get("edges").size());
  }
  @Test void concurrentAcceptanceSerializesAndRejectsMissingRevision() throws Exception {
    String alice=token("alice","race","*",ALL), bob=token("bob","race","*",ALL);
    createAndApprove(alice,bob,proposal("race-edge","service","s","USES_MODEL","model","m","AUTHORED",""),"race-edge");
    assertEquals(400,post("/v1/graphs:accept",bob,"{\"scope\":\"all\"}").statusCode());
    HttpRequest request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/graphs:accept"))
        .header("Authorization",bob).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(accept(0))).build();
    var first=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
    var second=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
    assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.graph_revisions WHERE tenant_id='race'",Integer.class));
  }
  @Test void impactReportsEveryDistinctSimplePathAndSharedResources() throws Exception {
    String alice=token("alice","paths","*",ALL), bob=token("bob","paths","*",ALL);
    createAndApprove(alice,bob,proposal("a","service","s","DEPENDS_ON","agent_workload","agent","AUTHORED",""),"a");
    createAndApprove(alice,bob,proposal("b","service","s","USES_MODEL","model","m","AUTHORED",""),"b");
    createAndApprove(alice,bob,proposal("c","agent_workload","agent","USES_MODEL","model","m","AUTHORED",""),"c");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body());
    JsonNode impact=json.readTree(get("/v1/graphs/"+graph.get("id").asString()+"/impact?entity_type=service&entity_id=s",bob).body());
    assertEquals(1,impact.get("shared_models").size());
    int modelPaths=0; boolean mixed=false,serviceOnly=false;
    for(JsonNode path:impact.get("paths")) if(path.get("entity").get("entity_id").asString().equals("m")) {
      modelPaths++; mixed |= path.get("classification").asString().equals("MIXED"); serviceOnly |= path.get("classification").asString().equals("SERVICE_ONLY");
    }
    assertEquals(2,modelPaths); assertThat(mixed && serviceOnly).isTrue();
  }
  @Test void projectionVerifiesDigestEmitsOutboxAndIsIdempotent() throws Exception {
    String alice=token("alice","projection","*",ALL), bob=token("bob","projection","*",ALL);
    createAndApprove(alice,bob,proposal("edge","service","svc","USES_MODEL","model","model-1","AUTHORED",""),"edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body()); String id=graph.get("id").asString();
    isolateProjection(id);
    projectUntil(id,"PROJECTED");
    JsonNode receipt=json.readTree(post("/internal/v1/graphs/"+id+":receipt",bob,"{}").body());
    assertEquals("PROJECTED",receipt.get("projection_status").asString()); assertThat(receipt.get("eligibility").asBoolean()).isTrue();
    assertEquals(graph.get("digest").asString(),db.queryForObject("SELECT verified_digest FROM uacrf_topology.graph_projections WHERE graph_id=?",String.class,id).trim());
    assertEquals(1,db.queryForObject("SELECT count(*) FROM uacrf_topology.outbox_event WHERE tenant_id='projection' AND event_type='uacrf.s06.graph.projected.v1'",Integer.class));
    try(Session session=neo4j.session()) {
      assertEquals(2,session.run("MATCH (n:UacrfTopologyNode {tenant_id:'projection', revision_id:$id}) RETURN count(n) AS count",Map.of("id",id)).single().get("count").asInt());
      assertEquals(1,session.run("MATCH ()-[e:UACRF_TOPOLOGY_EDGE {tenant_id:'projection', revision_id:$id}]->() RETURN count(e) AS count",Map.of("id",id)).single().get("count").asInt());
    }
    db.update("UPDATE uacrf_topology.graph_projections SET status='PENDING',verified_digest=NULL,next_attempt_at=now(),updated_at=now() WHERE graph_id=?",id);
    assertThat(projectionWorker.projectNext()).isTrue();
    try(Session session=neo4j.session()) {
      assertEquals(2,session.run("MATCH (n:UacrfTopologyNode {tenant_id:'projection', revision_id:$id}) RETURN count(n) AS count",Map.of("id",id)).single().get("count").asInt());
      assertEquals(1,session.run("MATCH ()-[e:UACRF_TOPOLOGY_EDGE {tenant_id:'projection', revision_id:$id}]->() RETURN count(e) AS count",Map.of("id",id)).single().get("count").asInt());
    }
  }

  @Test void reconciliationDetectsTamperingAndNeverRestoresEligibility() throws Exception {
    String alice=token("alice","tamper","*",ALL), bob=token("bob","tamper","*",ALL);
    createAndApprove(alice,bob,proposal("edge","service","svc","USES_MODEL","model","model-1","AUTHORED",""),"edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body()); String id=graph.get("id").asString();
    isolateProjection(id);
    projectUntil(id,"PROJECTED");
    try(Session session=neo4j.session()) {
      session.run("MATCH (n:UacrfTopologyNode {tenant_id:'tamper', revision_id:$id, entity_id:'svc'}) SET n.entity_id='modified'",Map.of("id",id)).consume();
    }
    JsonNode result=json.readTree(get("/v1/graphs/"+id+"/reconciliation",bob).body());
    assertEquals("DRIFTED",result.get("projection_status").asString()); assertEquals(1,result.get("missing_nodes").size()); assertEquals(1,result.get("extra_nodes").size());
    JsonNode receipt=json.readTree(post("/internal/v1/graphs/"+id+":receipt",bob,"{}").body());
    assertEquals("DRIFTED",receipt.get("projection_status").asString()); assertThat(receipt.get("eligibility").asBoolean()).isFalse();
  }

  @Test void tenantScopedReadbackIgnoresAnotherTenantsMatchingRevisionMarker() throws Exception {
    String alice=token("alice","tenant-a","*",ALL), bob=token("bob","tenant-a","*",ALL);
    createAndApprove(alice,bob,proposal("edge","service","svc","USES_MODEL","model","model-1","AUTHORED",""),"edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body()); String id=graph.get("id").asString();
    isolateProjection(id);
    projectUntil(id,"PROJECTED");
    try(Session session=neo4j.session()) {
      session.run("CREATE (:UacrfTopologyNode {tenant_id:'tenant-b', environment_id:'env-1', revision_id:$id, revision_number:1, entity_type:'service', entity_id:'secret'})",Map.of("id",id)).consume();
    }
    JsonNode result=json.readTree(get("/v1/graphs/"+id+"/reconciliation",bob).body());
    assertEquals("PROJECTED",result.get("projection_status").asString()); assertEquals(0,result.get("extra_nodes").size()); assertEquals(0,result.get("missing_nodes").size());
    assertThat(result.get("actual_digest").asString()).isEqualTo(graph.get("digest").asString());
  }

  @Test void activeLeaseOwnedElsewhereIsNeverStolen() throws Exception {
    String alice=token("alice","lease","*",ALL), bob=token("bob","lease","*",ALL);
    createAndApprove(alice,bob,proposal("edge","service","svc","USES_MODEL","model","model-1","AUTHORED",""),"edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body()); String id=graph.get("id").asString();
    isolateProjection(id);
    db.update("UPDATE uacrf_topology.graph_projections SET status='PROJECTING',lease_owner='another-worker',lease_expires_at=now()+interval '1 minute' WHERE graph_id=?",id);
    assertThat(projectionWorker.projectNext()).isFalse();
    assertEquals("another-worker",db.queryForObject("SELECT lease_owner FROM uacrf_topology.graph_projections WHERE graph_id=?",String.class,id));
  }

  @Test void neo4jOutageIsRetryableAndRecoversAfterRestart() throws Exception {
    String alice=token("alice","outage","*",ALL), bob=token("bob","outage","*",ALL);
    createAndApprove(alice,bob,proposal("edge","service","svc","USES_MODEL","model","model-1","AUTHORED",""),"edge");
    JsonNode graph=json.readTree(post("/v1/graphs:accept",bob,accept(0)).body()); String id=graph.get("id").asString();
    isolateProjection(id);
    NEO4J.stop();
    try {
      projectUntil(id,"FAILED");
      assertEquals(1,db.queryForObject("SELECT attempt_count FROM uacrf_topology.graph_projections WHERE graph_id=?",Integer.class,id));
    } finally { NEO4J.start(); }
    neo4j.verifyConnectivity();
    db.update("UPDATE uacrf_topology.graph_projections SET next_attempt_at=now() WHERE graph_id=?",id);
    assertThat(projectionWorker.projectNext()).isTrue();
    assertEquals("PROJECTED",db.queryForObject("SELECT status FROM uacrf_topology.graph_projections WHERE graph_id=?",String.class,id));
    assertEquals(2,db.queryForObject("SELECT attempt_count FROM uacrf_topology.graph_projections WHERE graph_id=?",Integer.class,id));
  }

  private void isolateProjection(String id) {
    db.update("UPDATE uacrf_topology.graph_projections SET status='DRIFTED',lease_owner=NULL,lease_expires_at=NULL,updated_at=now() WHERE graph_id<>? AND status IN ('PENDING','FAILED','PROJECTING')",id);
  }

  private void projectUntil(String id,String expected) throws Exception {
    for(int i=0;i<100;i++) {
      String status=db.queryForObject("SELECT status FROM uacrf_topology.graph_projections WHERE graph_id=?",String.class,id);
      if(expected.equals(status)) return;
      if("FAILED".equals(status) && !"FAILED".equals(expected)) {
        String error=db.queryForObject("SELECT last_error FROM uacrf_topology.graph_projections WHERE graph_id=?",String.class,id);
        throw new AssertionError("Projection failed before reaching " + expected + ": " + error);
      }
      projectionWorker.projectNext();
    }
    assertEquals(expected,db.queryForObject("SELECT status FROM uacrf_topology.graph_projections WHERE graph_id=?",String.class,id));
  }

  private void createAndApprove(String a,String b,String body,String id) throws Exception { assertEquals(201,post("/v1/dependency-proposals",a,body).statusCode()); assertEquals(200,post("/v1/dependency-proposals/"+id+":review",b,review(1)).statusCode()); }
  private static String proposal(String id,String st,String si,String rel,String tt,String ti,String kind,String extra) { return "{\"id\":\"%s\",\"source_type\":\"%s\",\"source_id\":\"%s\",\"relationship_type\":\"%s\",\"target_type\":\"%s\",\"target_id\":\"%s\",\"provenance_kind\":\"%s\"%s}".formatted(id,st,si,rel,tt,ti,kind,extra); }
  private static String review(long revision) { return "{\"decision\":\"ACCEPT\",\"expected_revision\":"+revision+"}"; }
  private static String accept(long revision) { return "{\"scope\":\"all\",\"expected_revision_number\":"+revision+"}"; }
  private static String selectionRequest(String graphId,String reason) {
    return selectionRequest(graphId,reason,0);
  }
  private static String selectionRequest(String graphId,String reason,long expectedRevision) {
    return selectionRequest(graphId,reason,"action-relevant-dependencies.v1",expectedRevision);
  }
  private static String selectionRequest(String graphId,String reason,String queryId,long expectedRevision) {
    return "{\"service_id\":\"svc\",\"case_id\":\"case-1\",\"graph_revision_id\":\""+graphId
        +"\",\"reason\":\""+reason+"\",\"expected_revision\":"+expectedRevision+",\"query_id\":\""+queryId+"\","
        +"\"query_parameters_ref\":\"catalog://service/svc/revision/1\",\"query_parameters_digest\":\""+"a".repeat(64)
        +"\",\"purpose\":\"topology.graph.subgraph-selection\",\"maximum_nodes\":200}";
  }
  private JsonNode runSelection(String token,String key,String body) throws Exception {
    JsonNode receipt=json.readTree(postCommand("/v1/graphs/subgraphs:select",token,key,body).body());
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),
          token).body()).get("status").asString())) break;
    return json.readTree(db.queryForObject("SELECT result::text FROM uacrf_topology.graph_command "
        +"WHERE command_id=?::uuid",String.class,receipt.get("command_id").asString()));
  }
  private JsonNode runClassification(String token,JsonNode receipt) throws Exception {
    while (graphCommandWorker.processNext())
      if (!activeCommandStatus(json.readTree(get("/v1/commands/"+receipt.get("command_id").asString(),
          token).body()).get("status").asString())) break;
    return json.readTree(db.queryForObject("SELECT result::text FROM uacrf_topology.graph_command "
        +"WHERE command_id=?::uuid",String.class,receipt.get("command_id").asString()));
  }
  private static String classifyRequest(String serviceId,String authorizedGraphId,String currentGraphId,
      String selectionDigest,long expectedRevision) {
    return "{\"service_id\":\""+serviceId+"\",\"case_id\":\"case-1\",\"reason\":\"compare\","
        +"\"expected_revision\":"+expectedRevision+",\"authorized_graph_revision_id\":\""+authorizedGraphId+"\","
        +"\"current_graph_revision_id\":\""+currentGraphId+"\",\"selection_digest\":\""+selectionDigest+"\"}";
  }
  private void stubCommandProofs(String serviceId,String version,String purpose) {
    Map<String,Object> snapshot=Map.of("service_id",serviceId,"name","Service","owner_id","owner-1",
        "criticality",3,"lifecycle","ACTIVE","revision",Integer.parseInt(version),
        "owned_agent_workload_ids",List.of("agent"));
    org.mockito.Mockito.when(proofs.prepareServiceRevision(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(serviceId),
        org.mockito.ArgumentMatchers.eq(version),org.mockito.ArgumentMatchers.eq("a".repeat(64)),
        org.mockito.ArgumentMatchers.eq(purpose),org.mockito.ArgumentMatchers.any()))
        .thenReturn(new TopologyProofClient.ServiceRevisionBinding(
            Map.of("resource_id",serviceId,"resource_version",version,"content_digest","a".repeat(64)),
            snapshot,List.of("agent"),true));
    whenCaseReceipt(purpose);
  }
  private static boolean activeCommandStatus(String status) {
    return List.of("ACCEPTED","RUNNING","WAITING_FOR_DEPENDENCY","RETRY_SCHEDULED","OUTCOME_UNKNOWN")
        .contains(status);
  }
  private void whenCaseReceipt(String purpose) {
    whenCaseReceiptForWorkload(purpose,"workload-1");
  }
  private void whenCaseReceiptForWorkload(String purpose,String workloadId) {
    org.mockito.Mockito.when(proofs.prepareCaseReceipt(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("case-1"),org.mockito.ArgumentMatchers.eq(purpose),org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("case_id","case-1","purpose","incident","case_digest","d".repeat(64),
            "workload_id",workloadId,"required_source_kinds",List.of("metrics")));
  }
  private static Map<String,Object> objectiveSnapshot(String workloadId,String purpose,String digest) {
    return Map.of("workload_id",workloadId,"purpose",purpose,"objectives",List.of(),
        "set_digest",digest);
  }
  private HttpResponse<String> postCommand(String path,String token,String key,String body) throws Exception {
    HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Authorization",token).header("Content-Type","application/json");
    if(key!=null) request.header("Idempotency-Key",key);
    return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
  private HttpResponse<String> post(String path,String token,String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
  private HttpResponse<String> get(String path,String token) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString()); }
  private static String token(String user,String tenant,String scope,List<String> caps) { DelegationPrincipal p=new DelegationPrincipal("https://uacrf.local/s01|"+user,tenant,"env-1",scope,"https://idp.example.test",user,caps,List.of(),List.of()); return "Bearer "+new DelegationTokenIssuer(ENCODER,Clock.systemUTC(),"https://uacrf.local/s01",KEY_ID).issue(p,"S06"); }
  private static KeyPair keys() { try { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
