package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ObjectivesClientTests {
  private static final String RECORD = """
      {"id":"objective","workload_id":"workload-a","title":"Latency","metric":"latency",
       "comparator":"LTE","target":99.75,"unit":"ms","window_seconds":60,"origin":"LOCAL",
       "content_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
       "state":"DRAFT","revision":1,"proposed_by":"proposer"}
      """;

  @Test
  void readsBoundedHistoryAndRejectsMismatchedOrIncompleteResponses() {
    for (String body : List.of(
        "{\"objective_id\":\"objective\",\"items\":[{\"revision\":3,\"state\":\"REJECTED\","
            + "\"actor_id\":\"reviewer\",\"recorded_at\":\"2026-01-01T00:00:00Z\"}],\"more_recorded\":false}",
        "{\"objective_id\":\"other\",\"items\":[],\"more_recorded\":false}",
        "{\"objective_id\":\"objective\",\"items\":[]}")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objectives/objective/decision-history"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (body.contains("\"actor_id\"")) {
        assertThat(client.decisionHistory("delegated", "objective").items())
            .singleElement().satisfies(event -> assertThat(event.actorId()).isEqualTo("reviewer"));
      } else {
        assertThatThrownBy(() -> client.decisionHistory("delegated", "objective"))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void readsDecisionRecordsAndRejectsMissingGapDisclosure() {
    for (boolean disclose : List.of(true, false)) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      String body = "{\"objective_id\":\"objective\",\"items\":[{\"revision\":3,"
          + "\"content_digest\":\"" + "a".repeat(64) + "\",\"decision\":\"APPROVE\","
          + "\"reason\":\"reviewed\",\"decided_by\":\"reviewer\",\"decided_at\":\"2026-01-01T00:00:00Z\"}],"
          + "\"next_cursor\":null,\"history_may_be_incomplete\":" + disclose + "}";
      server.expect(requestTo("http://edge.test/v1/objectives/objective/decision-records?pageSize=25&cursor=NA"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (disclose) {
        assertThat(client.decisionRecords("delegated", "objective", "NA").items())
            .singleElement().satisfies(record -> assertThat(record.reason()).isEqualTo("reviewed"));
      } else {
        assertThatThrownBy(() -> client.decisionRecords("delegated", "objective", "NA"))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void importPreviewForwardsBoundRequestAndValidatesReceipt() {
    String id = "12345678-1234-4234-8234-123456789012";
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/v1/objective-imports:preview"))
        .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "key"))
        .andExpect(jsonPath("$.connection_id").value("sample"))
        .andExpect(jsonPath("$.mapping_revision").value(1))
        .andRespond(withSuccess("{\"receipt_id\":\"" + id + "\",\"status\":\"PENDING\","
            + "\"read_resource\":\"/v1/objective-imports/" + id + "\","
            + "\"runtime_verification\":\"NOT_ESTABLISHED\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://edge.test/v1/objective-imports/" + id))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
        .andRespond(withSuccess("{\"receipt_id\":\"" + id + "\",\"status\":\"PENDING\","
            + "\"read_resource\":\"/v1/objective-imports/other\"}", MediaType.APPLICATION_JSON));
    ObjectivesClient client = new ObjectivesClient(builder.build());
    assertThat(client.previewImport("delegated", "key",
        new ObjectivesClient.ImportPreview("sample", "slo", "workload", 1L, 0L)).receiptId()).isEqualTo(id);
    assertThatThrownBy(() -> client.importReceipt("reader", id)).isInstanceOf(IllegalStateException.class);
    server.verify();
  }

  @Test
  void readsOnlyWellFormedSyntheticInspectionForPendingReceipt() {
    String id = "12345678-1234-4234-8234-123456789012";
    String body = """
        {"connection_id":"sample","connection_revision":1,"binding_id":"binding",
         "binding_revision":1,"target_workload_id":"workload","source_system":"sample:monitoring",
         "external_objective_id":"sample:checkout-recovery","source_version":"sample-v1",
         "payload_digest":"%s","verification":"SYNTHETIC_UNVERIFIED",
         "definition_fingerprint":{"version":"OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1","digest":"%s"},
         "definition":{"metric":"recovery.time","target":720,"unit":"seconds","window":"stable-30m",
         "measurement_ref":"sample:check","comparison":"AT_MOST","measurement_kind":"RECOVERY_TIME","percentile":null}}
        """.formatted("a".repeat(64), "b".repeat(64));
    for (String response : List.of(body, body.replace("SYNTHETIC_UNVERIFIED", "VERIFIED"),
        body.replace("\"measurement_kind\":\"RECOVERY_TIME\"", "\"measurement_kind\":null"),
        body.replace("\"connection_revision\":1", "\"connection_revision\":0"),
        body.replace("\"payload_digest\":\"" + "a".repeat(64) + "\"", "\"payload_digest\":\"bad\""),
        body.replace("OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1", "UNKNOWN_VERSION"),
        body.replace("\"digest\":\"" + "b".repeat(64) + "\"", "\"digest\":\"bad\""),
        body.replace("\"definition_fingerprint\":{", "\"definition_fingerprint_unused\":{"))) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objective-imports/" + id + "/sample-source"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
          .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (response.equals(body)) {
        assertThat(client.inspectImportSample("reader", id).definition().target()).isEqualByComparingTo("720");
      } else {
        assertThatThrownBy(() -> client.inspectImportSample("reader", id)).isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void capturesOnlyWithValidBoundSyntheticReceipt() {
    String id = "12345678-1234-4234-8234-123456789012";
    String key = "12345678-1234-4234-8234-123456789013";
    String capture = "12345678-1234-4234-8234-123456789014";
    String body = """
        {"capture_id":"%s","receipt_id":"%s","captured_at":"2026-11-21T00:00:00Z",
         "verification":"SYNTHETIC_UNVERIFIED"}
        """.formatted(capture, id);
    for (String response : List.of(body, body.replace(id, capture),
        body.replace("SYNTHETIC_UNVERIFIED", "VERIFIED"), body.replace("2026-11-21T00:00:00Z", "bad"))) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objective-imports/" + id + "/sample-captures"))
          .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(header("Idempotency-Key", key))
          .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (response.equals(body)) {
        assertThat(client.captureImportSample("delegated", id, key).captureId()).isEqualTo(capture);
      } else {
        assertThatThrownBy(() -> client.captureImportSample("delegated", id, key))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void readsPendingImportPagesAndRejectsInvalidItems() {
    String id = "12345678-1234-4234-8234-123456789012";
    for (String status : List.of("PENDING", "SUCCEEDED")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objective-imports?pageSize=25&cursor=abc"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
          .andRespond(withSuccess("{\"items\":[{\"receipt_id\":\"" + id + "\",\"status\":\""
              + status + "\",\"target_workload_id\":\"workload\",\"created_at\":\"2026-01-01T00:00:00Z\"}],"
              + "\"next_cursor\":null}", MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (status.equals("PENDING")) {
        assertThat(client.listImports("reader", "abc").items()).singleElement()
            .satisfies(item -> assertThat(item.receiptId()).isEqualTo(id));
      } else {
        assertThatThrownBy(() -> client.listImports("reader", "abc")).isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void activationBindsRequestAndRejectsMismatchedReceipt() {
    String digest = "a".repeat(64);
    for (String actor : List.of("activator", "other")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objectives/objective:idempotent-activate"))
          .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(header("Idempotency-Key", "key")).andExpect(jsonPath("$.expected_revision").value(3))
          .andExpect(jsonPath("$.expected_content_digest").value(digest))
          .andRespond(withSuccess(RECORD.replace("\"state\":\"DRAFT\",\"revision\":1,\"proposed_by\":\"proposer\"",
              "\"state\":\"ACTIVE\",\"revision\":4,\"proposed_by\":\"proposer\","
                  + "\"decided_by\":\"reviewer\",\"decision_reason\":\"reason\",\"activated_by\":\"" + actor + "\""),
              MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (actor.equals("activator")) {
        assertThat(client.activate("delegated", "objective", "key", 3, digest, "activator", "reviewer", "reason").state()).isEqualTo("ACTIVE");
      } else {
        assertThatThrownBy(() -> client.activate("delegated", "objective", "key", 3, digest, "activator", "reviewer", "reason"))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void submissionBindsRevisionAndDigestAndRejectsMismatchedResult() {
    String digest = "a".repeat(64);
    for (boolean matching : List.of(true, false)) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objectives/objective:idempotent-submit"))
          .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(header("Idempotency-Key", "key"))
          .andExpect(jsonPath("$.expected_revision").value(1))
          .andExpect(jsonPath("$.expected_content_digest").value(digest))
          .andRespond(withSuccess(RECORD.replace("\"state\":\"DRAFT\",\"revision\":1",
              matching ? "\"state\":\"SUBMITTED\",\"revision\":2" : "\"state\":\"DRAFT\",\"revision\":1"),
              MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (matching) {
        assertThat(client.submit("delegated", "objective", "key", 1, digest).state()).isEqualTo("SUBMITTED");
      } else {
        assertThatThrownBy(() -> client.submit("delegated", "objective", "key", 1, digest))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void reviewBindsExactDecisionAndRejectsMismatchedReceipt() {
    String digest = "a".repeat(64);
    for (boolean matching : List.of(true, false)) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objectives/objective:idempotent-decide"))
          .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(header("Idempotency-Key", "key"))
          .andExpect(jsonPath("$.decision").value("REJECT"))
          .andExpect(jsonPath("$.reason").value("Exact reason"))
          .andExpect(jsonPath("$.expected_revision").value(2))
          .andExpect(jsonPath("$.expected_content_digest").value(digest))
          .andRespond(withSuccess(RECORD.replace("\"state\":\"DRAFT\",\"revision\":1,\"proposed_by\":\"proposer\"",
              "\"state\":\"REJECTED\",\"revision\":3,\"proposed_by\":\"proposer\","
                  + "\"decided_by\":\"reviewer\",\"decision_reason\":\""
                  + (matching ? "Exact reason" : "different") + "\""), MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (matching) {
        assertThat(client.decide("delegated", "objective", "key", 2, digest,
            "REJECT", "Exact reason", "reviewer").state()).isEqualTo("REJECTED");
      } else {
        assertThatThrownBy(() -> client.decide("delegated", "objective", "key", 2, digest,
            "REJECT", "Exact reason", "reviewer")).isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void createsLocalDraftAndRejectsMismatchedReceipt() {
    var request = new ObjectivesClient.LocalObjective("objective", "workload-a", "Latency", "latency",
        "LTE", new BigDecimal("99.75"), "ms", 60, "LOCAL");
    for (boolean matching : List.of(true, false)) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objectives:local-idempotent-create"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(header("Idempotency-Key", "key"))
          .andExpect(jsonPath("$.workload_id").value("workload-a"))
          .andExpect(jsonPath("$.origin").value("LOCAL"))
          .andRespond(withSuccess(matching ? RECORD : RECORD.replace("99.75", "99.76"), MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      if (matching) {
        assertThat(client.createLocal("delegated", "key", request).id()).isEqualTo("objective");
      } else {
        assertThatThrownBy(() -> client.createLocal("delegated", "key", request))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void readsExactWorkloadAndCursorThroughEdge() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/objectives");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/objectives/v1/objectives?pageSize=25&workloadId=workload-a&cursor=start"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("{\"items\":[" + RECORD + "],\"next_cursor\":\"next\"}", MediaType.APPLICATION_JSON));
    var page = new ObjectivesClient(builder.build()).list("delegated", "start", "workload-a");
    assertThat(page.nextCursor()).isEqualTo("next");
    assertThat(page.items()).singleElement().satisfies(item -> {
      assertThat(item.target()).isEqualByComparingTo("99.75");
      assertThat(item.state()).isEqualTo("DRAFT");
    });
    server.verify();
  }

  @Test
  void rejectsMalformedOrOutOfScopePages() {
    for (String body : List.of(
        "{\"items\":null}",
        "{\"items\":[null]}",
        "{\"items\":[" + RECORD.replace("\"window_seconds\":60", "\"window_seconds\":null") + "]}",
        "{\"items\":[" + RECORD.replace("workload-a", "workload-b") + "]}",
        "{\"items\":[],\"next_cursor\":\"\"}")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/objectives?pageSize=25&workloadId=workload-a"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      ObjectivesClient client = new ObjectivesClient(builder.build());
      assertThatThrownBy(() -> client.list("delegated", null, "workload-a"))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }
}
