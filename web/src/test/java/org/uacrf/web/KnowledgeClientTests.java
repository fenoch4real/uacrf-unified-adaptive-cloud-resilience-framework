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

class KnowledgeClientTests {
  private static final String RECORD = """
      {"item_id":"item:one","version":"v1","title":"Title","source_ref":"https://example.test",
       "classification":"PUBLIC","allowed_purposes":["risk:review"],"target_workload_ids":[],
       "review_deadline":"2030-01-01T00:00:00Z","reason":"Reason",
       "source_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
       "state":"CANDIDATE","revision":1,"proposed_by":"user","created_at":"2026-01-01T00:00:00Z"}
      """;

  @Test
  void readsEncodedExactFiltersFromEdge() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/knowledge");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/knowledge/v1/knowledge-items?item_id=item%3Aone&purpose=risk%3Areview"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("[" + RECORD + "]", MediaType.APPLICATION_JSON));
    var items = new KnowledgeClient(builder.build())
        .list("delegated", "item:one", "risk:review", KnowledgeClient.Classification.PUBLIC);
    assertThat(items).singleElement().satisfies(item -> assertThat(item.state()).isEqualTo(KnowledgeClient.State.CANDIDATE));
    server.verify();
  }

  @Test
  void rejectsMalformedMismatchedOrAboveCeilingMetadata() {
    for (String body : List.of(
        "[null]",
        "[" + RECORD.replace("\"PUBLIC\"", "\"INTERNAL\"") + "]",
        "[" + RECORD.replace("item:one", "other") + "]",
        "[" + RECORD.replace("risk:review", "other") + "]",
        "[" + RECORD.replace("\"revision\":1", "\"revision\":null") + "]",
        "[" + RECORD.replace("\"source_digest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
            "\"source_digest\":\"bad\"") + "]")) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/knowledge-items?item_id=item%3Aone&purpose=risk%3Areview"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      KnowledgeClient client = new KnowledgeClient(builder.build());
      assertThatThrownBy(() -> client.list("delegated", "item:one", "risk:review", KnowledgeClient.Classification.PUBLIC))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }
}
