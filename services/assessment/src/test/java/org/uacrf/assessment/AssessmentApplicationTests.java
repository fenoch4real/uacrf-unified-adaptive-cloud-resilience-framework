package org.uacrf.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AssessmentApplicationTests {
  private static final String KEY_ID = "assessment-test-key";
  private static final KeyPair KEY_PAIR = keyPair();
  private static final JwtEncoder ENCODER = DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID);
  private static final List<String> ALL = List.of("assessment.case.read", "assessment.case.write", "assessment.evaluation.run", "assessment.evaluation.read", "assessment.recommendation.read");
  @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81").withDatabaseName("uacrf_assessment").withUsername("assessment_test").withPassword("assessment_test");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl); p.add("spring.datasource.username", POSTGRES::getUsername); p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("uacrf.delegation.public-key-base64", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded())); p.add("uacrf.delegation.key-id", () -> KEY_ID);
  }
  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test void sealedCaseIsImmutableAndCanonicalAcrossOrdering() throws Exception {
    String token = token("t-seal", "*", ALL);
    JsonNode first = create(token, "[\"b\",\"a\"]", "LTE");
    String id = first.get("id").asString();
    assertEquals(409, post("/v1/cases/"+id+":seal", token, "{\"expected_revision\":7}").statusCode());
    JsonNode sealed = body(post("/v1/cases/"+id+":seal", token, "{\"expected_revision\":1}"),200);
    JsonNode second = create(token, "[\"a\",\"b\"]", "LTE");
    JsonNode sealed2 = body(post("/v1/cases/"+second.get("id").asString()+":seal", token, "{\"expected_revision\":1}"),200);
    assertEquals(sealed.get("case_digest").asString(), sealed2.get("case_digest").asString());
    assertEquals(409, post("/v1/cases/"+id+":seal", token, "{\"expected_revision\":2}").statusCode());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE uacrf_assessment.analysis_case SET workload_id='bad' WHERE tenant_id=? AND id=?", "t-seal", id));
    assertEquals(sealed.get("case_digest").asString(), body(get("/v1/cases/"+id,token),200).get("case_digest").asString());
    assertThat(body(post("/internal/v1/cases/"+id+":receipt",token,"{}"),200).toString()).contains("assessment", "required_source_kinds");
  }
  @Test void inclusiveBoundariesAndEvidenceRules() throws Exception {
    String token = token("t-rules", "*", ALL); String id = sealed(token, "[\"a\",\"b\"]", "LTE");
    String a = measurement("r1","a","2026-01-01T00:59:00Z","10","ms");
    String b = measurement("r2","b","2026-01-01T00:59:30Z","10","ms");
    assertEquals("CONFIRMED_SUCCESS", eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b).get("outcome").asString());
    assertEquals("INSUFFICIENT_EVIDENCE", eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a).get("outcome").asString());
    assertEquals("INSUFFICIENT_EVIDENCE", eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b.replace("ms", "s")).get("outcome").asString());
    JsonNode ignored = eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b.replace("00:59:30", "01:00:01"));
    assertEquals("INSUFFICIENT_EVIDENCE", ignored.get("outcome").asString()); assertThat(ignored.toString()).contains("AFTER_CUTOFF");
    JsonNode outside = eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b.replace("2026-01-01T00:59:30Z", "2025-12-31T23:59:59Z"));
    assertEquals("INSUFFICIENT_EVIDENCE", outside.get("outcome").asString()); assertThat(outside.toString()).contains("OUTSIDE_WINDOW");
    JsonNode precise = eval(token,id,"2026-01-01T01:00:00.0000001Z","SHADOW",a+","+b.replace("2026-01-01T00:59:30Z", "2026-01-01T01:00:00.0000009Z"));
    assertEquals("INSUFFICIENT_EVIDENCE", precise.get("outcome").asString());
    assertThat(precise.toString()).contains("AFTER_CUTOFF");
    eval(token,id,"2026-01-01T01:00:00.0000000Z","SHADOW",a+","+b);
    assertEquals(precise.get("id").asString(), body(get("/v1/cases/"+id+"/evaluations:latest",token),200).get("id").asString());
    String gte = sealed(token,"[\"a\"]","GTE");
    assertEquals("CONFIRMED_SUCCESS", eval(token,gte,"2026-01-01T01:00:00Z","SHADOW",a).get("outcome").asString());
  }
  @Test void correctionReplayAndLatestCutoffPreserveHistory() throws Exception {
    String token = token("t-history", "*", ALL); String id = sealed(token,"[\"a\"]","LTE");
    String good = measurement("receipt","a","2026-01-01T00:59:00Z","5","ms");
    JsonNode old = eval(token,id,"2026-01-01T01:00:00Z","LIVE",good);
    JsonNode replay = eval(token,id,"2026-01-01T01:00:00Z","LIVE",good);
    assertEquals(old.get("result_digest").asString(),replay.get("result_digest").asString());
    assertEquals("SKIPPED", old.get("dependency_vector").get("receipt_verification").asString());
    JsonNode corrected = eval(token,id,"2026-01-01T01:00:00Z","LIVE",good.replace("\"value\":5", "\"value\":20"));
    assertEquals("CONFIRMED_BREACH", corrected.get("outcome").asString()); assertEquals(3,corrected.get("revision").asInt());
    assertNotEquals(old.get("result_digest").asString(), corrected.get("result_digest").asString());
    assertEquals("CONFIRMED_SUCCESS",body(get("/v1/evaluations/"+old.get("id").asString(),token),200).get("outcome").asString());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE uacrf_assessment.evaluation SET outcome='CONFIRMED_BREACH' WHERE tenant_id=? AND id=?", "t-history", old.get("id").asString()));
    eval(token,id,"2026-01-01T02:00:00Z","LIVE","");
    eval(token,id,"2026-01-01T01:00:00Z","LIVE",good);
    assertEquals("INSUFFICIENT_EVIDENCE",body(get("/v1/cases/"+id+"/evaluations:latest",token),200).get("outcome").asString());
    assertEquals(5, body(get("/v1/cases/"+id+"/evaluations",token),200).size());
  }
  @Test void modesRecommendationsAndLatestPerSource() throws Exception {
    String token = token("t-mode", "*", ALL); String id = sealed(token,"[\"a\"]","LTE");
    String older = measurement("r1","a","2026-01-01T00:58:00Z","5","ms"), newer = measurement("r2","a","2026-01-01T00:59:00Z","20","ms");
    JsonNode shadow = eval(token,id,"2026-01-01T01:00:00Z","SHADOW",older+","+newer);
    assertEquals("CONFIRMED_BREACH", shadow.get("outcome").asString()); assertThat(shadow.toString()).contains("SUPERSEDED");
    eval(token,id,"2026-01-01T01:00:00Z","DRY_RUN",newer);
    assertEquals(0,body(get("/v1/recommendations?workload_id=w1",token),200).size());
    eval(token,id,"2026-01-01T01:00:00Z","LIVE",newer);
    JsonNode recs = body(get("/v1/recommendations?workload_id=w1",token),200); assertEquals(1,recs.size()); assertFalse(recs.get(0).get("executable").asBoolean());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE uacrf_assessment.recommendation SET executable=true WHERE tenant_id='t-mode'"));
    assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event WHERE tenant_id='t-mode'",Integer.class));
  }
  @Test void authorizationAndTenantIsolation() throws Exception {
    String token = token("t-auth","*",ALL); String id = sealed(token,"[\"a\"]","LTE");
    String other = token("t-other","*",ALL); assertEquals(404,get("/v1/cases/"+id,other).statusCode());
    assertEquals(403,get("/v1/cases/"+id,token("t-auth","*",List.of("assessment.case.write"))).statusCode());
    assertEquals(403,post("/v1/cases",token("t-auth","service-1",ALL),caseJson("[\"a\"]","LTE")).statusCode());
    assertEquals(403,post("/v1/cases/"+id+":evaluate",token("t-auth","service-1",ALL),evalJson("2026-01-01T01:00:00Z","LIVE","")).statusCode());
    assertEquals(403,post("/v1/cases/"+id+":evaluate",token("t-auth","*",List.of("assessment.case.read")),evalJson("2026-01-01T01:00:00Z","LIVE","")).statusCode());
  }
  private JsonNode create(String token,String kinds,String comparator) throws Exception { return body(post("/v1/cases",token,caseJson(kinds,comparator)),201); }
  private String sealed(String token,String kinds,String comparator) throws Exception { String id=create(token,kinds,comparator).get("id").asString(); body(post("/v1/cases/"+id+":seal",token,"{\"expected_revision\":1}"),200); return id; }
  private static String caseJson(String kinds,String comparator) { return "{\"workload_id\":\"w1\",\"purpose\":\"assessment\",\"metric\":\"latency\",\"comparator\":\""+comparator+"\",\"threshold\":10,\"unit\":\"ms\",\"window_seconds\":3600,\"objective_ref\":{\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},\"graph_ref\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},\"required_source_kinds\":"+kinds+"}"; }
  private static String measurement(String receipt,String kind,String at,String value,String unit) { return "{\"source_receipt_id\":\""+receipt+"\",\"source_kind\":\""+kind+"\",\"observed_at\":\""+at+"\",\"value\":"+value+",\"unit\":\""+unit+"\"}"; }
  private static String evalJson(String cutoff,String mode,String inputs) { return "{\"observation_cutoff\":\""+cutoff+"\",\"mode\":\""+mode+"\",\"measurements\":["+inputs+"]}"; }
  private JsonNode eval(String token,String id,String cutoff,String mode,String inputs) throws Exception { return body(post("/v1/cases/"+id+":evaluate",token,evalJson(cutoff,mode,inputs)),201); }
  private JsonNode body(HttpResponse<String> response,int status) { assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body()); }
  private HttpResponse<String> post(String path,String token,String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
  private HttpResponse<String> get(String path,String token) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString()); }
  private static String token(String tenant,String scope,List<String> capabilities) { DelegationPrincipal p = new DelegationPrincipal("https://uacrf.local/s01|user",tenant,"env-1",scope,"https://idp.example.test","user",capabilities,List.of(),List.of()); return "Bearer "+new DelegationTokenIssuer(ENCODER,Clock.systemUTC(),"https://uacrf.local/s01",KEY_ID).issue(p,"S07"); }
  private static KeyPair keyPair() { try { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
