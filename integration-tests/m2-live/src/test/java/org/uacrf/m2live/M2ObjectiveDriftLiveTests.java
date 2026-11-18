package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2ObjectiveDriftLiveTests {
  @Test
  void changedActiveObjectiveSetMakesClassificationIndeterminateAndPersistsIt() throws Exception {
    try (LiveM2TestSupport.Environment environment = LiveM2TestSupport.environment()) {
      String principalId = LiveM2TestSupport.principalId("drift-reader");
      String actorSubject = LiveM2TestSupport.principalId("drift-actor");
      environment.registerActor(principalId, actorSubject);

      String suffix = UUID.randomUUID().toString();
      String workloadId = "workload-" + suffix;
      String serviceId = "service-" + suffix;
      LiveM2TestSupport.Fixture fixture =
          environment.createAcceptedGraph(suffix, workloadId, serviceId);
      String originalObjectiveId = "objective-original-" + suffix;
      String changedObjectiveId = "objective-changed-" + suffix;
      environment.createAndActivateObjective(workloadId, originalObjectiveId, "250");

      LiveM2TestSupport.Response selection =
          environment.select(fixture, principalId, actorSubject, suffix);
      assertEquals(202, selection.statusCode(), selection.body());
      String selectionCommandId =
          (String) LiveM2TestSupport.readJson(selection.body()).get("command_id");
      Map<String, Object> selectionStatus =
          environment.awaitCommand(
              selectionCommandId, environment.topologyToken(principalId, actorSubject));
      assertEquals("SUCCEEDED", selectionStatus.get("status"));
      Map<?, ?> selectionAdvisory = (Map<?, ?>) selectionStatus.get("advisory_result");
      Map<?, ?> originalSnapshot = (Map<?, ?>) selectionAdvisory.get("slo_objectives");
      List<?> originalObjectives = (List<?>) originalSnapshot.get("objectives");
      assertTrue(
          originalObjectives.stream()
              .anyMatch(
                  objective ->
                      originalObjectiveId.equals(((Map<?, ?>) objective).get("id"))));
      String selectionDigest = (String) selectionAdvisory.get("result_digest");

      environment.createAndActivateObjective(workloadId, changedObjectiveId, "200");
      Map<String, Object> oldObjective = environment.objective(originalObjectiveId);
      Map<String, Object> currentObjective = environment.objective(changedObjectiveId);
      assertEquals("SUPERSEDED", oldObjective.get("state"));
      assertEquals("ACTIVE", currentObjective.get("state"));

      LiveM2TestSupport.Response missingTokenRead =
          environment.request(
              environment.topologyUrl(),
              "GET",
              "/v1/commands/" + selectionCommandId,
              null,
              null,
              null);
      assertEquals(401, missingTokenRead.statusCode());

      LiveM2TestSupport.Response classification =
          environment.classify(fixture, principalId, actorSubject, selectionDigest, suffix);
      assertEquals(202, classification.statusCode(), classification.body());
      String classificationCommandId =
          (String) LiveM2TestSupport.readJson(classification.body()).get("command_id");
      Map<String, Object> result =
          environment.awaitCommand(
              classificationCommandId, environment.topologyToken(principalId, actorSubject));
      assertEquals("SUCCEEDED", result.get("status"));
      Map<?, ?> advisory = (Map<?, ?>) result.get("advisory_result");
      assertEquals("INDETERMINATE", advisory.get("completeness"));
      Map<?, ?> objectiveSetChange = (Map<?, ?>) advisory.get("objective_set_change");
      assertEquals(Boolean.TRUE, objectiveSetChange.get("changed"));

      LiveM2TestSupport.Response persisted =
          environment.getCommand(classificationCommandId, principalId, actorSubject);
      assertEquals(200, persisted.statusCode());
      Map<String, Object> persistedStatus = LiveM2TestSupport.readJson(persisted.body());
      Map<?, ?> persistedAdvisory = (Map<?, ?>) persistedStatus.get("advisory_result");
      Map<?, ?> persistedChange = (Map<?, ?>) persistedAdvisory.get("objective_set_change");
      assertEquals(Boolean.TRUE, persistedChange.get("changed"));
      environment.verifyStubHealth();
      assertEquals(0, environment.evidenceSummaryCalls());
    }
  }
}
