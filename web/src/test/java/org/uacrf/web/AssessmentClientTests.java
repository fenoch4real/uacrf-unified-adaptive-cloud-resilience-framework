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
}
