package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class CaseMaterialClient {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();
  private final boolean enabled;
  private final URI assessmentBase;
  private final OwnerReceiptClient.DelegationProvider delegations;
  private final InternalJsonClient http = new InternalJsonClient();

  CaseMaterialClient(boolean enabled, URI assessmentBase, boolean allowLoopbackHttp,
                     OwnerReceiptClient.DelegationProvider delegations) {
    this.enabled = enabled;
    this.assessmentBase = InternalJsonClient.validateBase(assessmentBase, allowLoopbackHttp);
    this.delegations = java.util.Objects.requireNonNull(delegations, "delegations");
  }

  // Integrity and receipt consistency only; this does not authorize later use or dispatch.
  String fetch(String tenantId, String environmentId, SourceReceiptConsistency.Expected expected,
               SourceReceiptConsistency.CaseReceipt receipt) throws IOException, InterruptedException {
    if (!enabled) throw new IllegalStateException("Case material fetching is disabled.");
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    SourceReceiptConsistency.checkCase(expected, receipt);
    String token = delegations.delegation(tenantId, environmentId, "S07", List.of("assessment.case.read"));
    Map<?, ?> body = http.post(assessmentBase.resolve("/internal/v1/cases/" + expected.caseId() + ":material"),
        token, Map.of());
    if (!expected.caseId().equals(body.get("case_id"))
        || !receipt.caseDigest().equals(body.get("case_digest"))
        || !(body.get("canonical_text") instanceof String material)
        || material.isEmpty() || !UnicodeText.isValid(material)
        || !receipt.caseDigest().equals(Sha256.hex(material.getBytes(StandardCharsets.UTF_8)))) {
      throw new IOException("Case material does not match the owner receipt.");
    }
    Object decoded;
    try {
      decoded = JSON.readValue(material, Object.class);
    } catch (JacksonException failure) {
      throw new IOException("Case material is not valid JSON.", failure);
    }
    if (!(decoded instanceof Map<?, ?> content)
        || !receipt.workloadId().equals(content.get("workload_id"))
        || !receipt.purpose().equals(content.get("purpose"))
        || !receipt.requiredSourceKinds().equals(content.get("required_source_kinds"))) {
      throw new IOException("Case material metadata does not match the owner receipt.");
    }
    return material;
  }
}
