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
