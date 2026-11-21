package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2ObjectiveWorkloadOwnerLiveTests {
  @Test
  void onlyCurrentCatalogOwnerCanProposeAndActivateObjectives() throws Exception {
    try (LiveM2TestSupport.Environment environment =
        LiveM2TestSupport.environmentWithWorkloadOwnerChecks()) {
      String ownerId = LiveM2TestSupport.principalId("catalog-owner");
      String ownerSubject = LiveM2TestSupport.principalId("catalog-owner-subject");
      String nonOwnerId = LiveM2TestSupport.principalId("non-owner");
      String nonOwnerSubject = LiveM2TestSupport.principalId("non-owner-subject");
      List<String> objectiveCapabilities =
          List.of(
              "objectives.objective.write",
              "objectives.objective.activate",
              "objectives.objective.read");
      environment.registerActor(ownerId, ownerSubject, objectiveCapabilities);
      environment.registerActor(nonOwnerId, nonOwnerSubject, objectiveCapabilities);

      String suffix = UUID.randomUUID().toString();
      String serviceId = "service-" + suffix;
      String workloadId = "workload-" + suffix;
      environment.registerCatalogFixture(serviceId, workloadId, suffix, ownerId);

      String originalObjectiveId = "objective-original-" + suffix;
      environment.createAndActivateObjective(
          workloadId, originalObjectiveId, "250", ownerId, ownerSubject);

      String rejectedProposalId = "objective-non-owner-" + suffix;
      LiveM2TestSupport.Response rejectedProposal =
          environment.request(
              environment.objectivesUrl(),
              "POST",
              "/v1/objectives:local-idempotent-create",
              environment.objectiveToken(
                  nonOwnerId, nonOwnerSubject, LiveM2TestSupport.OBJECTIVE_PROPOSER),
              objectiveBody(rejectedProposalId, workloadId),
              "non-owner-create");
      assertEquals(403, rejectedProposal.statusCode(), rejectedProposal.body());
      assertEquals(
          "WORKLOAD_AUTHORITY_DENIED",
          LiveM2TestSupport.readJson(rejectedProposal.body()).get("code"));
      assertEquals(404, environment.request(
          environment.objectivesUrl(),
          "GET",
          "/v1/objectives/" + rejectedProposalId,
          environment.objectiveToken(
              ownerId, ownerSubject, LiveM2TestSupport.OBJECTIVE_ACTIVATOR),
          null,
          null).statusCode());

      String nextObjectiveId = "objective-next-" + suffix;
      String ownerProposerToken =
          environment.objectiveToken(
              ownerId, ownerSubject, LiveM2TestSupport.OBJECTIVE_PROPOSER);
      LiveM2TestSupport.Response proposed =
          environment.request(
              environment.objectivesUrl(),
              "POST",
              "/v1/objectives:local-idempotent-create",
              ownerProposerToken,
              objectiveBody(nextObjectiveId, workloadId),
              "owner-create");
      assertEquals(201, proposed.statusCode(), proposed.body());
      String digest = (String) LiveM2TestSupport.readJson(proposed.body()).get("content_digest");
      LiveM2TestSupport.Response replayedProposal = environment.request(
          environment.objectivesUrl(), "POST", "/v1/objectives:local-idempotent-create",
          ownerProposerToken, objectiveBody(nextObjectiveId, workloadId), "owner-create");
      assertEquals(200, replayedProposal.statusCode(), replayedProposal.body());
      assertEquals(
          200,
          environment.request(
              environment.objectivesUrl(),
              "POST",
              "/v1/objectives/" + nextObjectiveId + ":submit",
              ownerProposerToken,
              LiveM2TestSupport.json(Map.of("expected_revision", 1)),
              null).statusCode());

      String reviewerId = LiveM2TestSupport.principalId("objective-reviewer");
      LiveM2TestSupport.Response decided =
          environment.request(
              environment.objectivesUrl(),
              "POST",
              "/v1/objectives/" + nextObjectiveId + ":decide",
              environment.objectiveToken(
                  reviewerId, reviewerId, LiveM2TestSupport.OBJECTIVE_REVIEWER),
              LiveM2TestSupport.json(
                  Map.of(
                      "decision", "APPROVE",
                      "reason", "M2 live workload-owner validation",
                      "expected_revision", 2)),
              null);
      assertEquals(200, decided.statusCode(), decided.body());

      String activationBody = LiveM2TestSupport.json(
          Map.of("expected_revision", 3, "expected_content_digest", digest));
      LiveM2TestSupport.Response rejectedActivation =
          environment.request(
              environment.objectivesUrl(),
              "POST",
              "/v1/objectives/" + nextObjectiveId + ":idempotent-activate",
              environment.objectiveToken(
                  nonOwnerId, nonOwnerSubject, LiveM2TestSupport.OBJECTIVE_ACTIVATOR),
              activationBody,
              "non-owner-activation");
      assertEquals(403, rejectedActivation.statusCode(), rejectedActivation.body());
      assertEquals(
          "WORKLOAD_AUTHORITY_DENIED",
          LiveM2TestSupport.readJson(rejectedActivation.body()).get("code"));
      assertEquals("ACTIVE", environment.objective(originalObjectiveId).get("state"));
      assertEquals("APPROVED", environment.objective(nextObjectiveId).get("state"));

      LiveM2TestSupport.Response activated =
          environment.request(
              environment.objectivesUrl(),
              "POST",
              "/v1/objectives/" + nextObjectiveId + ":idempotent-activate",
              environment.objectiveToken(
                  ownerId, ownerSubject, LiveM2TestSupport.OBJECTIVE_ACTIVATOR),
              activationBody,
              "owner-activation");
      assertEquals(200, activated.statusCode(), activated.body());
      LiveM2TestSupport.Response replayedActivation = environment.request(
          environment.objectivesUrl(), "POST", "/v1/objectives/" + nextObjectiveId + ":idempotent-activate",
          environment.objectiveToken(ownerId, ownerSubject, LiveM2TestSupport.OBJECTIVE_ACTIVATOR),
          activationBody, "owner-activation");
      assertEquals(200, replayedActivation.statusCode(), replayedActivation.body());
      assertEquals("ACTIVE", LiveM2TestSupport.readJson(replayedActivation.body()).get("state"));
      assertEquals("SUPERSEDED", environment.objective(originalObjectiveId).get("state"));
      assertEquals("ACTIVE", environment.objective(nextObjectiveId).get("state"));
      environment.verifyStubHealth();
      assertEquals(0, environment.evidenceSummaryCalls());
    }
  }

  private static String objectiveBody(String id, String workloadId) {
    return LiveM2TestSupport.json(
        Map.of(
            "id", id,
            "workload_id", workloadId,
            "title", "M2 live objective " + id,
            "metric", "latency.p95",
            "comparator", "LTE",
            "target", new BigDecimal("250"),
            "unit", "ms",
            "window_seconds", 300,
            "origin", "LOCAL"));
  }
}
