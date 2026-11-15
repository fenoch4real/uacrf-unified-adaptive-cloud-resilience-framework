package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class S01DelegationClient implements OwnerReceiptClient.DelegationProvider {
  private final boolean enabled;
  private final URI identityBase;
  private final ActorTokenProvider actorTokens;
  private final InternalJsonClient http;

  interface ActorTokenProvider {
    String token() throws IOException, InterruptedException;
  }

  S01DelegationClient(boolean enabled, URI identityBase, boolean allowLoopbackHttp,
                      ActorTokenProvider actorTokens) {
    this(enabled, identityBase, allowLoopbackHttp, actorTokens, new InternalJsonClient());
  }

  S01DelegationClient(boolean enabled, URI identityBase, boolean allowLoopbackHttp,
                      ActorTokenProvider actorTokens, InternalJsonClient http) {
    this.enabled = enabled;
    this.identityBase = InternalJsonClient.validateBase(identityBase, allowLoopbackHttp);
    this.actorTokens = Objects.requireNonNull(actorTokens, "actorTokens");
    this.http = Objects.requireNonNull(http, "http");
  }

  @Override
  public String delegation(String tenantId, String environmentId, String audience, List<String> capabilities)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("S01 delegation fetching is disabled.");
    }
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    if (!allowed(audience, capabilities)) {
      throw new IllegalArgumentException("Unsupported owner delegation scope.");
    }
    Map<?, ?> response = http.post(identityBase.resolve("/internal/v1/delegations"), actorTokens.token(),
        Map.of("audience", audience, "capability_ids", capabilities,
            "tenant_id", tenantId, "environment_id", environmentId));
    if (!(response.get("access_token") instanceof String token)
        || !(response.get("token_type") instanceof String type)
        || !"Bearer".equalsIgnoreCase(type)
        || !(response.get("expires_in") instanceof Integer lifetime)
        || lifetime <= 0 || lifetime > 30) {
      throw new IOException("S01 returned an invalid delegation response.");
    }
    return InternalJsonClient.requireToken(token);
  }

  private static boolean allowed(String audience, List<String> capabilities) {
    return ("S07".equals(audience) && List.of("assessment.case.read").equals(capabilities))
        || ("S08".equals(audience) && (List.of("knowledge.retrieval.read").equals(capabilities)
            || List.of("knowledge.retrieval.read", "knowledge.classification.internal.read").equals(capabilities)
            || List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read").equals(capabilities)));
  }
}
