package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.time.Instant;
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
  void stagingChecksReceiptAgainstContentAndCommand() {
    String digest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    String receipt = RECORD.replace("item:one", "item-1").replace("risk:review", "review")
        .replace("https://example.test", "sample:source").replace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", digest);
    var command = new KnowledgeClient.StageRequest("item-1", "v1", "Title", "sample:source", "hello",
        KnowledgeClient.Classification.PUBLIC, List.of("review"), List.of(),
        Instant.parse("2030-01-01T00:00:00Z"), "Reason");
    for (boolean valid : List.of(true, false)) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/knowledge-items:idempotent"))
          .andExpect(header("Idempotency-Key", "key"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(jsonPath("$.item_id").value("item-1"))
          .andExpect(jsonPath("$.content").value("hello"))
          .andRespond(withSuccess(valid ? receipt : receipt.replace(digest, "a".repeat(64)), MediaType.APPLICATION_JSON));
      KnowledgeClient client = new KnowledgeClient(builder.build());
      if (valid) assertThat(client.stage("delegated", "key", command).state()).isEqualTo(KnowledgeClient.State.CANDIDATE);
      else assertThatThrownBy(() -> client.stage("delegated", "key", command)).isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }

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
