package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2AuthorizedSelectionLiveTests {
  @Test
  void liveOwnerSelectionReturnsAndPersistsExactActiveObjectiveContext() throws Exception {
    try (LiveM2TestSupport.Environment environment = LiveM2TestSupport.environment()) {
      String principalId = LiveM2TestSupport.principalId("slo-reader");
      String actorSubject = LiveM2TestSupport.principalId("slo-actor");
      environment.registerActor(principalId, actorSubject);

      String suffix = UUID.randomUUID().toString();
      String workloadId = "workload-" + suffix;
      String serviceId = "service-" + suffix;
      LiveM2TestSupport.Fixture fixture =
          environment.createAcceptedGraph(suffix, workloadId, serviceId);
      Map<String, Object> projection =
          environment.awaitGraphProjection(fixture.graphId(), principalId, actorSubject);
      assertEquals("PROJECTED", projection.get("projection_status"));
      assertEquals(projection.get("expected_digest"), projection.get("actual_digest"));
      assertEquals(Boolean.TRUE, projection.get("content_integrity_valid"));
      assertEquals(List.of(), projection.get("missing_nodes"));
      assertEquals(List.of(), projection.get("extra_nodes"));
      assertEquals(List.of(), projection.get("missing_edges"));
      assertEquals(List.of(), projection.get("extra_edges"));
      LiveM2TestSupport.Response graphReceipt =
          environment.request(
              environment.topologyUrl(),
              "POST",
              "/internal/v1/graphs/" + fixture.graphId() + ":receipt",
              environment.topologyToken(principalId, actorSubject),
              null,
              null);
      assertEquals(200, graphReceipt.statusCode(), graphReceipt.body());
      Map<String, Object> receipt = LiveM2TestSupport.readJson(graphReceipt.body());
      assertEquals("PROJECTED", receipt.get("projection_status"));
      assertEquals(Boolean.TRUE, receipt.get("eligibility"));
      String objectiveId = "objective-" + suffix;
      environment.createAndActivateObjective(workloadId, objectiveId, "250");

      Map<String, Object> objective = environment.objective(objectiveId);
      long revision = ((Number) objective.get("revision")).longValue();
      String contentDigest = (String) objective.get("content_digest");
      assertNotNull(contentDigest);
      String expectedSetDigest =
          sha256(workloadId + "|" + objectiveId + ":" + revision + ":" + contentDigest);

      String bearer = environment.scopedTopologyToken(principalId, actorSubject, serviceId);
      String otherScope = environment.scopedTopologyToken(principalId, actorSubject, "other-service");
      assertEquals(403, environment.request(environment.topologyUrl(), "GET",
          "/v1/graphs/" + fixture.graphId(), bearer, null, null).statusCode());
      assertEquals(403, environment.request(environment.topologyUrl(), "POST",
          "/internal/v1/graphs/" + fixture.graphId() + ":receipt", bearer, null, null).statusCode());
      LiveM2TestSupport.Response missingToken =
          environment.request(
              environment.topologyUrl(),
              "POST",
              "/v1/graphs/subgraphs:select",
              null,
              "{}",
              "m2-live-missing-token-" + suffix);
      assertEquals(401, missingToken.statusCode());

      LiveM2TestSupport.Response accepted =
          environment.select(fixture, bearer, suffix);
      assertEquals(202, accepted.statusCode(), accepted.body());
      String commandId = (String) LiveM2TestSupport.readJson(accepted.body()).get("command_id");
      assertNotNull(commandId);
      assertEquals(404, environment.request(environment.topologyUrl(), "GET",
          "/v1/commands/" + commandId, otherScope, null, null).statusCode());

      Map<String, Object> status = environment.awaitCommand(commandId, bearer);
      assertEquals("SUCCEEDED", status.get("status"));
      Map<?, ?> advisory = (Map<?, ?>) status.get("advisory_result");
      Map<?, ?> slo = (Map<?, ?>) advisory.get("slo_objectives");
      assertEquals(expectedSetDigest, slo.get("set_digest"));
      List<?> objectives = (List<?>) slo.get("objectives");
      assertEquals(objectiveId, ((Map<?, ?>) objectives.getFirst()).get("id"));
      List<?> edges = (List<?>) advisory.get("edges");
      assertFalse(LiveM2TestSupport.json(Map.of("edges", edges)).contains("GOVERNED_BY_SLO"));

      LiveM2TestSupport.Response persisted =
          environment.getCommand(commandId, principalId, actorSubject);
      assertEquals(200, persisted.statusCode());
      Map<String, Object> persistedStatus = LiveM2TestSupport.readJson(persisted.body());
      assertEquals("SUCCEEDED", persistedStatus.get("status"));
      Map<?, ?> persistedAdvisory = (Map<?, ?>) persistedStatus.get("advisory_result");
      Map<?, ?> persistedSlo = (Map<?, ?>) persistedAdvisory.get("slo_objectives");
      assertEquals(expectedSetDigest, persistedSlo.get("set_digest"));
      environment.verifyStubHealth();
      assertEquals(0, environment.evidenceSummaryCalls());
    }
  }

  private static String sha256(String value) throws Exception {
    return java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
