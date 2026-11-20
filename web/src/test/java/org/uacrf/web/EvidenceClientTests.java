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

class EvidenceClientTests {
  @Test
  void readsPaginatedEvidenceMetadataThroughEdge() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/evidence");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/evidence/v1/evidence?pageSize=25&cursor=start"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("""
            {"items":[{"id":"sample","source_class":"SAMPLE_LOCAL","source_ref":"sample:source",
            "sequence":1,"observed_at":"2026-11-12T09:05:00Z","kind":"sample.metric",
            "service_id":null,"schema_version":null,
            "payload_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "payload_byte_length":2,"submitted_by":"test","qualification_state":"SAMPLE_ONLY",
            "revision":1,"reviewed_by":null,"review_reason":null}],"next_cursor":"next"}
            """, MediaType.APPLICATION_JSON));
    var page = new EvidenceClient(builder.build()).list("delegated", "start");
    assertThat(page.nextCursor()).isEqualTo("next");
    assertThat(page.items()).singleElement().satisfies(item -> {
      assertThat(item.sourceClass()).isEqualTo("SAMPLE_LOCAL");
      assertThat(item.qualificationState()).isEqualTo("SAMPLE_ONLY");
      assertThat(item.payloadByteLength()).isEqualTo(2);
    });
    server.verify();
  }

  @Test
  void rejectsIncompleteOrContradictoryEvidence() {
    for (String body : List.of(
        "{\"items\":null}",
        "{\"items\":[null]}",
        """
        {"items":[{"id":"sample","source_class":"SAMPLE_LOCAL","source_ref":"sample:source",
        "sequence":1,"observed_at":"2026-11-12T09:05:00Z","kind":"sample.metric",
        "payload_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "payload_byte_length":2,"submitted_by":"test","qualification_state":"QUALIFIED",
        "revision":1}]}
        """,
        "{\"items\":[],\"next_cursor\":\"\"}")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/evidence?pageSize=25"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      EvidenceClient client = new EvidenceClient(builder.build());
      assertThatThrownBy(() -> client.list("delegated", null))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }
}
