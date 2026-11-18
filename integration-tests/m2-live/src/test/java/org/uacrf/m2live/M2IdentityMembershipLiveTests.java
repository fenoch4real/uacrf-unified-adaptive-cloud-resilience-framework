package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2IdentityMembershipLiveTests {
  @Test
  void absentIdentityMembershipBlocksOwnerExchangeWithoutPersistingCommand() throws Exception {
    try (LiveM2TestSupport.Environment environment = LiveM2TestSupport.environment()) {
      String principalId = LiveM2TestSupport.principalId("membership-reader");
      String actorSubject = LiveM2TestSupport.principalId("membership-actor");
      environment.registerActor(principalId, actorSubject);

      String suffix = UUID.randomUUID().toString();
      String workloadId = "workload-" + suffix;
      String serviceId = "service-" + suffix;
      String caseId = "case-" + suffix;
      LiveM2TestSupport.Fixture fixture =
          environment.createAcceptedGraph(suffix, workloadId, serviceId, caseId);
      environment.createAndActivateObjective(workloadId, "objective-" + suffix, "250");

      LiveM2TestSupport.Response validSelection =
          environment.select(fixture, principalId, actorSubject, suffix);
      assertEquals(202, validSelection.statusCode(), validSelection.body());
      String commandId =
          (String) LiveM2TestSupport.readJson(validSelection.body()).get("command_id");
      assertEquals(
          "SUCCEEDED",
          environment
              .awaitCommand(commandId, environment.topologyToken(principalId, actorSubject))
              .get("status"));
      LiveM2TestSupport.Response persisted =
          environment.getCommand(commandId, principalId, actorSubject);
      assertEquals(200, persisted.statusCode());
      assertEquals("SUCCEEDED", LiveM2TestSupport.readJson(persisted.body()).get("status"));

      String unregisteredPrincipal = LiveM2TestSupport.principalId("unregistered");
      String unregisteredSubject = LiveM2TestSupport.principalId("subject");
      int serviceReceiptsBefore = environment.serviceReceiptCalls();
      int caseReceiptsBefore = environment.caseReceiptCalls();
      LiveM2TestSupport.Response denied =
          environment.select(
              fixture,
              unregisteredPrincipal,
              unregisteredSubject,
              suffix + "-denied");

      assertEquals(409, denied.statusCode(), denied.body());
      assertTrue(denied.body().contains("PROOF_UNAVAILABLE_OR_STALE"));
      assertFalse(denied.raw().headers().firstValue("Location").isPresent());
      assertEquals(serviceReceiptsBefore, environment.serviceReceiptCalls());
      assertEquals(caseReceiptsBefore, environment.caseReceiptCalls());
      assertEquals(200, environment.getCommand(commandId, principalId, actorSubject).statusCode());
      environment.verifyStubHealth();
      assertEquals(0, environment.evidenceSummaryCalls());
    }
  }
}
