package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class OwnerReceiptClient {
  private final boolean enabled;
  private final URI assessmentBase;
  private final URI knowledgeBase;
  private final DelegationProvider delegations;
  private final InternalJsonClient http;

  interface DelegationProvider {
    String delegation(String tenantId, String environmentId, String audience, List<String> capabilities)
        throws IOException, InterruptedException;
  }

  record Receipts(SourceReceiptConsistency.CaseReceipt sourceCase,
                  SourceReceiptConsistency.RetrievalReceipt retrieval,
                  List<String> excerpts) {
    Receipts {
      excerpts = List.copyOf(excerpts);
    }
  }

  OwnerReceiptClient(boolean enabled, URI assessmentBase, URI knowledgeBase,
                     boolean allowLoopbackHttp, DelegationProvider delegations) {
    this(enabled, assessmentBase, knowledgeBase, allowLoopbackHttp, delegations, new InternalJsonClient());
  }

  OwnerReceiptClient(boolean enabled, URI assessmentBase, URI knowledgeBase,
                     boolean allowLoopbackHttp, DelegationProvider delegations, InternalJsonClient http) {
    this.enabled = enabled;
    this.assessmentBase = InternalJsonClient.validateBase(assessmentBase, allowLoopbackHttp);
    this.knowledgeBase = InternalJsonClient.validateBase(knowledgeBase, allowLoopbackHttp);
    this.delegations = java.util.Objects.requireNonNull(delegations, "delegations");
    this.http = java.util.Objects.requireNonNull(http, "http");
  }

  Receipts fetch(String tenantId, String environmentId, SourceReceiptConsistency.Expected expected)
      throws IOException, InterruptedException {
    var sourceCase = fetchCase(tenantId, environmentId, expected);
    return fetchRetrieval(tenantId, environmentId, expected, sourceCase);
  }

  SourceReceiptConsistency.CaseReceipt fetchCase(
      String tenantId, String environmentId, SourceReceiptConsistency.Expected expected)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Owner receipt fetching is disabled.");
    }
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    SourceReceiptConsistency.validateExpected(expected);
    String assessmentToken = token(tenantId, environmentId, "S07", List.of("assessment.case.read"));
    Map<?, ?> caseBody = http.post(assessmentBase.resolve("/internal/v1/cases/" + expected.caseId() + ":receipt"),
        assessmentToken, Map.of());
    var sourceCase = new SourceReceiptConsistency.CaseReceipt(
        text(caseBody, "case_id"), text(caseBody, "purpose"), text(caseBody, "case_digest"),
        text(caseBody, "workload_id"), strings(caseBody, "required_source_kinds"));
    try {
      SourceReceiptConsistency.checkCase(expected, sourceCase);
    } catch (IllegalArgumentException failure) {
      throw new IOException("Assessment receipt consistency check failed.", failure);
    }
    return sourceCase;
  }

  Receipts fetchRetrieval(String tenantId, String environmentId, SourceReceiptConsistency.Expected expected,
                          SourceReceiptConsistency.CaseReceipt sourceCase) throws IOException, InterruptedException {
    return fetchRetrieval(tenantId, environmentId, expected, sourceCase, null);
  }

  Receipts fetchRetrieval(String tenantId, String environmentId, SourceReceiptConsistency.Expected expected,
                          SourceReceiptConsistency.CaseReceipt sourceCase, Map<String, DigestBoundArtifact> sources)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Owner receipt fetching is disabled.");
    }
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    SourceReceiptConsistency.checkCase(expected, sourceCase);
    String knowledgeToken = retrievalToken(tenantId, environmentId, expected);
    var retrieval = checkedRetrievalReceipt(expected, sourceCase, knowledgeToken);
    Map<?, ?> content = http.get(knowledgeBase.resolve("/v1/retrievals/" + expected.retrievalId()), knowledgeToken);
    List<String> excerpts = excerpts(content, retrieval);
    if (sources != null) {
      KnowledgeSourceVerifier.verifyAll(expected, new Receipts(sourceCase, retrieval, excerpts), sources);
    }
    var refreshed = checkedRetrievalReceipt(expected, sourceCase, knowledgeToken);
    if (!retrieval.equals(refreshed)) {
      throw new IOException("Retrieval receipt changed during content checking.");
    }
    return new Receipts(sourceCase, refreshed, excerpts);
  }

  SourceReceiptConsistency.RetrievalReceipt fetchRetrievalReceipt(
      String tenantId,
      String environmentId,
      SourceReceiptConsistency.Expected expected,
      SourceReceiptConsistency.CaseReceipt sourceCase)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Owner receipt fetching is disabled.");
    }
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    SourceReceiptConsistency.checkCase(expected, sourceCase);
    return checkedRetrievalReceipt(
        expected, sourceCase, retrievalToken(tenantId, environmentId, expected));
  }

  private String retrievalToken(
      String tenantId, String environmentId, SourceReceiptConsistency.Expected expected)
      throws IOException, InterruptedException {
    List<String> capabilities = switch (expected.classification()) {
      case PUBLIC -> List.of("knowledge.retrieval.read");
      case INTERNAL -> List.of("knowledge.retrieval.read", "knowledge.classification.internal.read");
      case RESTRICTED -> List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read");
    };
    return token(tenantId, environmentId, "S08", capabilities);
  }

  private SourceReceiptConsistency.RetrievalReceipt checkedRetrievalReceipt(
      SourceReceiptConsistency.Expected expected,
      SourceReceiptConsistency.CaseReceipt sourceCase,
      String knowledgeToken)
      throws IOException, InterruptedException {
    var retrieval = retrievalReceipt(expected, knowledgeToken);
    try {
      KnowledgeManifestVerifier.verify(expected, sourceCase, retrieval);
    } catch (IllegalArgumentException failure) {
      throw new IOException("Owner receipt consistency check failed.", failure);
    }
    return retrieval;
  }

  private SourceReceiptConsistency.RetrievalReceipt retrievalReceipt(
      SourceReceiptConsistency.Expected expected, String knowledgeToken) throws IOException, InterruptedException {
    Map<?, ?> retrievalBody = http.post(
        knowledgeBase.resolve("/internal/v1/retrievals/" + expected.retrievalId() + ":receipt"),
        knowledgeToken,
        Map.of("case_id", expected.caseId(), "purpose", expected.purpose(),
            "classification", expected.classification().name(), "manifest_digest", expected.manifestDigest()));
    return new SourceReceiptConsistency.RetrievalReceipt(
        text(retrievalBody, "retrieval_id"), text(retrievalBody, "requested_case_id"),
        text(retrievalBody, "case_id"), text(retrievalBody, "case_binding"), text(retrievalBody, "case_digest"),
        text(retrievalBody, "workload_id"), text(retrievalBody, "purpose"),
        classification(retrievalBody), text(retrievalBody, "manifest_digest"),
        text(retrievalBody, "query"), text(retrievalBody, "created_at"),
        results(retrievalBody), bool(retrievalBody, "eligible"), text(retrievalBody, "reason"));
  }

  private String token(String tenantId, String environmentId, String audience, List<String> capabilities)
      throws IOException, InterruptedException {
    return InternalJsonClient.requireToken(delegations.delegation(tenantId, environmentId, audience, capabilities));
  }

  private static List<String> excerpts(Map<?, ?> body, SourceReceiptConsistency.RetrievalReceipt receipt)
      throws IOException {
    if (!receipt.retrievalId().equals(text(body, "retrieval_id"))
        || !receipt.caseId().equals(text(body, "case_id"))
        || !receipt.caseBinding().equals(text(body, "case_binding"))
        || !receipt.caseDigest().equals(text(body, "case_digest"))
        || !receipt.workloadId().equals(text(body, "workload_id"))
        || !receipt.purpose().equals(text(body, "purpose"))
        || !receipt.classification().name().equals(text(body, "max_classification"))
        || !receipt.manifestDigest().equals(text(body, "manifest_digest"))
        || !receipt.query().equals(text(body, "query"))
        || !receipt.createdAt().equals(text(body, "created_at"))
        || !receipt.results().equals(results(body))) {
      throw new IOException("Retrieval content does not match the owner receipt.");
    }
    List<?> items = (List<?>) body.get("results");
    List<String> excerpts = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      Map<?, ?> item = (Map<?, ?>) items.get(i);
      String excerpt = text(item, "excerpt");
      var result = receipt.results().get(i);
      if (!UnicodeText.isValid(excerpt)
          || excerpt.codePointCount(0, excerpt.length()) != result.endOffset() - result.startOffset()
          || !Sha256.hex(excerpt.getBytes(StandardCharsets.UTF_8)).equals(result.excerptDigest())) {
        throw new IOException("Retrieval excerpt does not match the owner receipt.");
      }
      excerpts.add(excerpt);
    }
    return List.copyOf(excerpts);
  }

  private static List<SourceReceiptConsistency.ReceiptResult> results(Map<?, ?> body) throws IOException {
    Object value = body.get("results");
    if (!(value instanceof List<?> list) || list.size() > 10) {
      throw new IOException("Owner receipt has invalid results.");
    }
    List<SourceReceiptConsistency.ReceiptResult> results = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> result)) {
        throw new IOException("Owner receipt has an invalid result.");
      }
      results.add(new SourceReceiptConsistency.ReceiptResult(
          text(result, "item_id"), text(result, "version"), integer(result, "chunk_index"),
          integer(result, "start_offset"), integer(result, "end_offset"),
          text(result, "source_digest"), text(result, "excerpt_digest"),
          strings(result, "allowed_purposes"), classification(result), integer(result, "score")));
    }
    return List.copyOf(results);
  }

  private static String text(Map<?, ?> body, String key) throws IOException {
    Object value = body.get(key);
    if (!(value instanceof String text)) {
      throw new IOException("Owner receipt has an invalid " + key + ".");
    }
    return text;
  }

  private static int integer(Map<?, ?> body, String key) throws IOException {
    Object value = body.get(key);
    if (!(value instanceof Integer number)) {
      throw new IOException("Owner receipt has an invalid " + key + ".");
    }
    return number;
  }

  private static boolean bool(Map<?, ?> body, String key) throws IOException {
    Object value = body.get(key);
    if (!(value instanceof Boolean result)) {
      throw new IOException("Owner receipt has an invalid " + key + ".");
    }
    return result;
  }

  private static List<String> strings(Map<?, ?> body, String key) throws IOException {
    Object value = body.get(key);
    if (!(value instanceof List<?> list) || list.size() > 100) {
      throw new IOException("Owner receipt has an invalid " + key + ".");
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String text)) {
        throw new IOException("Owner receipt has an invalid " + key + ".");
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static SourceReceiptConsistency.Classification classification(Map<?, ?> body) throws IOException {
    try {
      return SourceReceiptConsistency.Classification.valueOf(text(body, "classification"));
    } catch (IllegalArgumentException failure) {
      throw new IOException("Owner receipt has an invalid classification.", failure);
    }
  }

}
