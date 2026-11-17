package org.uacrf.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

class EvidenceTopologySummaryTests {
  private static final String EVIDENCE_ID = "evidence-1";
  private static final String PURPOSE = "topology.dependency-proposal";
  private static final String PAYLOAD = "raw evidence payload";
  private static final String REVIEW_TEXT = "private reviewer free text";
  private static final Instant OBSERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant REVIEWED_AT = Instant.parse("2026-01-01T01:00:00Z");
  private static final Instant CUTOFF = Instant.parse("2026-01-02T00:00:00Z");
  private JdbcTemplate jdbc;
  private EvidenceService service;
  private Map<String, Object> row;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service = new EvidenceService(jdbc, null);
    row = evidenceRow();
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> List.of(row));
  }

  @Test
  void returnsOnlyBoundedMetadataForCurrentQualifiedEvidence() {
    EvidenceResponses.TopologySummaryResult result =
        service.topologySummary(delegation(), EVIDENCE_ID, request(sha(PAYLOAD), 3, CUTOFF));

    assertThat(result.usableAsEvidence()).isTrue();
    assertThat(result.summary())
        .isEqualTo(
            new EvidenceResponses.TopologySummary(
                EVIDENCE_ID,
                "PROVIDER",
                "provider:source-1",
                7,
                OBSERVED_AT.toString(),
                "measurement.latency",
                "QUALIFIED",
                3,
                sha(PAYLOAD)));
    assertThat(result.toString()).doesNotContain(PAYLOAD, REVIEW_TEXT, "reviewReason");
  }

  @Test
  void requiresExactS01AudienceResourcePurposeAndCapabilityScope() {
    List<Jwt> outOfScope =
        List.of(
            delegation("S06", "evidence_event", EVIDENCE_ID, EVIDENCE_ID, PURPOSE, true),
            delegation("S04", "agent_workload", EVIDENCE_ID, EVIDENCE_ID, PURPOSE, true),
            delegation("S04", "evidence_event", "other-evidence", EVIDENCE_ID, PURPOSE, true),
            delegation("S04", "evidence_event", EVIDENCE_ID, "other-evidence", PURPOSE, true),
            delegation("S04", "evidence_event", EVIDENCE_ID, EVIDENCE_ID, "wrong-purpose", true),
            delegation("S04", "evidence_event", EVIDENCE_ID, EVIDENCE_ID, PURPOSE, false));

    for (Jwt jwt : outOfScope) {
      assertThatThrownBy(() -> service.topologySummary(jwt, EVIDENCE_ID, request(sha(PAYLOAD), 3, CUTOFF)))
          .isInstanceOf(EvidenceException.class)
          .extracting(error -> ((EvidenceException) error).code())
          .isEqualTo("DELEGATION_SCOPE_MISMATCH");
    }
  }

  @Test
  void rejectsStaleMismatchedUnqualifiedAndConflictingEvidence() {
    assertIneligible(request("0".repeat(64), 3, CUTOFF));
    assertIneligible(request(sha(PAYLOAD), 4, CUTOFF));
    assertIneligible(request(sha(PAYLOAD), 3, REVIEWED_AT.minusNanos(1)));
    row.put("qualification_state", "UNVERIFIED");
    assertIneligible(request(sha(PAYLOAD), 3, CUTOFF));

    row = evidenceRow();
    row.put("has_conflict", true);
    assertIneligible(request(sha(PAYLOAD), 3, CUTOFF));

    row = evidenceRow();
    row.put("payload", "tampered payload");
    assertIneligible(request(sha(PAYLOAD), 3, CUTOFF));
  }

  private void assertIneligible(EvidenceRequests.TopologySummaryRequest request) {
    assertThatThrownBy(() -> service.topologySummary(delegation(), EVIDENCE_ID, request))
        .isInstanceOf(EvidenceException.class)
        .extracting(error -> ((EvidenceException) error).code())
        .isEqualTo("EVIDENCE_NOT_CURRENT");
  }

  private static EvidenceRequests.TopologySummaryRequest request(
      String digest, long revision, Instant cutoff) {
    return new EvidenceRequests.TopologySummaryRequest(PURPOSE, digest, revision, cutoff);
  }

  private static Map<String, Object> evidenceRow() {
    Map<String, Object> values = new HashMap<>();
    values.put("id", EVIDENCE_ID);
    values.put("source_class", "PROVIDER");
    values.put("source_ref", "provider:source-1");
    values.put("sequence", 7L);
    values.put("observed_at", Timestamp.from(OBSERVED_AT));
    values.put("kind", "measurement.latency");
    values.put("payload", PAYLOAD);
    values.put("payload_digest", sha(PAYLOAD));
    values.put("qualification_state", "QUALIFIED");
    values.put("revision", 3L);
    values.put("reviewed_at", Timestamp.from(REVIEWED_AT));
    values.put("review_reason", REVIEW_TEXT);
    values.put("has_conflict", false);
    return values;
  }

  private static Jwt delegation() {
    return delegation("S04", "evidence_event", EVIDENCE_ID, EVIDENCE_ID, PURPOSE, true);
  }

  private static Jwt delegation(
      String audience,
      String resourceType,
      String resourceId,
      String serviceScope,
      String purpose,
      boolean hasCapability) {
    return Jwt.withTokenValue("signed-by-s01")
        .header("alg", "RS256")
        .issuer("https://uacrf.local/s01")
        .subject("s01|topology")
        .audience(List.of(audience))
        .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
        .expiresAt(Instant.parse("2026-01-01T00:00:30Z"))
        .claim("tenant_id", "tenant-1")
        .claim("environment_id", "environment-1")
        .claim("service_scope", serviceScope)
        .claim("actor_issuer", "https://idp.example.test")
        .claim("actor_subject", "topology-service")
        .claim("capabilities", hasCapability ? List.of("evidence.topology.summary") : List.of())
        .claim("resource_type", resourceType)
        .claim("resource_id", resourceId)
        .claim("purpose", purpose)
        .build();
  }

  private static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
