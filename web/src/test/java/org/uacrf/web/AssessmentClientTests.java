package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AssessmentClientTests {
  private static final String CASE = """
      {"id":"case-1","workload_id":"workload-1","purpose":"assessment",
       "metric":"latency","comparator":"LTE","threshold":10,"unit":"ms","window_seconds":3600,
       "objective_ref":{"objective_id":"objective-1","revision":1,"digest":"d"},
       "graph_ref":{"graph_id":"graph-1","revision":1,"digest":"d"},
       "required_source_kinds":["measurement.latency"],"state":"SEALED","revision":2,
       "case_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
      """;
  private static final String EVALUATION = """
      {"id":"evaluation-1","case_id":"case-1","revision":1,
       "observation_cutoff":"2026-01-01T00:00:00Z","received_at":"2026-01-01T00:01:00Z",
       "mode":"SHADOW","outcome":"INSUFFICIENT_EVIDENCE",
       "dependency_vector":{"receipt_verification":"FAILED","receipt_reason_code":"OWNER_UNAVAILABLE"},
       "result_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
      """;

  @Test
  void readsWorkloadCasesThroughTheAssessmentEdge() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/assessment");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/assessment/v1/cases?workload_id=workload-1"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("[" + CASE + "]", MediaType.APPLICATION_JSON));
    var cases = new AssessmentClient(builder.build()).listCases("delegated", "workload-1");
    assertThat(cases).singleElement().satisfies(item -> {
      assertThat(item.workloadId()).isEqualTo("workload-1");
      assertThat(item.state()).isEqualTo("SEALED");
      assertThat(item.threshold()).isEqualByComparingTo("10");
    });
    server.verify();
  }

  @Test
  void rejectsMalformedCasesAndWorkloadSubstitution() {
    for (String body : List.of(
        "[null]", "[" + CASE.replace("\"SEALED\"", "\"UNKNOWN\"") + "]",
        "[" + CASE.replace("\"workload-1\"", "\"other\"") + "]",
        "[" + CASE.replace("\"case_digest\":\"" + "a".repeat(64) + "\"", "\"case_digest\":null") + "]")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/cases?workload_id=workload-1"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      assertThatThrownBy(() -> new AssessmentClient(builder.build()).listCases("delegated", "workload-1"))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }

  @Test
  void readsEvaluationsAndRejectsMismatchedOrIncompleteResults() {
    for (String body : List.of(
        "[" + EVALUATION + "]",
        "[" + EVALUATION.replace("\"case-1\"", "\"other\"") + "]",
        "[" + EVALUATION.replace("\"SHADOW\"", "null") + "]",
        "[" + EVALUATION.replace("\"receipt_reason_code\":\"OWNER_UNAVAILABLE\"", "\"receipt_reason_code\":null") + "]")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/cases/case-1/evaluations"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      AssessmentClient client = new AssessmentClient(builder.build());
      if (body.equals("[" + EVALUATION + "]")) {
        assertThat(client.listEvaluations("delegated", "case-1")).singleElement()
            .satisfies(item -> assertThat(item.dependencyVector().receiptVerification()).isEqualTo("FAILED"));
      } else {
        assertThatThrownBy(() -> client.listEvaluations("delegated", "case-1"))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }
}
