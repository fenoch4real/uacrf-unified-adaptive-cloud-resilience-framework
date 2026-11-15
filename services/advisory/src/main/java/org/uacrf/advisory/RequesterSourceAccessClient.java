package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RequesterSourceAccessClient {
  private final boolean enabled;
  private final URI identityBase;
  private final InternalJsonClient http = new InternalJsonClient();

  record Principal(String id, String kind) {}

  RequesterSourceAccessClient(boolean enabled, URI identityBase, boolean allowLoopbackHttp) {
    this.enabled = enabled;
    this.identityBase = InternalJsonClient.validateBase(identityBase, allowLoopbackHttp);
  }

  // These source capabilities alone do not authorize an advisory operation.
  Principal check(String actorToken, String tenantId, String environmentId,
                  SourceReceiptConsistency.Classification classification) throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Requester source access checking is disabled.");
    }
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    java.util.Objects.requireNonNull(classification, "classification");
    URI uri = identityBase.resolve("/v1/me/access?tenant_id=" + encode(tenantId)
        + "&environment_id=" + encode(environmentId));
    Map<?, ?> body = http.get(uri, actorToken);
    if (!(body.get("principal_id") instanceof String principalId)
        || !(body.get("kind") instanceof String kind)
        || !("HUMAN".equals(kind) || "SYSTEM".equals(kind))
        || !"GLOBAL_GRANTS_ONLY".equals(body.get("scope"))
        || !body.containsKey("service_id") || body.get("service_id") != null
        || !(body.get("capability_ids") instanceof List<?> values)) {
      throw new IOException("S01 returned invalid requester access.");
    }
    try {
      InternalJsonClient.requireId(principalId);
    } catch (IllegalArgumentException failure) {
      throw new IOException("S01 returned invalid requester access.", failure);
    }
    Set<String> capabilities = new HashSet<>();
    for (Object value : values) {
      if (!(value instanceof String capability) || capability.isBlank()
          || capability.length() > 200 || !capabilities.add(capability)) {
        throw new IOException("S01 returned invalid requester access.");
      }
    }
    if (!capabilities.contains("assessment.case.read")
        || !capabilities.contains("knowledge.retrieval.read")
        || (classification == SourceReceiptConsistency.Classification.INTERNAL
            && !capabilities.contains("knowledge.classification.internal.read")
            && !capabilities.contains("knowledge.classification.restricted.read"))
        || (classification == SourceReceiptConsistency.Classification.RESTRICTED
            && !capabilities.contains("knowledge.classification.restricted.read"))) {
      throw new IOException("Requester lacks required source access capabilities.");
    }
    return new Principal(principalId, kind);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
