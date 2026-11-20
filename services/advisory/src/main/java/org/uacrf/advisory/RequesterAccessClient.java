package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RequesterAccessClient {
  private final boolean enabled;
  private final URI identityBase;
  private final InternalJsonClient http;

  static final class Principal {
    private final String id;
    private final String kind;
    private final String tenantId;
    private final String environmentId;
    private final Set<String> capabilities;
    private final Instant capabilityExpiryHorizon;
    private final Instant sessionExpiresAt;

    private Principal(
        String id, String kind, String tenantId, String environmentId, Set<String> capabilities,
        Instant capabilityExpiryHorizon, Instant sessionExpiresAt) {
      this.id = id;
      this.kind = kind;
      this.tenantId = tenantId;
      this.environmentId = environmentId;
      this.capabilities = Set.copyOf(capabilities);
      this.capabilityExpiryHorizon = capabilityExpiryHorizon;
      this.sessionExpiresAt = sessionExpiresAt;
    }

    String id() {
      return id;
    }

    String kind() {
      return kind;
    }

    String tenantId() {
      return tenantId;
    }

    String environmentId() {
      return environmentId;
    }

    Set<String> capabilities() {
      return capabilities;
    }

    Instant capabilityExpiryHorizon() {
      return capabilityExpiryHorizon;
    }

    Instant sessionExpiresAt() {
      return sessionExpiresAt;
    }
  }

  RequesterAccessClient(boolean enabled, URI identityBase, boolean allowLoopbackHttp) {
    this(enabled, identityBase, allowLoopbackHttp, new InternalJsonClient());
  }

  RequesterAccessClient(boolean enabled, URI identityBase, boolean allowLoopbackHttp, InternalJsonClient http) {
    this.enabled = enabled;
    this.identityBase = InternalJsonClient.validateBase(identityBase, allowLoopbackHttp);
    this.http = java.util.Objects.requireNonNull(http, "http");
  }

  // This is a prerequisite snapshot, not an admission or source-disclosure decision.
  Principal check(String actorToken, String tenantId, String environmentId,
                  SourceReceiptConsistency.Classification classification) throws IOException, InterruptedException {
    return check(actorToken, tenantId, environmentId, classification, false);
  }

  Principal check(String actorToken, String tenantId, String environmentId,
                  SourceReceiptConsistency.Classification classification, boolean requireProfileRead)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Requester access checking is disabled.");
    }
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    java.util.Objects.requireNonNull(classification, "classification");
    URI uri = identityBase.resolve("/v1/me/access?tenant_id=" + encode(tenantId)
        + "&environment_id=" + encode(environmentId));
    Map<?, ?> body = http.get(uri, actorToken);
    if (!(body.get("principal_id") instanceof String principalId)
        || !(body.get("kind") instanceof String kind)
        || !"HUMAN".equals(kind)
        || !"GLOBAL_GRANTS_ONLY".equals(body.get("scope"))
        || !body.containsKey("service_id") || body.get("service_id") != null
        || !(body.get("capability_ids") instanceof List<?> values)) {
      throw new IOException("S01 returned invalid requester access.");
    }
    Instant horizon = futureExpiry(body.get("capability_expiry_horizon"), "capability expiry horizon");
    Instant sessionExpiresAt = futureExpiry(body.get("session_expires_at"), "session expiry");
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
    if (!capabilities.contains("advisory.run.create")
        || !capabilities.contains("assessment.case.read")
        || !capabilities.contains("knowledge.retrieval.read")
        || (requireProfileRead && !capabilities.contains("catalog.agent.read"))
        || (classification == SourceReceiptConsistency.Classification.INTERNAL
            && !capabilities.contains("knowledge.classification.internal.read")
            && !capabilities.contains("knowledge.classification.restricted.read"))
        || (classification == SourceReceiptConsistency.Classification.RESTRICTED
            && !capabilities.contains("knowledge.classification.restricted.read"))) {
      throw new IOException("Requester lacks required advisory or source access capabilities.");
    }
    return new Principal(principalId, kind, tenantId, environmentId, capabilities, horizon, sessionExpiresAt);
  }

  private static Instant futureExpiry(Object value, String field) throws IOException {
    if (!(value instanceof String text)) {
      throw new IOException("S01 returned invalid requester " + field + ".");
    }
    Instant expiry;
    try {
      expiry = Instant.parse(text);
    } catch (DateTimeParseException failure) {
      throw new IOException("S01 returned invalid requester " + field + ".", failure);
    }
    if (!expiry.isAfter(Instant.now())) {
      throw new IOException("Requester " + field + " has passed.");
    }
    return expiry;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
