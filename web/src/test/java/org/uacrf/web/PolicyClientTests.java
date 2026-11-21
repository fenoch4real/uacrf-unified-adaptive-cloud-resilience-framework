package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PolicyClientTests {
  private static final String MATERIAL = """
      {"definition":{},"effective_from":"2026-01-01T00:00:00Z","expires_at":"2031-01-01T00:00:00Z","id":"policy-a","service_id":"service-a"}""";
  private static final String DIGEST = digest(MATERIAL);
  private static final String SUMMARY = """
      {"id":"policy-a","revision":2,"service_id":"service-a",
       "expires_at":"2031-01-01T00:00:00Z","digest":"%s",
       "proposed_by":"proposer","proposal_reason":"reason","proposed_at":"2026-01-01T00:00:00Z",
       "decision":"DRAFT","current_eligible":false,
       "authority":"SIGNED_POLICY_CONFIGURATION_NOT_ACTION_AUTHORIZATION",
       "effective_from":"2026-01-01T00:00:00Z"}
      """.formatted(DIGEST);
  private static final String DETAIL = SUMMARY.strip().substring(0, SUMMARY.strip().length() - 1) + """
      ,"material":{"algorithm":"SHA-256","encoding":"UTF-8","canonical_text":%s,"digest":"%s"},
      "review":{"required_approval_count":1,"approval_count":0,"valid_approval_count":0,
      "rejection_count":0,"votes":[]}}
      """.formatted(jsonString(MATERIAL), DIGEST);

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  @Test
  void readsServiceScopedPageAndExactRevision() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/decision-governance");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/decision-governance/v1/authorization-policy-bundles?service_id=service-a&page_size=25"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("{\"items\":[" + SUMMARY + "],\"next_cursor\":\"next\",\"coverage\":\"AUTHORIZED_VISIBLE_SCOPE_ONLY\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://edge.test/internal/decision-governance/v1/authorization-policy-bundles/policy-a?revision=2"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("{\"policy\":" + DETAIL + "}", MediaType.APPLICATION_JSON));
    PolicyClient client = new PolicyClient(builder.build());
    assertThat(client.list("delegated", "service-a", null).items()).singleElement()
        .satisfies(item -> assertThat(item.revision()).isEqualTo(2));
    assertThat(client.read("delegated", "service-a", "policy-a", 2).material().canonicalText()).isEqualTo(MATERIAL);
    server.verify();
  }

  @Test
  void rejectsOutOfScopeAndMalformedPages() {
    for (String body : List.of(
        "{\"items\":[" + SUMMARY.replace("service-a", "service-b") + "],\"coverage\":\"AUTHORIZED_VISIBLE_SCOPE_ONLY\"}",
        "{\"items\":[],\"next_cursor\":\"same\",\"coverage\":\"AUTHORIZED_VISIBLE_SCOPE_ONLY\"}",
        "{\"items\":[" + SUMMARY + "],\"coverage\":\"ALL\"}",
        "{\"items\":[" + SUMMARY.replace("\"current_eligible\":false", "\"current_eligible\":true") + "],\"coverage\":\"AUTHORIZED_VISIBLE_SCOPE_ONLY\"}")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/authorization-policy-bundles?service_id=service-a&page_size=25&cursor=same"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      assertThatThrownBy(() -> new PolicyClient(builder.build()).list("token", "service-a", "same"))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }

  @Test
  void rejectsWrongRevisionScopeAndTamperedMaterial() {
    for (String policy : List.of(
        DETAIL.replace("\"revision\":2", "\"revision\":3"),
        DETAIL.replace("service-a", "service-b"),
        DETAIL.replace(jsonString(MATERIAL), jsonString("tampered")),
        DETAIL.replace(jsonString(MATERIAL), jsonString(MATERIAL.replace("service-a", "service-b")))
            .replace(DIGEST, digest(MATERIAL.replace("service-a", "service-b"))),
        DETAIL.replace("\"valid_approval_count\":0", "\"valid_approval_count\":1"))) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/authorization-policy-bundles/policy-a?revision=2"))
          .andRespond(withSuccess("{\"policy\":" + policy + "}", MediaType.APPLICATION_JSON));
      assertThatThrownBy(() -> new PolicyClient(builder.build()).read("token", "service-a", "policy-a", 2))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }

  @Test
  void submitsKeyedProposalAndChecksReturnedDefinition() {
      String material = """
          {"definition":{"allowed_action_kinds":["READ"],"allowed_actor_ids":["actor"],"allowed_resource_ids":["resource"],"eligible_approver_ids":["reviewer"],"maximum_cost_usd":10,"permitted_providers":["AZURE"],"permitted_regions":["region"],"required_approver_count":1},"effective_from":"2026-01-01T00:00:00Z","expires_at":"2031-01-01T00:00:00Z","id":"policy-a","service_id":"service-a"}""";
      var definition = new PolicyClient.Definition(List.of("READ"), List.of("actor"), List.of("resource"),
          List.of("AZURE"), List.of("region"), 1, List.of("reviewer"), new BigDecimal("10.00"));
      var request = new PolicyClient.Proposal("policy-a", "service-a", 1,
          Instant.parse("2031-01-01T00:00:00Z"), definition, "reason", Instant.parse("2026-01-01T00:00:00Z"));
      for (boolean changed : List.of(false, true)) {
        String returnedMaterial = changed ? material.replace("resource", "other-resource") : material;
        String policy = DETAIL.replace(jsonString(MATERIAL), jsonString(returnedMaterial))
            .replace(DIGEST, digest(returnedMaterial));
        RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://edge.test/v1/authorization-policy-bundles"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
            .andExpect(header("Idempotency-Key", "same-key"))
            .andExpect(jsonPath("$.service_id").value("service-a"))
            .andExpect(jsonPath("$.expected_revision").value(1))
            .andExpect(jsonPath("$.definition.allowed_action_kinds[0]").value("READ"))
            .andRespond(withSuccess("{\"policy\":" + policy + "}", MediaType.APPLICATION_JSON));
        PolicyClient client = new PolicyClient(builder.build());
        if (changed) {
          assertThatThrownBy(() -> client.propose("delegated", "same-key", request))
              .isInstanceOf(IllegalStateException.class);
        } else {
          assertThat(client.propose("delegated", "same-key", request).revision()).isEqualTo(2);
        }
        server.verify();
    }
  }
}
