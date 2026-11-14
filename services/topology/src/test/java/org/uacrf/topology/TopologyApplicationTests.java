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
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",POSTGRES::getJdbcUrl); p.add("spring.datasource.username",POSTGRES::getUsername); p.add("spring.datasource.password",POSTGRES::getPassword);
    p.add("uacrf.topology.neo4j.uri",NEO4J::getBoltUrl); p.add("uacrf.topology.neo4j.user",()->"neo4j"); p.add("uacrf.topology.neo4j.password",NEO4J::getAdminPassword);
    p.add("uacrf.topology.neo4j.connection-timeout-ms",()->"500"); p.add("uacrf.topology.neo4j.max-transaction-retry-ms",()->"1000");
    p.add("uacrf.topology.projection.enabled",()->"true"); p.add("uacrf.topology.projection.interval-ms",()->"3600000"); p.add("uacrf.topology.projection.retry-base-ms",()->"10");
    p.add("uacrf.delegation.public-key-base64",()->Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())); p.add("uacrf.delegation.key-id",()->KEY_ID);
  }
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired GraphProjectionWorker projectionWorker;
  @Autowired Driver neo4j;
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
  private HttpResponse<String> post(String path,String token,String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
  private HttpResponse<String> get(String path,String token) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString()); }
  private static String token(String user,String tenant,String scope,List<String> caps) { DelegationPrincipal p=new DelegationPrincipal("https://uacrf.local/s01|"+user,tenant,"env-1",scope,"https://idp.example.test",user,caps,List.of(),List.of()); return "Bearer "+new DelegationTokenIssuer(ENCODER,Clock.systemUTC(),"https://uacrf.local/s01",KEY_ID).issue(p,"S06"); }
  private static KeyPair keys() { try { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
