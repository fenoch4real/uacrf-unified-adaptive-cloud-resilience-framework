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
  void checksCandidateContentAndBoundReviewReceipt() {
    String digest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    String candidate = RECORD.replace("item:one", "item-1").replace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", digest);
    String reviewed = candidate.replace("\"state\":\"CANDIDATE\",\"revision\":1",
        "\"state\":\"REJECTED\",\"revision\":2,\"reviewed_by\":\"reviewer\",\"review_reason\":\"not suitable\"");
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/v1/knowledge-items/item-1/versions/v1:candidate-content"))
        .andRespond(withSuccess("{\"metadata\":" + candidate + ",\"content\":\"hello\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://edge.test/v1/knowledge-items/item-1:review-bound"))
        .andExpect(jsonPath("$.expected_version").value("v1"))
        .andExpect(jsonPath("$.expected_source_digest").value(digest))
        .andRespond(withSuccess(reviewed, MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://edge.test/v1/knowledge-items/item-1/versions/v1:candidate-content"))
        .andRespond(withSuccess("{\"metadata\":" + candidate + ",\"content\":\"changed\"}", MediaType.APPLICATION_JSON));
    KnowledgeClient client = new KnowledgeClient(builder.build());
    assertThat(client.candidate("reader", "item-1", "v1", KnowledgeClient.Classification.PUBLIC).content()).isEqualTo("hello");
    var review = new KnowledgeClient.BoundReviewRequest("REJECT", 1, "v1", digest, "not suitable");
    assertThat(client.review("reviewer", "item-1", review, KnowledgeClient.Classification.PUBLIC).state())
        .isEqualTo(KnowledgeClient.State.REJECTED);
    assertThatThrownBy(() -> client.candidate("reader", "item-1", "v1", KnowledgeClient.Classification.PUBLIC))
        .isInstanceOf(IllegalStateException.class);
    server.verify();
  }

  @Test
  void readsOnlyMatchingRetrievalWithinSelectedClearance() {
    String id = "a1234567-1234-4234-8234-123456789012";
    String receipt = """
        {"retrieval_id":"%s","case_id":null,"case_binding":"NOT_VERIFIED","case_digest":null,
         "workload_id":null,"purpose":"review","max_classification":"INTERNAL","query":"hello",
         "created_at":"2026-01-01T00:00:00Z",
         "manifest_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","results":[]}
        """.formatted(id);
    for (String body : List.of(receipt, receipt.replace(id, "b1234567-1234-4234-8234-123456789012"))) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/retrievals/" + id))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      KnowledgeClient client = new KnowledgeClient(builder.build());
      if (body.equals(receipt)) assertThat(client.readRetrieval("reader", id, KnowledgeClient.Classification.INTERNAL).retrievalId()).isEqualTo(id);
      else assertThatThrownBy(() -> client.readRetrieval("reader", id, KnowledgeClient.Classification.INTERNAL))
          .isInstanceOf(IllegalStateException.class);
      server.verify();
    }
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/v1/retrievals/" + id))
        .andRespond(withSuccess(receipt, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> new KnowledgeClient(builder.build()).readRetrieval("reader", id, KnowledgeClient.Classification.PUBLIC))
        .isInstanceOf(IllegalStateException.class);
    server.verify();
  }

  @Test
  void checksRetrievalAndConcealedOrDigestBoundExcerpts() {
    String digest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    String receipt = """
        {"retrieval_id":"a1234567-1234-4234-8234-123456789012","case_id":null,
         "case_binding":"NOT_VERIFIED","case_digest":null,"workload_id":null,"purpose":"review",
         "max_classification":"PUBLIC","query":"hello","created_at":"2026-01-01T00:00:00Z",
         "manifest_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "results":[{"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":5,
          "source_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "excerpt_digest":"%s","allowed_purposes":["review"],"classification":"PUBLIC","score":1,"excerpt":"hello"}]}
        """.formatted(digest);
    var command = new KnowledgeClient.RetrievalRequest(null, "review", KnowledgeClient.Classification.PUBLIC, "hello", 10);
    for (String body : List.of(receipt, receipt.replace("\"excerpt\":\"hello\"", "\"excerpt\":null"),
        receipt.replace("\"excerpt\":\"hello\"", "\"excerpt\":\"wrong\""),
        receipt.replace("\"classification\":\"PUBLIC\"", "\"classification\":\"INTERNAL\""),
        receipt.replace("\"purpose\":\"review\"", "\"purpose\":\"other\""))) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/retrievals:idempotent"))
          .andExpect(header("Idempotency-Key", "key"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(jsonPath("$.query").value("hello"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      KnowledgeClient client = new KnowledgeClient(builder.build());
      if (body.equals(receipt) || body.contains("\"excerpt\":null")) {
        assertThat(client.retrieve("delegated", "key", command).results()).hasSize(1);
      } else {
        assertThatThrownBy(() -> client.retrieve("delegated", "key", command)).isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

  @Test
  void checksBoundWithdrawalReceipt() {
    String digest = "a".repeat(64);
    String receipt = RECORD.replace("\"state\":\"CANDIDATE\",\"revision\":1",
        "\"state\":\"WITHDRAWN\",\"revision\":3,\"withdrawn_by\":\"withdrawer\",\"withdrawal_reason\":\"obsolete\"");
    var command = new KnowledgeClient.BoundWithdrawRequest(2, "v1", digest, "obsolete");
    for (String body : List.of(receipt, receipt.replace("\"version\":\"v1\"", "\"version\":\"v2\""),
        receipt.replace(digest, "b".repeat(64)), receipt.replace("\"revision\":3", "\"revision\":2"),
        receipt.replace("\"withdrawal_reason\":\"obsolete\"", "\"withdrawal_reason\":\"other\""))) {
      RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
      MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("http://edge.test/v1/knowledge-items/item%3Aone:withdraw-bound"))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
          .andExpect(jsonPath("$.expected_version").value("v1"))
          .andExpect(jsonPath("$.expected_revision").value(2))
          .andExpect(jsonPath("$.expected_source_digest").value(digest))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      KnowledgeClient client = new KnowledgeClient(builder.build());
      if (body.equals(receipt)) {
        assertThat(client.withdraw("delegated", "item:one", command, KnowledgeClient.Classification.PUBLIC).state())
            .isEqualTo(KnowledgeClient.State.WITHDRAWN);
      } else {
        assertThatThrownBy(() -> client.withdraw("delegated", "item:one", command, KnowledgeClient.Classification.PUBLIC))
            .isInstanceOf(IllegalStateException.class);
      }
      server.verify();
    }
  }

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
