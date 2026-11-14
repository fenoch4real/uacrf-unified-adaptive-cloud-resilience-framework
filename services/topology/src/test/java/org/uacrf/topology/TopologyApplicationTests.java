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
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",POSTGRES::getJdbcUrl); p.add("spring.datasource.username",POSTGRES::getUsername); p.add("spring.datasource.password",POSTGRES::getPassword);
    p.add("uacrf.delegation.public-key-base64",()->Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())); p.add("uacrf.delegation.key-id",()->KEY_ID);
  }
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
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
    assertEquals(digest,receipt.get("digest").asString()); assertEquals("NOT_PROJECTED",receipt.get("projection_status").asString()); assertThat(receipt.get("eligibility").asBoolean()).isFalse();
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
  private void createAndApprove(String a,String b,String body,String id) throws Exception { assertEquals(201,post("/v1/dependency-proposals",a,body).statusCode()); assertEquals(200,post("/v1/dependency-proposals/"+id+":review",b,review(1)).statusCode()); }
  private static String proposal(String id,String st,String si,String rel,String tt,String ti,String kind,String extra) { return "{\"id\":\"%s\",\"source_type\":\"%s\",\"source_id\":\"%s\",\"relationship_type\":\"%s\",\"target_type\":\"%s\",\"target_id\":\"%s\",\"provenance_kind\":\"%s\"%s}".formatted(id,st,si,rel,tt,ti,kind,extra); }
  private static String review(long revision) { return "{\"decision\":\"ACCEPT\",\"expected_revision\":"+revision+"}"; }
  private static String accept(long revision) { return "{\"scope\":\"all\",\"expected_revision_number\":"+revision+"}"; }
  private HttpResponse<String> post(String path,String token,String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
  private HttpResponse<String> get(String path,String token) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString()); }
  private static String token(String user,String tenant,String scope,List<String> caps) { DelegationPrincipal p=new DelegationPrincipal("https://uacrf.local/s01|"+user,tenant,"env-1",scope,"https://idp.example.test",user,caps,List.of(),List.of()); return "Bearer "+new DelegationTokenIssuer(ENCODER,Clock.systemUTC(),"https://uacrf.local/s01",KEY_ID).issue(p,"S06"); }
  private static KeyPair keys() { try { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
