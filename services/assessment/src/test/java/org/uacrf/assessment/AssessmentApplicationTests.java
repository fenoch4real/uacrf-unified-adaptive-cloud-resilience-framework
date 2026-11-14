package org.uacrf.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.time.Clock;
import java.time.Instant;
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
  private static final List<String> ALL = List.of("assessment.case.read", "assessment.case.write",
      "assessment.evaluation.run", "assessment.evaluation.read", "assessment.recommendation.read",
      "assessment.model.read", "assessment.model.write", "assessment.model.review",
      "assessment.incident.read", "assessment.incident.write", "assessment.learning.write");
  @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81").withDatabaseName("uacrf_assessment").withUsername("assessment_test").withPassword("assessment_test");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl); p.add("spring.datasource.username", POSTGRES::getUsername); p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("uacrf.delegation.public-key-base64", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded())); p.add("uacrf.delegation.key-id", () -> KEY_ID);
    p.add("uacrf.assessment.models.enabled", () -> "true");
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
    JsonNode receipt = body(post("/internal/v1/cases/"+id+":receipt",token,"{}"),200);
    assertThat(receipt.toString()).contains("assessment", "required_source_kinds");
    assertEquals("w1", receipt.get("workload_id").asString());
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
    assertEquals(404,post("/internal/v1/cases/"+id+":receipt",other,"{}").statusCode());
    assertEquals(403,post("/internal/v1/cases/"+id+":receipt",
        token("t-auth","*",List.of("assessment.case.write")),"{}").statusCode());
    assertEquals(403,get("/v1/cases/"+id,token("t-auth","*",List.of("assessment.case.write"))).statusCode());
    assertEquals(403,post("/v1/cases",token("t-auth","service-1",ALL),caseJson("[\"a\"]","LTE")).statusCode());
    assertEquals(403,post("/v1/cases/"+id+":evaluate",token("t-auth","service-1",ALL),evalJson("2026-01-01T01:00:00Z","LIVE","")).statusCode());
    assertEquals(403,post("/v1/cases/"+id+":evaluate",token("t-auth","*",List.of("assessment.case.read")),evalJson("2026-01-01T01:00:00Z","LIVE","")).statusCode());
  }
  @Test void modelVersionsAreImmutableUniqueAndTenantScoped() throws Exception {
    String owner = token("t-model-immutable", "*", ALL);
    JsonNode retained = modelVersion(owner, "m-immutable", "1.0");
    String id = retained.get("id").asString();
    assertEquals(403, get("/v1/model-versions/" + id,
        token("t-model-immutable", "*", List.of("assessment.model.write"))).statusCode());
    assertEquals(403, post("/v1/model-versions", token("t-model-immutable", "*",
        List.of("assessment.model.read")), modelJson("m-forbidden", "1.0")).statusCode());
    assertEquals("CANDIDATE", retained.get("state").asString());
    assertEquals(1, retained.get("revision").asInt());
    assertEquals(409, post("/v1/model-versions", owner,
        modelJson("m-immutable", "1.0")).statusCode());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.model_version SET model_card_ref='changed' WHERE tenant_id=? AND id=?",
        "t-model-immutable", id));
    assertEquals(404, get("/v1/model-versions/" + id,
        token("t-model-other", "*", ALL)).statusCode());
    assertEquals(1, body(get("/v1/model-versions?model_id=m-immutable", owner), 200).size());
    assertEquals(0, body(get("/v1/model-versions", token("t-model-other", "*", ALL)), 200).size());
  }
  @Test void reviewRequiresIndependentReviewerVerifiedReceiptsAndPassingEvaluation() throws Exception {
    String proposer = token("t-model-review", "*", ALL);
    String id = modelVersion(proposer, "m-review", "1.0").get("id").asString();
    String reviewer = token("t-model-review", "*", ALL, "reviewer");
    assertEquals(409, post(reviewPath(id), proposer, reviewJson(1, "APPROVE", "[]")).statusCode());
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(2, "APPROVE", "[]")).statusCode());
    String missingId = UUID.randomUUID().toString();
    assertEquals(404, post(reviewPath(id), reviewer, reviewJson(1, "APPROVE",
        "[\"" + missingId + "\"]")).statusCode());
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String unverified = seedEvaluation("t-model-review", caseId, "SKIPPED", "CONFIRMED_SUCCESS");
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(1, "APPROVE",
        "[\"" + unverified + "\"]")).statusCode());
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(1, "APPROVE", "[]")).statusCode());
    String passing = seedEvaluation("t-model-review", caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    JsonNode approved = body(post(reviewPath(id), reviewer, reviewJson(1, "APPROVE",
        "[\"" + passing + "\"]")), 200);
    assertEquals("APPROVED", approved.get("state").asString());
    assertEquals(2, approved.get("revision").asInt());
    assertThat(approved.get("reviewed_by").asString()).endsWith("|reviewer");
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(2, "REJECT", "[]")).statusCode());
    assertEquals(409, post("/v1/model-versions/" + id + ":promote",
        token("t-model-review", "*", ALL, "reviewer"),
        promoteJson(2, "SHADOW")).statusCode());
  }
  @Test void evaluationIdsAreTenantScopedAndInsufficientEvidenceCannotBeReviewed() throws Exception {
    String proposer = token("t-model-cross", "*", ALL);
    String id = modelVersion(proposer, "m-cross", "1.0").get("id").asString();
    String reviewer = token("t-model-cross", "*", ALL, "reviewer");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String foreignCase = sealed(token("t-model-foreign", "*", ALL), "[\"a\"]", "LTE");
    String foreignEvaluation = seedEvaluation("t-model-foreign", foreignCase, "VERIFIED", "CONFIRMED_SUCCESS");
    assertEquals(404, post(reviewPath(id), reviewer,
        reviewJson(1, "APPROVE", "[\"" + foreignEvaluation + "\"]")).statusCode());
    String insufficient = seedEvaluation("t-model-cross", caseId, "VERIFIED", "INSUFFICIENT_EVIDENCE");
    assertEquals(409, post(reviewPath(id), reviewer,
        reviewJson(1, "REJECT", "[\"" + insufficient + "\"]")).statusCode());
  }
  @Test void promotionIsIndependentExclusiveAndLimitedToAdvisoryModes() throws Exception {
    String proposer = token("t-model-promote", "*", ALL);
    String reviewer = token("t-model-promote", "*", ALL, "reviewer");
    String promoter = token("t-model-promote", "*", ALL, "promoter");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String passing = seedEvaluation("t-model-promote", caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    String first = modelVersion(proposer, "m-promote", "1.0").get("id").asString();
    body(post(reviewPath(first), reviewer, reviewJson(1, "APPROVE", "[\"" + passing + "\"]")), 200);
    String second = modelVersion(proposer, "m-promote", "2.0").get("id").asString();
    body(post(reviewPath(second), reviewer, reviewJson(1, "APPROVE", "[\"" + passing + "\"]")), 200);
    assertEquals(409, post("/v1/model-versions/" + first + ":promote", proposer,
        promoteJson(2, "SHADOW")).statusCode());
    assertEquals(409, post("/v1/model-versions/" + first + ":promote", promoter,
        promoteJson(3, "SHADOW")).statusCode());
    assertEquals(400, post("/v1/model-versions/" + first + ":promote", promoter,
        promoteJson(2, "LIVE")).statusCode());
    JsonNode activeShadow = body(post("/v1/model-versions/" + first + ":promote", promoter,
        promoteJson(2, "SHADOW")), 200);
    assertEquals("ACTIVE", activeShadow.get("state").asString());
    assertEquals("SHADOW", activeShadow.get("target_mode").asString());
    JsonNode activeAdvisory = body(post("/v1/model-versions/" + second + ":promote", promoter,
        promoteJson(2, "ADVISORY")), 200);
    assertEquals("ACTIVE", activeAdvisory.get("state").asString());
    String third = modelVersion(proposer, "m-promote", "3.0").get("id").asString();
    body(post(reviewPath(third), reviewer, reviewJson(1, "APPROVE", "[\"" + passing + "\"]")), 200);
    body(post("/v1/model-versions/" + third + ":promote", promoter,
        promoteJson(2, "SHADOW")), 200);
    assertEquals("SUPERSEDED", body(get("/v1/model-versions/" + first, proposer), 200)
        .get("state").asString());
    assertEquals("ACTIVE", body(get("/v1/model-versions/" + second, proposer), 200)
        .get("state").asString());
    assertEquals("ACTIVE", body(get("/v1/model-versions/" + third, proposer), 200)
        .get("state").asString());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-model-promote' AND model_id='m-promote' "
        + "AND target_mode='SHADOW' AND state='ACTIVE'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-model-promote' AND model_id='m-promote' "
        + "AND target_mode='ADVISORY' AND state='ACTIVE'", Integer.class));
    assertTrue(jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id='t-model-promote' AND aggregate_type='MODEL_VERSION'", Integer.class) >= 10);
  }

  @Test void incidentRequiresVerifiedMeasurementsAtOrBeforeObservationAndIsAttributable() throws Exception {
    String owner = token("t-incident-valid", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-valid", caseId, "2026-01-01T01:00:00Z", "VERIFIED",
        measurementReceipt("r-incident", "2026-01-01T00:59:00Z"));
    String forged = incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-incident\"]")
        .replace("\"reason\":\"retain incident evidence\"}",
            "\"reason\":\"retain incident evidence\",\"capabilities\":[\"assessment.incident.write\"]}");
    String readOnly = token("t-incident-valid", "*", List.of("assessment.incident.read"));
    assertEquals(403, post("/v1/incidents", readOnly,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-incident\"]")).statusCode());
    assertEquals(400, post("/v1/incidents", readOnly, forged).statusCode());
    String incidentWriter = token("t-incident-valid", "*",
        List.of("assessment.incident.write"), "incident-writer");
    JsonNode incident = body(post("/v1/incidents", incidentWriter,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-incident\",\"r-incident\"]")), 201);
    assertEquals(1, incident.get("revision").asInt());
    assertEquals(List.of("r-incident"), json.convertValue(incident.get("evidence_refs"), List.class));
    assertThat(incident.get("created_by").asString()).endsWith("|incident-writer");
    assertEquals(200, get("/v1/incidents/" + incident.get("id").asString(), owner).statusCode());
    assertEquals(404, get("/v1/incidents/" + incident.get("id").asString(),
        token("t-incident-other", "*", ALL)).statusCode());
    assertEquals(403, get("/v1/incidents/" + incident.get("id").asString(),
        token("t-incident-valid", "*", List.of("assessment.incident.write"))).statusCode());
  }

  @Test void incidentRejectsUnverifiedMissingAndStaleEvaluationEvidence() throws Exception {
    String owner = token("t-incident-invalid", "*", ALL);
    String cutoff = "2026-01-01T01:00:00Z";
    String unverifiedCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", unverifiedCase, cutoff, "SKIPPED",
        measurementReceipt("r-unverified", "2026-01-01T00:59:00Z"));
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(unverifiedCase, "w1", cutoff, "[\"r-unverified\"]")).statusCode());

    String noReceiptCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", noReceiptCase, cutoff, "VERIFIED", "[]");
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(noReceiptCase, "w1", cutoff, "[\"r-missing\"]")).statusCode());

    String futureEvaluationCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", futureEvaluationCase, "2026-01-01T01:01:00Z",
        "VERIFIED", measurementReceipt("r-future", "2026-01-01T01:00:30Z"));
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(futureEvaluationCase, "w1", cutoff, "[\"r-future\"]")).statusCode());

    String futureReceiptCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", futureReceiptCase, "2026-01-01T00:59:00Z",
        "VERIFIED", measurementReceipt("r-late", "2026-01-01T01:00:30Z"));
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(futureReceiptCase, "w1", cutoff, "[\"r-late\"]")).statusCode());
  }

  @Test void incidentRejectsCrossTenantWrongWorkloadAndUnreferencedMeasurements() throws Exception {
    String owner = token("t-incident-scope", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-scope", caseId, "2026-01-01T01:00:00Z", "VERIFIED",
        measurementReceipt("r-scoped", "2026-01-01T00:59:00Z"));
    assertEquals(404, post("/v1/incidents", token("t-incident-foreign", "*", ALL),
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-scoped\"]")).statusCode());
    assertEquals(404, post("/v1/incidents", owner,
        incidentJson(caseId, "w-other", "2026-01-01T01:00:00Z", "[\"r-scoped\"]")).statusCode());
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-other\"]")).statusCode());
  }

  @Test void learningRecordsAreImmutableRevisionFencedProposalsWithTransactionalOutbox() throws Exception {
    String owner = token("t-learning-proposal", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-learning-proposal", caseId, "2026-01-01T01:00:00Z", "VERIFIED",
        measurementReceipt("r-learning", "2026-01-01T00:59:00Z"));
    JsonNode incident = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-learning\"]")), 201);
    String incidentId = incident.get("id").asString();
    String learningWriter = token("t-learning-proposal", "*",
        List.of("assessment.learning.write"), "learning-writer");
    String recordBody = learningJson(incidentId, 1, "[\"r-learning\",\"r-learning\"]");
    assertEquals(409, post("/v1/learning-records", learningWriter,
        learningJson(incidentId, 2, "[\"r-learning\"]")).statusCode());
    assertEquals(409, post("/v1/learning-records", learningWriter,
        learningJson(incidentId, 1, "[\"r-not-retained\"]")).statusCode());
    assertEquals(404, post("/v1/learning-records", token("t-learning-foreign", "*", ALL),
        learningJson(incidentId, 1, "[\"r-learning\"]")).statusCode());
    assertEquals(400, post("/v1/learning-records", learningWriter, recordBody.replace(
        "\"owner_id\":\"owner-1\"", "\"owner_id\":\"\"")).statusCode());

    int modelCount = jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-learning-proposal'", Integer.class);
    JsonNode proposed = body(post("/v1/learning-records", learningWriter, recordBody), 201);
    assertEquals("PROPOSED", proposed.get("state").asString());
    assertEquals(1, proposed.get("revision").asInt());
    assertEquals(1, proposed.get("expected_revision").asInt());
    assertThat(proposed.get("proposed_by").asString()).endsWith("|learning-writer");
    assertEquals(List.of("r-learning"), json.convertValue(proposed.get("evidence_refs"), List.class));
    assertThat(proposed.toString()).contains("owner-1", "closure_test");
    assertEquals(modelCount, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-learning-proposal'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id='t-learning-proposal' AND event_type='uacrf.s07.learning.proposed.v1' "
        + "AND aggregate_id=?", Integer.class, proposed.get("id").asString()));
    assertEquals("https://uacrf.local/s01|learning-writer", jdbc.queryForObject(
        "SELECT payload->>'actor_id' FROM uacrf_assessment.outbox_event "
            + "WHERE tenant_id='t-learning-proposal' AND aggregate_id=?",
        String.class, proposed.get("id").asString()));
    assertEquals("PROPOSED", jdbc.queryForObject(
        "SELECT payload->'minimal_outcome_metadata'->>'state' "
            + "FROM uacrf_assessment.outbox_event WHERE tenant_id='t-learning-proposal' "
            + "AND aggregate_id=?",
        String.class, proposed.get("id").asString()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.learning_record SET state='APPROVED' WHERE tenant_id=? AND id=?",
        "t-learning-proposal", proposed.get("id").asString()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "DELETE FROM uacrf_assessment.learning_record WHERE tenant_id=? AND id=?",
        "t-learning-proposal", proposed.get("id").asString()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.incident SET reason='changed' WHERE tenant_id=? AND id=?",
        "t-learning-proposal", incidentId));
  }

  private JsonNode create(String token,String kinds,String comparator) throws Exception { return body(post("/v1/cases",token,caseJson(kinds,comparator)),201); }
  private JsonNode modelVersion(String token, String model, String version) throws Exception {
    return body(post("/v1/model-versions", token, modelJson(model, version)), 201);
  }
  private String seedEvaluation(String tenant, String caseId, String verification, String outcome) {
    String id = UUID.randomUUID().toString();
    int revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM "
        + "uacrf_assessment.evaluation WHERE tenant_id=? AND environment_id='env-1' AND case_id=?",
        Integer.class, tenant, caseId);
    String vector = "{\"receipt_verification\":\"" + verification
        + "\",\"objective_receipt\":{\"objective_id\":\"o1\"},\"graph_receipt\":{\"graph_id\":\"g1\"},"
        + "\"measurement_receipts\":[{\"evidence_id\":\"r1\",\"payload_digest\":\""
        + "a".repeat(64) + "\",\"usable_as_evidence\":true}]}";
    jdbc.update("INSERT INTO uacrf_assessment.evaluation "
        + "(tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,"
        + "cutoff_nano,mode,outcome,dependency_vector,result_digest) "
        + "VALUES (?, 'env-1', ?, ?, ?, '2026-01-01T00:00:00Z', 1767225600,0,'SHADOW',? ,?::jsonb,?)",
        tenant, id, caseId, revision, outcome, vector, "b".repeat(64));
    return id;
  }
  private static String modelJson(String model, String version) {
    return "{\"model_id\":\"" + model + "\",\"version\":\"" + version
        + "\",\"artifact_ref\":\"artifact://model\",\"digest\":\"" + "c".repeat(64)
        + "\",\"model_card_ref\":\"card://model\",\"evaluation_pack_ref\":\"eval://pack\","
        + "\"reason\":\"candidate for governance\"}";
  }
  private static String reviewPath(String id) { return "/v1/model-versions/" + id + ":review"; }
  private static String reviewJson(int revision, String decision, String evaluations) {
    return "{\"expected_revision\":" + revision + ",\"decision\":\"" + decision
        + "\",\"evaluation_result_ids\":" + evaluations + ",\"reason\":\"independent review\"}";
  }
  private static String promoteJson(int revision, String mode) {
    return "{\"expected_revision\":" + revision + ",\"target_mode\":\"" + mode
        + "\",\"reason\":\"bounded advisory activation\"}";
  }
  private String sealed(String token,String kinds,String comparator) throws Exception { String id=create(token,kinds,comparator).get("id").asString(); body(post("/v1/cases/"+id+":seal",token,"{\"expected_revision\":1}"),200); return id; }
  private static String caseJson(String kinds,String comparator) { return "{\"workload_id\":\"w1\",\"purpose\":\"assessment\",\"metric\":\"latency\",\"comparator\":\""+comparator+"\",\"threshold\":10,\"unit\":\"ms\",\"window_seconds\":3600,\"objective_ref\":{\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},\"graph_ref\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},\"required_source_kinds\":"+kinds+"}"; }
  private static String incidentJson(String caseId, String workloadId, String observedFrom,
      String evidenceRefs) {
    return "{\"workload_id\":\"" + workloadId + "\",\"source_case_id\":\"" + caseId
        + "\",\"observed_from\":\"" + observedFrom + "\",\"description\":\"observed incident\","
        + "\"evidence_refs\":" + evidenceRefs + ",\"reason\":\"retain incident evidence\"}";
  }
  private static String learningJson(String incidentId, int expectedRevision, String evidenceRefs) {
    return "{\"incident_id\":\"" + incidentId + "\",\"experiment_id\":\"exp-1\","
        + "\"hypotheses\":[\"recovery path may be delayed\"],\"action_items\":[{"
        + "\"description\":\"test the recovery path\",\"owner_id\":\"owner-1\","
        + "\"due_at\":\"2026-02-01T00:00:00Z\",\"proposed_version_ref\":\"candidate://v2\","
        + "\"closure_test\":\"replay verified evidence\"}],\"evidence_refs\":" + evidenceRefs
        + ",\"expected_revision\":" + expectedRevision
        + ",\"reason\":\"propose follow-up only\"}";
  }
  private static String measurementReceipt(String id, String observedAt) {
    return "{\"evidence_id\":\"" + id + "\",\"purpose\":\"assessment\","
        + "\"qualification_state\":\"QUALIFIED\",\"usable_as_evidence\":true,"
        + "\"payload_digest\":\"" + "a".repeat(64) + "\",\"revision\":1,"
        + "\"observed_at\":\"" + observedAt + "\",\"source_kind\":\"a\","
        + "\"metric\":\"latency\",\"value\":5,\"unit\":\"ms\"}";
  }
  private void seedIncidentEvaluation(String tenant, String caseId, String cutoff,
      String verification, String receipts) {
    String id = UUID.randomUUID().toString();
    int revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM "
        + "uacrf_assessment.evaluation WHERE tenant_id=? AND environment_id='env-1' AND case_id=?",
        Integer.class, tenant, caseId);
    Instant cutoffInstant = Instant.parse(cutoff);
    String receiptList = receipts.stripLeading().startsWith("{") ? "[" + receipts + "]" : receipts;
    String vector = "{\"receipt_verification\":\"" + verification
        + "\",\"objective_receipt\":{\"workload_id\":\"w1\",\"set_digest\":\""
        + "b".repeat(64) + "\",\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},"
        + "\"graph_receipt\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},"
        + "\"measurement_receipts\":" + receiptList + "}";
    jdbc.update("INSERT INTO uacrf_assessment.evaluation "
        + "(tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,"
        + "cutoff_nano,mode,outcome,dependency_vector,result_digest) "
        + "VALUES (?, 'env-1', ?, ?, ?, ?, ?, ?, 'SHADOW','CONFIRMED_SUCCESS',?::jsonb,?)",
        tenant, id, caseId, revision, cutoff, cutoffInstant.getEpochSecond(), cutoffInstant.getNano(),
        vector, "c".repeat(64));
  }
  private static String measurement(String receipt,String kind,String at,String value,String unit) { return "{\"source_receipt_id\":\""+receipt+"\",\"source_kind\":\""+kind+"\",\"observed_at\":\""+at+"\",\"value\":"+value+",\"unit\":\""+unit+"\"}"; }
  private static String evalJson(String cutoff,String mode,String inputs) { return "{\"observation_cutoff\":\""+cutoff+"\",\"mode\":\""+mode+"\",\"measurements\":["+inputs+"]}"; }
  private JsonNode eval(String token,String id,String cutoff,String mode,String inputs) throws Exception { return body(post("/v1/cases/"+id+":evaluate",token,evalJson(cutoff,mode,inputs)),201); }
  private JsonNode body(HttpResponse<String> response,int status) { assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body()); }
  private HttpResponse<String> post(String path,String token,String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
  private HttpResponse<String> get(String path,String token) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString()); }
  private static String token(String tenant,String scope,List<String> capabilities) { return token(tenant, scope, capabilities, "user"); }
  private static String token(String tenant,String scope,List<String> capabilities, String subject) { DelegationPrincipal p = new DelegationPrincipal("https://uacrf.local/s01|"+subject,tenant,"env-1",scope,"https://idp.example.test",subject,capabilities,List.of(),List.of()); return "Bearer "+new DelegationTokenIssuer(ENCODER,Clock.systemUTC(),"https://uacrf.local/s01",KEY_ID).issue(p,"S07"); }
  private static KeyPair keyPair() { try { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
