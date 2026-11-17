package org.uacrf.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.catalog.CatalogRequests.OwnerReceiptRequest;
import org.uacrf.catalog.CatalogRequests.WorkloadLimits;
import org.uacrf.catalog.CatalogRequests.WorkloadVersionManifest;
import org.uacrf.catalog.CatalogResponses.OwnerReceipt;
import org.uacrf.catalog.CatalogResponses.OwnerReceiptClaims;
import org.uacrf.catalog.CatalogResponses.ServiceRevisionSnapshot;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class OwnerReceiptTests {
  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final String WORKLOAD_ID = "workload-7";
  private static final String VERSION_ID = "version-3";
  private static final String SERVICE_ID = "service-7";
  private static final String PURPOSE = "topology.dependency-proposal";

  private final ObjectMapper objectMapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  private final KeyPair keyPair = generateKeyPair();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final WorkloadVersionManifest manifest = manifest();
  private String manifestJson;
  private String manifestDigest;

  @BeforeEach
  void setUp() throws Exception {
    manifestJson = objectMapper.writeValueAsString(manifest);
    manifestDigest = sha256(manifestJson);
  }

  @Test
  void signsCurrentScopedOwnerReceiptWithKeyIdAndManifestDigest() throws Exception {
    CatalogService service = service(profile("ACTIVE", VERSION_ID, 7L));
    OwnerReceiptRequest request =
        new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(4));

    OwnerReceipt receipt = service.issueOwnerReceipt(delegation(WORKLOAD_ID, PURPOSE), WORKLOAD_ID, request);

    assertThat(receipt.protocol()).isEqualTo("UACRF_OWNER_RECEIPT_V1");
    assertThat(receipt.keyId()).isEqualTo("s02-owner-test-key");
    assertThat(receipt.issuerService()).isEqualTo("S02");
    assertThat(receipt.audienceService()).isEqualTo("S06");
    assertThat(receipt.actorId()).isEqualTo("human-actor-1");
    assertThat(receipt.resourceType()).isEqualTo("agent_workload");
    assertThat(receipt.resourceId()).isEqualTo(WORKLOAD_ID);
    assertThat(receipt.resourceVersion()).isEqualTo(VERSION_ID);
    assertThat(receipt.resourceRevision()).isEqualTo(7);
    assertThat(receipt.authorityEpoch()).isEqualTo(7);
    assertThat(receipt.contentDigest()).isEqualTo(manifestDigest);
    assertThat(receipt.eligible()).isTrue();
    assertThat(Instant.parse(receipt.validUntil())).isEqualTo(NOW.plusSeconds(4));
    assertThat(
            OwnerReceiptSignature.verify(
                objectMapper.writeValueAsString(signingPayload(receipt)),
                receipt.signature(),
                (RSAPublicKey) keyPair.getPublic()))
        .isTrue();

    Map<String, Object> changedKeyId = signingPayload(receipt);
    changedKeyId.put("key_id", "different-key");
    assertThat(
            OwnerReceiptSignature.verify(
                objectMapper.writeValueAsString(changedKeyId),
                receipt.signature(),
                (RSAPublicKey) keyPair.getPublic()))
        .isFalse();
  }

  @Test
  void rejectsStaleVersionRevisionInactiveServiceAndExpiredDeadline() {
    CatalogService active = service(profile("ACTIVE", VERSION_ID, 7L));
    Jwt scopedDelegation = delegation(WORKLOAD_ID, PURPOSE);
    assertCode(
        active,
        scopedDelegation,
        new OwnerReceiptRequest(PURPOSE, "older-version", 7L, NOW.plusSeconds(3)),
        "STALE_WORKLOAD_VERSION");
    assertCode(
        active,
        scopedDelegation,
        new OwnerReceiptRequest(PURPOSE, VERSION_ID, 6L, NOW.plusSeconds(3)),
        "STALE_WORKLOAD_REVISION");
    assertCode(
        service(profile("SUSPENDED", VERSION_ID, 7L)),
        scopedDelegation,
        new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(3)),
        "OWNER_INACTIVE");
    assertCode(
        active,
        scopedDelegation,
        new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW),
        "OPERATION_DEADLINE_EXPIRED");
    assertCode(
        active,
        scopedDelegation,
        new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(6)),
        "OPERATION_DEADLINE_EXPIRED");
  }

  @Test
  void rejectsBroaderOrMismatchedDelegationsBeforeReadingTheCatalog() {
    CatalogService service = service(null);
    Jwt wrongPurpose = delegation(WORKLOAD_ID, "topology.graph.current-use");
    CatalogException purposeDenied =
        assertThrows(
            CatalogException.class,
            () ->
                service.issueOwnerReceipt(
                    wrongPurpose,
                    WORKLOAD_ID,
                    new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(2))));
    assertThat(purposeDenied.code()).isEqualTo("PERMISSION_DENIED");

    Jwt wrongResource = delegation("another-workload", PURPOSE);
    CatalogException resourceDenied =
        assertThrows(
            CatalogException.class,
            () ->
                service.issueOwnerReceipt(
                    wrongResource,
                    WORKLOAD_ID,
                    new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(2))));
    assertThat(resourceDenied.code()).isEqualTo("PERMISSION_DENIED");

    Jwt broadScope =
        delegation("*", WORKLOAD_ID, PURPOSE, List.of("catalog.agent.owner-receipt"), List.of("S02"));
    CatalogException scopeDenied =
        assertThrows(
            CatalogException.class,
            () ->
                service.issueOwnerReceipt(
                    broadScope,
                    WORKLOAD_ID,
                    new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(2))));
    assertThat(scopeDenied.code()).isEqualTo("PERMISSION_DENIED");

    Jwt broadCapability =
        delegation(
            WORKLOAD_ID,
            WORKLOAD_ID,
            PURPOSE,
            List.of("catalog.agent.owner-receipt", "catalog.agent.read"),
            List.of("S02"));
    CatalogException capabilityDenied =
        assertThrows(
            CatalogException.class,
            () ->
                service.issueOwnerReceipt(
                    broadCapability,
                    WORKLOAD_ID,
                    new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(2))));
    assertThat(capabilityDenied.code()).isEqualTo("PERMISSION_DENIED");

    Jwt broadAudience =
        delegation(
            WORKLOAD_ID,
            WORKLOAD_ID,
            PURPOSE,
            List.of("catalog.agent.owner-receipt"),
            List.of("S02", "S06"));
    CatalogException audienceDenied =
        assertThrows(
            CatalogException.class,
            () ->
                service.issueOwnerReceipt(
                    broadAudience,
                    WORKLOAD_ID,
                    new OwnerReceiptRequest(PURPOSE, VERSION_ID, null, NOW.plusSeconds(2))));
    assertThat(audienceDenied.code()).isEqualTo("PERMISSION_DENIED");
  }

  @Test
  void signsExactImmutableServiceRevisionForBothGraphPurposes() throws Exception {
    ServiceRevisionSnapshot snapshot =
        new ServiceRevisionSnapshot(SERVICE_ID, "Payments", "owner-1", 3, "ACTIVE", 1, List.of("agent-1"));
    String snapshotJson = objectMapper.writeValueAsString(snapshot);
    String digest = sha256(OwnerReceiptSignature.canonicalize(snapshotJson));
    CatalogService service = serviceRevision(snapshot, digest);

    for (String purpose :
        List.of(
            "topology.graph.subgraph-selection",
            "topology.graph.difference-classification")) {
      OwnerReceipt receipt =
          service.issueServiceOwnerReceipt(
              serviceDelegation(
                  SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5)),
              SERVICE_ID,
              new OwnerReceiptRequest(purpose, "1", 1L, NOW.plusSeconds(4)));

      assertThat(receipt.resourceType()).isEqualTo("service");
      assertThat(receipt.resourceId()).isEqualTo(SERVICE_ID);
      assertThat(receipt.resourceVersion()).isEqualTo("1");
      assertThat(receipt.resourceRevision()).isEqualTo(1);
      assertThat(receipt.contentDigest()).isEqualTo(digest);
      assertThat(receipt.tenantId()).isEqualTo("tenant-a");
      assertThat(receipt.environmentId()).isEqualTo("environment-a");
      assertThat(receipt.actorId()).isEqualTo("human-actor-1");
      assertThat(receipt.purpose()).isEqualTo(purpose);
      var retained =
          service.getServiceRevision(
              serviceDelegation(
                  SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5)),
              SERVICE_ID,
              "1");
      assertThat(retained.contentDigest()).isEqualTo(receipt.contentDigest());
      assertThat(retained.snapshot()).isEqualTo(objectMapper.readTree(snapshotJson));
      assertThat(
              OwnerReceiptSignature.verify(
                  objectMapper.writeValueAsString(signingPayload(receipt)),
                  receipt.signature(),
                  (RSAPublicKey) keyPair.getPublic()))
          .isTrue();
    }
  }

  @Test
  void rejectsMismatchedServiceVersionAndStoredDigestForServiceReceipts() throws Exception {
    ServiceRevisionSnapshot snapshot =
        new ServiceRevisionSnapshot(SERVICE_ID, "Payments", "owner-1", 3, "ACTIVE", 1, List.of());
    String digest =
        sha256(OwnerReceiptSignature.canonicalize(objectMapper.writeValueAsString(snapshot)));
    String purpose = "topology.graph.subgraph-selection";
    CatalogService service = serviceRevision(snapshot, digest);

    assertServiceCode(
        service,
        serviceDelegation(
            SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5)),
        "another-service",
        new OwnerReceiptRequest(purpose, "1", null, NOW.plusSeconds(3)),
        "PERMISSION_DENIED");
    assertServiceCode(
        service,
        serviceDelegation(
            SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5)),
        SERVICE_ID,
        new OwnerReceiptRequest(purpose, "2", null, NOW.plusSeconds(3)),
        "NOT_FOUND");
    assertServiceCode(
        service,
        serviceDelegation(
            SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5)),
        SERVICE_ID,
        new OwnerReceiptRequest(purpose, "1", 2L, NOW.plusSeconds(3)),
        "STALE_SERVICE_REVISION");
    assertServiceCode(
        serviceRevision(snapshot, "b".repeat(64)),
        serviceDelegation(
            SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5)),
        SERVICE_ID,
        new OwnerReceiptRequest(purpose, "1", null, NOW.plusSeconds(3)),
        "SERVICE_REVISION_INVALID");
  }

  @Test
  void rejectsExpiredOrOverbroadServiceReceiptDelegations() throws Exception {
    ServiceRevisionSnapshot snapshot =
        new ServiceRevisionSnapshot(SERVICE_ID, "Payments", "owner-1", 3, "ACTIVE", 1, List.of());
    String digest =
        sha256(OwnerReceiptSignature.canonicalize(objectMapper.writeValueAsString(snapshot)));
    CatalogService service = serviceRevision(snapshot, digest);
    String purpose = "topology.graph.difference-classification";

    assertServiceCode(
        service,
        serviceDelegation(
            SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.minusSeconds(1)),
        SERVICE_ID,
        new OwnerReceiptRequest(purpose, "1", null, NOW.plusSeconds(3)),
        "OPERATION_DEADLINE_EXPIRED");
    assertServiceCode(
        service,
        serviceDelegation(
            SERVICE_ID,
            purpose,
            List.of("catalog.service.read", "catalog.service.write"),
            NOW.plusSeconds(5)),
        SERVICE_ID,
        new OwnerReceiptRequest(purpose, "1", null, NOW.plusSeconds(3)),
        "PERMISSION_DENIED");
    assertServiceCode(
        service,
        serviceDelegation(
            SERVICE_ID,
            "topology.graph.current-use",
            List.of("catalog.service.read"),
            NOW.plusSeconds(5)),
        SERVICE_ID,
        new OwnerReceiptRequest(
            "topology.graph.current-use", "1", null, NOW.plusSeconds(3)),
        "PERMISSION_DENIED");
  }

  @Test
  void exactRevisionReadRejectsMissingAndBroadOrMismatchedDelegations() throws Exception {
    ServiceRevisionSnapshot snapshot =
        new ServiceRevisionSnapshot(SERVICE_ID, "Payments", "owner-1", 3, "ACTIVE", 1, List.of());
    String digest =
        sha256(OwnerReceiptSignature.canonicalize(objectMapper.writeValueAsString(snapshot)));
    CatalogService service = serviceRevision(snapshot, digest);
    String purpose = "topology.graph.subgraph-selection";
    Jwt valid = serviceDelegation(SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5));

    assertReadCode(service, valid, SERVICE_ID, "2", "NOT_FOUND");
    assertReadCode(service, valid, "other-service", "1", "PERMISSION_DENIED");
    assertReadCode(
        service,
        serviceDelegation(SERVICE_ID, "topology.graph.current-use", List.of("catalog.service.read"), NOW.plusSeconds(5)),
        SERVICE_ID, "1", "PERMISSION_DENIED");
    assertReadCode(
        service,
        serviceDelegation(SERVICE_ID, purpose, List.of("catalog.service.read", "catalog.service.write"), NOW.plusSeconds(5)),
        SERVICE_ID, "1", "PERMISSION_DENIED");
    assertReadCode(
        service,
        serviceDelegation(SERVICE_ID, purpose, List.of("catalog.agent.owner-receipt"), NOW.plusSeconds(5)),
        SERVICE_ID, "1", "PERMISSION_DENIED");
    assertReadCode(
        service,
        serviceDelegation(SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.minusSeconds(1)),
        SERVICE_ID, "1", "PERMISSION_DENIED");

    for (Map.Entry<String, Object> replacement :
        Map.<String, Object>of(
                "aud", List.of("S02", "S06"),
                "service_scope", "other-service",
                "resource_type", "agent_workload",
                "resource_id", "other-service",
                "tenant_id", "foreign-tenant")
            .entrySet()) {
      Map<String, Object> claims = new java.util.HashMap<>(valid.getClaims());
      claims.put(replacement.getKey(), replacement.getValue());
      Jwt altered = new Jwt("altered", valid.getIssuedAt(), valid.getExpiresAt(), valid.getHeaders(), claims);
      assertReadCode(
          service, altered, SERVICE_ID, "1",
          replacement.getKey().equals("tenant_id") ? "NOT_FOUND" : "PERMISSION_DENIED");
    }
  }

  @Test
  void retainsLegacySnapshotWithoutInventingMembership() throws Exception {
    String snapshotJson =
        """
        {"service_id":"service-7","name":"Payments","owner_id":"owner-1","criticality":3,"lifecycle":"ACTIVE","revision":1}
        """;
    String digest = sha256(OwnerReceiptSignature.canonicalize(snapshotJson));
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    doReturn(List.of(Map.of("snapshot", snapshotJson, "content_digest", digest)))
        .when(jdbc).queryForList(anyString(), any(Object[].class));
    CatalogService service = new CatalogService(
        jdbc, objectMapper,
        new OwnerReceiptSigningKey(
            "s02-owner-test-key", (java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()),
        clock);
    String purpose = "topology.graph.subgraph-selection";
    Jwt jwt = serviceDelegation(SERVICE_ID, purpose, List.of("catalog.service.read"), NOW.plusSeconds(5));
    var retained = service.getServiceRevision(jwt, SERVICE_ID, "1");
    assertThat(retained.snapshot().has("owned_agent_workload_ids")).isFalse();
    assertThat(retained.contentDigest()).isEqualTo(digest);
    assertThat(service.issueServiceOwnerReceipt(
        jwt, SERVICE_ID, new OwnerReceiptRequest(purpose, "1", 1L, NOW.plusSeconds(3)))
        .contentDigest()).isEqualTo(digest);
  }

  private void assertReadCode(
      CatalogService service, Jwt jwt, String serviceId, String version, String expectedCode) {
    CatalogException error =
        assertThrows(CatalogException.class, () -> service.getServiceRevision(jwt, serviceId, version));
    assertThat(error.code()).isEqualTo(expectedCode);
  }

  private CatalogService service(Map<String, Object> row) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    if (row != null) {
      doReturn(List.of(row)).when(jdbc).queryForList(anyString(), any(Object[].class));
    }
    return new CatalogService(
        jdbc,
        objectMapper,
        new OwnerReceiptSigningKey(
            "s02-owner-test-key", (java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()),
        clock);
  }

  private CatalogService serviceRevision(ServiceRevisionSnapshot snapshot, String storedDigest)
      throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    String snapshotJson = objectMapper.writeValueAsString(snapshot);
    Map<String, Object> row =
        Map.of("snapshot", snapshotJson, "content_digest", storedDigest);
    doAnswer(
            invocation -> {
              if ("tenant-a".equals(invocation.getArgument(1))
                  && "environment-a".equals(invocation.getArgument(2))
                  && SERVICE_ID.equals(invocation.getArgument(3))
                  && ((Number) invocation.getArgument(4)).longValue() == snapshot.revision()) {
                return List.of(row);
              }
              return List.of();
            })
        .when(jdbc)
        .queryForList(anyString(), any(Object[].class));
    return new CatalogService(
        jdbc,
        objectMapper,
        new OwnerReceiptSigningKey(
            "s02-owner-test-key", (java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()),
        clock);
  }

  private Map<String, Object> profile(String serviceLifecycle, String activeVersion, long revision) {
    return Map.of(
        "revision", revision,
        "active_version_id", activeVersion,
        "service_lifecycle", serviceLifecycle,
        "version_state", "ACTIVE",
        "digest", manifestDigest,
        "manifest", manifestJson);
  }

  private Jwt delegation(String resourceId, String purpose) {
    return delegation(
        resourceId,
        resourceId,
        purpose,
        List.of("catalog.agent.owner-receipt"),
        List.of("S02"));
  }

  private Jwt delegation(
      String scope,
      String resourceId,
      String purpose,
      List<String> capabilities,
      List<String> audience) {
    return Jwt.withTokenValue("delegation")
        .header("alg", "RS256")
        .issuer("https://uacrf.local/s01")
        .subject("s01-delegator")
        .audience(audience)
        .issuedAt(NOW.minusSeconds(1))
        .expiresAt(NOW.plusSeconds(5))
        .claim("tenant_id", "tenant-a")
        .claim("environment_id", "environment-a")
        .claim("service_scope", scope)
        .claim("actor_issuer", "https://identity.example.test")
        .claim("actor_subject", "human-actor-1")
        .claim("capabilities", capabilities)
        .claim("resource_type", "agent_workload")
        .claim("resource_id", resourceId)
        .claim("purpose", purpose)
        .build();
  }

  private Jwt serviceDelegation(
      String resourceId, String purpose, List<String> capabilities, Instant expiresAt) {
    return Jwt.withTokenValue("service-delegation")
        .header("alg", "RS256")
        .issuer("https://uacrf.local/s01")
        .subject("s01-delegator")
        .audience(List.of("S02"))
        .issuedAt(NOW.minusSeconds(5))
        .expiresAt(expiresAt)
        .claim("tenant_id", "tenant-a")
        .claim("environment_id", "environment-a")
        .claim("service_scope", resourceId)
        .claim("actor_issuer", "https://identity.example.test")
        .claim("actor_subject", "human-actor-1")
        .claim("capabilities", capabilities)
        .claim("resource_type", "service")
        .claim("resource_id", resourceId)
        .claim("purpose", purpose)
        .build();
  }

  private void assertCode(
      CatalogService service, Jwt jwt, OwnerReceiptRequest request, String expectedCode) {
    CatalogException error =
        assertThrows(
            CatalogException.class,
            () -> service.issueOwnerReceipt(jwt, WORKLOAD_ID, request));
    assertThat(error.code()).isEqualTo(expectedCode);
  }

  private Map<String, Object> signingPayload(OwnerReceipt receipt) {
    return CatalogService.ownerReceiptSigningPayload(
        new OwnerReceiptClaims(
            receipt.protocol(),
            receipt.keyId(),
            receipt.receiptId(),
            receipt.issuerService(),
            receipt.audienceService(),
            receipt.tenantId(),
            receipt.environmentId(),
            receipt.actorId(),
            receipt.resourceType(),
            receipt.resourceId(),
            receipt.resourceVersion(),
            receipt.resourceRevision(),
            receipt.contentDigest(),
            receipt.authorityEpoch(),
            receipt.eligible(),
            receipt.purpose(),
            receipt.issuedAt(),
            receipt.validUntil()));
  }

  private void assertServiceCode(
      CatalogService service,
      Jwt jwt,
      String serviceId,
      OwnerReceiptRequest request,
      String expectedCode) {
    CatalogException error =
        assertThrows(
            CatalogException.class,
            () -> service.issueServiceOwnerReceipt(jwt, serviceId, request));
    assertThat(error.code()).isEqualTo(expectedCode);
  }

  private static WorkloadVersionManifest manifest() {
    return new WorkloadVersionManifest(
        "a".repeat(64),
        "model-v1",
        List.of(),
        List.of(),
        "memory",
        "evaluation",
        "recovery",
        "runtime",
        "data-policy",
        "handoff",
        new WorkloadLimits(1, 0, 0, 1));
  }

  private static String sha256(String value) throws Exception {
    return java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
