package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2ObjectiveSampleSourceLiveTests {
  @Test
  void inspectsOnlyBoundSampleWithCurrentSeparateGrants() throws Exception {
    try (LiveM2TestSupport.Environment env = LiveM2TestSupport.environmentWithSampleSource()) {
      String suffix = UUID.randomUUID().toString();
      String connectionId = "sample-" + suffix;
      String workload = "workload-" + suffix;
      String owner = "owner-" + suffix;
      String subject = "actor-" + suffix;
      String principal = "principal-" + suffix;
      String objectiveRead = "objectives.objective.read";
      String connectionRead = "connections.connection.read";
      env.registerActor(principal, subject, List.of(objectiveRead, connectionRead));
      String writer = LiveM2TestSupport.bearerWithEligibleOwners(
          env.tenantId(), env.environmentId(), principal, subject, "S03",
          List.of("connections.connection.write", connectionRead), List.of(owner));
      var created = env.request(env.connectionsUrl(), "POST", "/v1/connections", writer,
          LiveM2TestSupport.json(Map.of("id", connectionId, "name", "Fictional source",
              "provider", "SAMPLE_LOCAL", "read_only_identity_ref", "role/sample",
              "owner_id", owner, "expected_revision", 0)), "create");
      assertEquals(201, created.statusCode(), created.body());
      var bound = env.request(env.connectionsUrl(), "POST",
          "/v1/connections/" + connectionId + "/source-bindings", writer,
          LiveM2TestSupport.json(Map.of("id", "binding-" + suffix,
              "source_ref", "sample:checkout-recovery", "service_ref", workload,
              "expected_revision", 0)), "bind");
      assertEquals(201, bound.statusCode(), bound.body());
      String actor = env.objectiveToken(principal, subject,
          List.of("objectives.objective.write", objectiveRead));
      var admitted = env.request(env.objectivesUrl(), "POST", "/v1/objective-imports:preview", actor,
          LiveM2TestSupport.json(Map.of("connection_id", connectionId,
              "external_objective_id", "sample:checkout-recovery",
              "target_workload_id", workload, "mapping_revision", 1,
              "expected_local_revision", 0)), "preview");
      assertEquals(202, admitted.statusCode(), admitted.body());
      String id = (String) LiveM2TestSupport.readJson(admitted.body()).get("receipt_id");
      String path = "/v1/objective-imports/" + id + "/sample-source";
      var inspected = env.request(env.objectivesUrl(), "GET", path, actor, null, null);
      assertEquals(200, inspected.statusCode(), inspected.body());
      Map<String, Object> result = LiveM2TestSupport.readJson(inspected.body());
      assertEquals("SYNTHETIC_UNVERIFIED", result.get("verification"));
      assertEquals(connectionId, result.get("connection_id"));
      assertEquals(workload, result.get("target_workload_id"));
      assertTrue(inspected.raw().headers().firstValue("Cache-Control").orElse("").contains("no-store"));
      Path fixturePath = Path.of(System.getProperty(
          "uacrf.repo.root", Path.of("../..").toAbsolutePath().toString()))
          .resolve("sample-data/objectives/sample-objective-definitions.json");
      Map<String, Object> fixture = LiveM2TestSupport.readJson(Files.readString(fixturePath));
      Map<?, ?> fixtureDefinition = (Map<?, ?>) ((List<?>) fixture.get("definitions")).getFirst();
      String payload = (String) fixtureDefinition.get("payload");
      assertEquals(LiveM2TestSupport.readJson(payload), result.get("definition"));
      assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8))), result.get("payload_digest"));
      assertEquals(Map.of("version", "OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1",
          "digest", "0615af06050a1ebe47c53362ebd790c68e0abd5ed9749b01605a3b006a9b7e1d"),
          result.get("definition_fingerprint"));
      String capturePath = "/v1/objective-imports/" + id + "/sample-captures";
      var capture = env.request(env.objectivesUrl(), "POST", capturePath, actor, "", "capture");
      assertEquals(201, capture.statusCode(), capture.body());
      assertTrue(capture.raw().headers().firstValue("Cache-Control").orElse("").contains("no-store"));
      var captured = LiveM2TestSupport.readJson(capture.body());
      assertEquals("SYNTHETIC_UNVERIFIED", captured.get("verification"));
      var replay = env.request(env.objectivesUrl(), "POST", capturePath, actor, "", "capture");
      assertEquals(200, replay.statusCode(), replay.body());
      assertEquals(captured, LiveM2TestSupport.readJson(replay.body()));
      assertEquals(403, env.request(env.objectivesUrl(), "POST", capturePath,
          env.objectiveToken(principal, subject, List.of(objectiveRead)), "", "denied").statusCode());
      var receipt = env.request(env.objectivesUrl(), "GET", "/v1/objective-imports/" + id, actor, null, null);
      assertEquals("PENDING", LiveM2TestSupport.readJson(receipt.body()).get("status"));

      var exchange = env.request(env.identityUrl(), "POST", "/internal/v1/delegations:exchange", actor,
          LiveM2TestSupport.json(Map.of("audience", "S03", "resource_type", "connection",
              "resource_id", connectionId, "purpose", "objectives.import.synthetic-source",
              "operation_deadline", Instant.now().plusSeconds(4).toString())), null);
      assertEquals(200, exchange.statusCode(), exchange.body());
      String scoped = "Bearer " + LiveM2TestSupport.readJson(exchange.body()).get("access_token");
      assertEquals(403, env.request(env.connectionsUrl(), "GET",
          "/v1/connections/" + connectionId, scoped, null, null).statusCode());
      assertEquals(403, env.request(env.connectionsUrl(), "GET",
          "/v1/connections/" + connectionId + "/source-bindings", scoped, null, null).statusCode());

      var wrongBinding = env.request(env.objectivesUrl(), "POST", "/v1/objective-imports:preview", actor,
          LiveM2TestSupport.json(Map.of("connection_id", connectionId,
              "external_objective_id", "sample:checkout-recovery",
              "target_workload_id", "unbound-" + suffix, "mapping_revision", 1,
              "expected_local_revision", 0)), "unbound");
      assertEquals(202, wrongBinding.statusCode(), wrongBinding.body());
      String wrongId = (String) LiveM2TestSupport.readJson(wrongBinding.body()).get("receipt_id");
      assertEquals(404, env.request(env.objectivesUrl(), "GET",
          "/v1/objective-imports/" + wrongId + "/sample-source", actor, null, null).statusCode());
      assertEquals(404, env.request(env.objectivesUrl(), "GET", path,
          LiveM2TestSupport.bearer("other-tenant", env.environmentId(), principal, subject,
              "S05", List.of(objectiveRead)), null, null).statusCode());
      assertEquals(404, env.request(env.objectivesUrl(), "GET", path,
          LiveM2TestSupport.bearer(env.tenantId(), "other-environment", principal, subject,
              "S05", List.of(objectiveRead)), null, null).statusCode());
      String other = "other-" + suffix;
      env.registerActor(other, other, List.of(objectiveRead));
      assertEquals(403, env.request(env.objectivesUrl(), "GET", path,
          env.objectiveToken(other, other, List.of(objectiveRead)), null, null).statusCode());
      String noObjective = "no-objective-" + suffix;
      env.registerActor(noObjective, noObjective, List.of(connectionRead));
      assertEquals(403, env.request(env.objectivesUrl(), "GET", path,
          env.objectiveToken(noObjective, noObjective, List.of(objectiveRead)), null, null).statusCode());
      String revokedObjective = "revoked-objective-" + suffix;
      env.registerActor(revokedObjective, revokedObjective, List.of(objectiveRead, connectionRead));
      env.revokeCapability(revokedObjective, objectiveRead);
      assertEquals(403, env.request(env.objectivesUrl(), "GET", path,
          env.objectiveToken(revokedObjective, revokedObjective, List.of(objectiveRead)), null, null).statusCode());
      env.revokeCapability(principal, connectionRead);
      assertEquals(403, env.request(env.objectivesUrl(), "GET", path, actor, null, null).statusCode());
      assertEquals(403, env.request(env.objectivesUrl(), "POST", capturePath, actor, "", "capture").statusCode());
      env.verifyStubHealth();
    }
  }
}
