package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2EvidenceBackedTopologyLiveTests {
  @Test
  void qualifiedFixtureSupportsTopologyButConflictingReplayInvalidatesCurrentUse() throws Exception {
    try (LiveM2TestSupport.Environment environment = LiveM2TestSupport.environmentWithEvidence()) {
      String suffix = UUID.randomUUID().toString();
      String source = "workload-source-" + suffix;
      String target = "workload-target-" + suffix;
      String sourceVersion = "version-source-" + suffix;
      String targetVersion = "version-target-" + suffix;
      environment.registerCatalogFixture("service-source-" + suffix, source, "source-" + suffix);
      environment.registerCatalogFixture("service-target-" + suffix, target, "target-" + suffix);
      String proposer = LiveM2TestSupport.principalId("evidence-proposer");
      String reviewer = LiveM2TestSupport.principalId("evidence-reviewer");
      environment.registerActor(proposer, proposer, LiveM2TestSupport.TOPOLOGY_CAPABILITIES);
      environment.registerActor(reviewer, reviewer, LiveM2TestSupport.TOPOLOGY_CAPABILITIES);
      String proposalToken = environment.humanTopologyToken(proposer, proposer);
      String reviewToken = environment.humanTopologyToken(reviewer, reviewer);
      String submitToken = environment.evidenceToken("fixture-submitter", "fixture-submitter",
          List.of("evidence.event.write"));
      String evidenceReviewToken = environment.evidenceToken("fixture-reviewer", "fixture-reviewer",
          List.of("evidence.qualification.review", "evidence.event.read"));

      String payload = "{\"metric\":\"latency\",\"value\":1.25,\"unit\":\"ms\","
          + "\"window_start\":\"2026-01-01T00:00:00Z\",\"window_end\":\"2026-01-01T01:00:00Z\"}";
      String digest = sha256(payload);
      String sampleId = "sample-" + suffix;
      String sampleBody = LiveM2TestSupport.json(Map.of(
          "id", sampleId, "source_class", "SAMPLE_LOCAL", "source_ref", "sample:topology",
          "sequence", 1, "observed_at", "2026-01-01T01:00:00Z",
          "kind", "measurement.latency", "payload", payload, "payload_digest", digest));
      var sample = environment.request(environment.evidenceUrl(), "POST", "/internal/v1/observations",
          submitToken, sampleBody, null);
      assertEquals(202, sample.statusCode(), sample.body());
      assertTrue(sample.body().contains("SAMPLE_ONLY"));
      var sampleReview = environment.request(environment.evidenceUrl(), "POST",
          "/v1/evidence/" + sampleId + "/reviews", evidenceReviewToken,
          "{\"decision\":\"QUALIFY\",\"reason\":\"Fixture\",\"expected_revision\":1}", null);
      assertEquals(409, sampleReview.statusCode(), sampleReview.body());
      assertEquals("SAMPLE_NOT_QUALIFIABLE", LiveM2TestSupport.readJson(sampleReview.body()).get("code"));
      String sampleProposal = "sample-proposal-" + suffix;
      var sampleAttempt = environment.request(environment.topologyUrl(), "POST",
          "/v1/dependency-proposals:evidence-backed", proposalToken,
          proposal(sampleProposal, source, sourceVersion, target, targetVersion, sampleId, digest, 1),
          null);
      assertEquals(409, sampleAttempt.statusCode(), sampleAttempt.body());
      assertEquals("PROOF_UNAVAILABLE_OR_STALE",
          LiveM2TestSupport.readJson(sampleAttempt.body()).get("code"));
      assertEquals(404, environment.request(environment.topologyUrl(), "GET",
          "/v1/dependency-proposals/" + sampleProposal, reviewToken, null, null).statusCode());

      String evidenceId = "provider-fixture-" + suffix;
      String observation = LiveM2TestSupport.json(Map.ofEntries(
          Map.entry("id", evidenceId), Map.entry("source_class", "PROVIDER"),
          Map.entry("source_ref", "test:synthetic-provider-fixture"),
          Map.entry("sequence", 1), Map.entry("observed_at", "2026-01-01T01:00:00Z"),
          Map.entry("kind", "measurement.latency"),
          Map.entry("service_id", "service-source-" + suffix),
          Map.entry("schema_version", "UACRF_OBSERVATION_V1"),
          Map.entry("payload", payload), Map.entry("payload_digest", digest)));
      var submitted = environment.request(environment.evidenceUrl(), "POST", "/internal/v1/observations",
          submitToken, observation, null);
      assertEquals(202, submitted.statusCode(), submitted.body());
      assertTrue(submitted.body().contains("UNVERIFIED"));
      var qualified = environment.request(environment.evidenceUrl(), "POST",
          "/v1/evidence/" + evidenceId + "/reviews", evidenceReviewToken,
          "{\"decision\":\"QUALIFY\",\"reason\":\"Synthetic integration fixture\",\"expected_revision\":1}",
          null);
      assertEquals(200, qualified.statusCode(), qualified.body());
      String qualificationPath = "/internal/v1/qualifications/" + evidenceId + ":receipt";
      String qualificationRequest = LiveM2TestSupport.json(Map.of(
          "purpose", "assessment", "expected_digest", digest, "cutoff", "2999-01-01T00:00:00Z"));
      var qualification = environment.request(environment.evidenceUrl(), "POST", qualificationPath,
          evidenceReviewToken, qualificationRequest, null);
      assertEquals(200, qualification.statusCode(), qualification.body());
      assertEquals(Boolean.TRUE, LiveM2TestSupport.readJson(qualification.body()).get("usable_as_evidence"));

      String proposalId = "proposal-" + suffix;
      String request = proposal(proposalId, source, sourceVersion, target, targetVersion, evidenceId, digest, 2);
      assertEquals(401, environment.request(environment.topologyUrl(), "POST",
          "/v1/dependency-proposals:evidence-backed", null, request, null).statusCode());
      var proposed = environment.request(environment.topologyUrl(), "POST",
          "/v1/dependency-proposals:evidence-backed", proposalToken, request, null);
      assertEquals(201, proposed.statusCode(), proposed.body());
      var reviewed = environment.request(environment.topologyUrl(), "POST",
          "/v1/dependency-proposals/" + proposalId + ":review", reviewToken,
          "{\"decision\":\"ACCEPT\",\"expected_revision\":1}", null);
      assertEquals(200, reviewed.statusCode(), reviewed.body());
      assertEquals("ACCEPTED", LiveM2TestSupport.readJson(reviewed.body()).get("status"));
      var readBack = environment.request(environment.topologyUrl(), "GET",
          "/v1/dependency-proposals/" + proposalId, reviewToken, null, null);
      assertEquals(200, readBack.statusCode(), readBack.body());
      assertEquals("ACCEPTED", LiveM2TestSupport.readJson(readBack.body()).get("status"));
      var graph = environment.request(environment.topologyUrl(), "POST", "/v1/graphs:accept",
          reviewToken, "{\"scope\":\"all\",\"expected_revision_number\":0}", null);
      assertEquals(200, graph.statusCode(), graph.body());
      Map<String, Object> graphBody = LiveM2TestSupport.readJson(graph.body());
      String graphId = (String) graphBody.get("id");
      assertEquals(List.of(Map.of(
          "source_type", "agent_workload", "source_id", source, "relationship_type", "DEPENDS_ON",
          "target_type", "agent_workload", "target_id", target, "proposal_id", proposalId)),
          graphBody.get("edges"));
      Map<String, Object> reconciliation = environment.awaitGraphProjection(graphId, reviewer, reviewer);
      assertEquals(Boolean.TRUE, reconciliation.get("content_integrity_valid"));
      var current = environment.request(environment.topologyUrl(), "POST",
          "/internal/v1/graphs/" + graphId + ":receipt", reviewToken, null, null);
      assertEquals(200, current.statusCode(), current.body());
      assertEquals(Boolean.TRUE, LiveM2TestSupport.readJson(current.body()).get("eligibility"));

      var conflict = environment.request(environment.evidenceUrl(), "POST", "/internal/v1/observations",
          submitToken, observation.replace("\"observed_at\":\"2026-01-01T01:00:00Z\"",
              "\"observed_at\":\"2026-01-01T02:00:00Z\""), null);
      assertEquals(202, conflict.statusCode(), conflict.body());
      assertTrue(conflict.body().contains("REPLAY_CONFLICT"), conflict.body());
      var conflictedQualification = environment.request(environment.evidenceUrl(), "POST",
          qualificationPath, evidenceReviewToken, qualificationRequest, null);
      assertEquals(200, conflictedQualification.statusCode(), conflictedQualification.body());
      Map<String, Object> conflictedBody = LiveM2TestSupport.readJson(conflictedQualification.body());
      assertEquals(Boolean.FALSE, conflictedBody.get("usable_as_evidence"));
      assertEquals("Evidence has a retained source conflict.", conflictedBody.get("reason"));
      var staleProposal = environment.request(environment.topologyUrl(), "POST",
          "/v1/dependency-proposals:evidence-backed", proposalToken,
          proposal("stale-" + suffix, source, sourceVersion, target, targetVersion, evidenceId, digest, 2),
          null);
      assertEquals(409, staleProposal.statusCode(), staleProposal.body());
      assertEquals("PROOF_UNAVAILABLE_OR_STALE",
          LiveM2TestSupport.readJson(staleProposal.body()).get("code"));
      var stale = environment.request(environment.topologyUrl(), "POST",
          "/internal/v1/graphs/" + graphId + ":receipt", reviewToken, null, null);
      assertEquals(200, stale.statusCode(), stale.body());
      assertEquals(Boolean.FALSE, LiveM2TestSupport.readJson(stale.body()).get("eligibility"));
      environment.verifyStubHealth();
    }
  }

  private static String proposal(String id, String source, String sourceVersion, String target,
      String targetVersion, String evidenceId, String digest, int revision) {
    return LiveM2TestSupport.json(Map.ofEntries(
        Map.entry("id", id), Map.entry("source_type", "agent_workload"),
        Map.entry("source_id", source), Map.entry("source_version", sourceVersion),
        Map.entry("relationship_type", "DEPENDS_ON"), Map.entry("target_type", "agent_workload"),
        Map.entry("target_id", target), Map.entry("target_version", targetVersion),
        Map.entry("evidence_id", evidenceId), Map.entry("evidence_digest", digest),
        Map.entry("evidence_revision", revision)));
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
