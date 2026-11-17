package org.uacrf.decisiongovernance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PolicyReviewIntegrationTests {
  private static final String DELEGATION_KEY_ID = "decision-governance-review-key";
  private static final KeyPair DELEGATION_KEY = generateKeyPair();
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final String ROOT = "/v1/authorization-policy-bundles";
  private static final String TENANT = "tenant-review";
  private static final String ENVIRONMENT = "environment-review";
  private static final String SERVICE = "service-review";
  private static final String PROPOSER = "proposer-1";
  private static final KeyPair REVIEWER_1_KEY = generateKeyPair();
  private static final KeyPair REVIEWER_2_KEY = generateKeyPair();
  private static final KeyPair REVIEWER_3_KEY = generateKeyPair();
  private static final KeyPair UNTRUSTED_KEY = generateKeyPair();

  @Container
  private static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("decision_governance_review")
          .withUsername("decision_governance_review")
          .withPassword("decision_governance_review");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
    properties.add(
        "uacrf.delegation.public-key-base64",
        () -> Base64.getEncoder().encodeToString(DELEGATION_KEY.getPublic().getEncoded()));
    properties.add("uacrf.delegation.key-id", () -> DELEGATION_KEY_ID);
    properties.add("uacrf.policy-trust.set-json", PolicyReviewIntegrationTests::trustSetJson);
  }

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void validSignedApprovalMakesTheExactCurrentRevisionEligibleWithoutAnyPermit() throws Exception {
    JsonNode draft = propose("policy-valid", 0, 1, List.of("reviewer-1"), "2020-01-01T00:00:00Z");
    HttpResponse<String> response =
        decide("reviewer-1", "policy-valid", approval("policy-valid", draft, "reviewer-1", "Looks right", REVIEWER_1_KEY, "key-reviewer-1"), "valid-1");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode policy = body(response).path("policy");
    assertThat(policy.path("decision").asText()).isEqualTo("APPROVED");
    assertThat(policy.path("current_eligible").asBoolean()).isTrue();
    assertThat(policy.path("reviewer_id").asText()).isEqualTo("reviewer-1");
    assertThat(policy.path("trust_revision").asLong()).isEqualTo(7);
    assertThat(policy.path("trust_digest").asText()).matches("^[a-f0-9]{64}$");
    assertThat(policy.path("assertion_digest").asText()).matches("^[a-f0-9]{64}$");
    assertThat(policy.path("signed_assertion").asText().split("\\.")).hasSize(3);
    assertThat(policy.path("authority").asText())
        .isEqualTo("SIGNED_POLICY_CONFIGURATION_NOT_ACTION_AUTHORIZATION");
    assertThat(policy.path("review").path("valid_approval_count").asInt()).isEqualTo(1);
    assertThat(policy.path("review").path("votes")).hasSize(1);
    assertThat(response.body().toLowerCase()).doesNotContain("action_permit", "\"permit\"", "reservation");
    assertThat(policyFields(policy)).noneMatch(name -> name.contains("permit"));

    JsonNode read = body(get("reviewer-1", ROOT + "/policy-valid")).path("policy");
    assertThat(read.path("current_eligible").asBoolean()).isTrue();
    JsonNode listed =
        body(get("reviewer-1", ROOT + "?service_id=" + SERVICE + "&page_size=200")).path("items");
    assertThat(listed.toString()).contains("\"approval_count\":1");
    assertThat(votes("policy-valid")).isEqualTo(1);
  }

  @Test
  void approvalOutsideTheEffectiveWindowOrOnASupersededRevisionIsNotCurrentlyEligible() throws Exception {
    JsonNode future = propose("policy-future", 0, 1, List.of("reviewer-1"), "2090-01-01T00:00:00Z");
    JsonNode approved =
        body(decide("reviewer-1", "policy-future", approval("policy-future", future, "reviewer-1", "Future", REVIEWER_1_KEY, "key-reviewer-1"), "future-1")).path("policy");
    assertThat(approved.path("decision").asText()).isEqualTo("APPROVED");
    assertThat(approved.path("current_eligible").asBoolean()).isFalse();

    JsonNode first = propose("policy-superseded", 0, 1, List.of("reviewer-1"), "2020-01-01T00:00:00Z");
    assertThat(
            decide("reviewer-1", "policy-superseded", approval("policy-superseded", first, "reviewer-1", "First", REVIEWER_1_KEY, "key-reviewer-1"), "super-1")
                .statusCode())
        .isEqualTo(200);
    JsonNode second = propose("policy-superseded", 1, 1, List.of("reviewer-1"), "2020-01-01T00:00:00Z");
    assertThat(second.path("decision").asText()).isEqualTo("DRAFT");
    JsonNode historical = body(get("reviewer-1", ROOT + "/policy-superseded?revision=1")).path("policy");
    assertThat(historical.path("decision").asText()).isEqualTo("APPROVED");
    assertThat(historical.path("current_eligible").asBoolean()).isFalse();

    HttpResponse<String> stale =
        decide("reviewer-1", "policy-superseded", approval("policy-superseded", first, "reviewer-1", "Stale", REVIEWER_1_KEY, "key-reviewer-1"), "super-stale");
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(body(stale).path("code").asText()).isEqualTo("REVISION_CONFLICT");
  }

  @Test
  void rolledBackOrContradictedTrustConfigurationMakesApprovalsIneligibleOnReads() throws Exception {
    JsonNode draft = propose("policy-rollback", 0, 1, List.of("reviewer-1"), "2020-01-01T00:00:00Z");
    assertThat(
            decide("reviewer-1", "policy-rollback", approval("policy-rollback", draft, "reviewer-1", "Ok", REVIEWER_1_KEY, "key-reviewer-1"), "rollback-1")
                .statusCode())
        .isEqualTo(200);
    String table = "uacrf_decision_governance.policy_trust_revision";
    try {
      jdbc.update("INSERT INTO " + table + " (revision, trust_digest) VALUES (8, ?)", "a".repeat(64));
      assertNotEligible("policy-rollback");
    } finally {
      removeTrustRevision(table, 8);
    }
    assertThat(body(get("reviewer-1", ROOT + "/policy-rollback")).path("policy").path("current_eligible").asBoolean()).isTrue();
    String recorded = jdbc.queryForObject("SELECT trust_digest FROM " + table + " WHERE revision = 7", String.class);
    try {
      alterTrustTrigger(table, "DISABLE");
      jdbc.update("UPDATE " + table + " SET trust_digest = ? WHERE revision = 7", "b".repeat(64));
      assertNotEligible("policy-rollback");
    } finally {
      jdbc.update("UPDATE " + table + " SET trust_digest = ? WHERE revision = 7", recorded);
      alterTrustTrigger(table, "ENABLE");
    }
    assertThat(body(get("reviewer-1", ROOT + "/policy-rollback")).path("policy").path("current_eligible").asBoolean()).isTrue();
  }

  private void assertNotEligible(String id) throws Exception {
    JsonNode policy = body(get("reviewer-1", ROOT + "/" + id)).path("policy");
    assertThat(policy.path("decision").asText()).isEqualTo("APPROVED");
    assertThat(policy.path("current_eligible").asBoolean()).isFalse();
    assertThat(policy.path("review").path("valid_approval_count").asInt()).isZero();
    assertThat(policy.path("review").path("votes").get(0).path("currently_trusted").asBoolean()).isFalse();
  }

  private void removeTrustRevision(String table, long revision) {
    try {
      alterTrustTrigger(table, "DISABLE");
      jdbc.update("DELETE FROM " + table + " WHERE revision = ?", revision);
    } finally {
      alterTrustTrigger(table, "ENABLE");
    }
  }

  private void alterTrustTrigger(String table, String action) {
    jdbc.execute("ALTER TABLE " + table + " " + action + " TRIGGER policy_trust_revision_immutable");
  }

  @Test
  void badSignatureWrongKeyScopeOrClaimsAreDeniedAndRetainNoVote() throws Exception {
    JsonNode draft = propose("policy-bad-assertion", 0, 1, List.of("reviewer-1"), "2020-01-01T00:00:00Z");
    String id = "policy-bad-assertion";
    Map<String, Object> claims = claims(id, draft, "reviewer-1", "Reason");

    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(claims, UNTRUSTED_KEY, "key-reviewer-1", "RS256")), "bad-sig"), 422, "ASSERTION_SIGNATURE_INVALID");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(claims, REVIEWER_1_KEY, "key-removed", "RS256")), "unknown-key"), 422, "ASSERTION_KEY_UNTRUSTED");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(claims, REVIEWER_2_KEY, "key-reviewer-2", "RS256")), "wrong-scope"), 422, "ASSERTION_KEY_UNTRUSTED");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(claims, REVIEWER_1_KEY, "key-reviewer-1", "HS256")), "bad-alg"), 422, "ASSERTION_INVALID");

    Map<String, Object> wrongDigest = new LinkedHashMap<>(claims);
    wrongDigest.put("policy_digest", "0".repeat(64));
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(wrongDigest, REVIEWER_1_KEY, "key-reviewer-1", "RS256")), "bad-digest"), 422, "ASSERTION_BINDING_MISMATCH");
    Map<String, Object> wrongReviewer = new LinkedHashMap<>(claims);
    wrongReviewer.put("reviewer_id", "reviewer-2");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(wrongReviewer, REVIEWER_1_KEY, "key-reviewer-1", "RS256")), "bad-reviewer"), 422, "ASSERTION_BINDING_MISMATCH");
    Map<String, Object> wrongTenant = new LinkedHashMap<>(claims);
    wrongTenant.put("tenant_id", "tenant-other");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(wrongTenant, REVIEWER_1_KEY, "key-reviewer-1", "RS256")), "bad-tenant"), 422, "ASSERTION_BINDING_MISMATCH");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "A different reason", jws(claims, REVIEWER_1_KEY, "key-reviewer-1", "RS256")), "bad-reason"), 422, "ASSERTION_BINDING_MISMATCH");
    Map<String, Object> extra = new LinkedHashMap<>(claims);
    extra.put("permit", "yes");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", jws(extra, REVIEWER_1_KEY, "key-reviewer-1", "RS256")), "extra-claim"), 422, "ASSERTION_INVALID");

    String nonCanonical = rawJws("{\"tenant_id\": \"" + TENANT + "\"}", REVIEWER_1_KEY, "key-reviewer-1");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", nonCanonical), "non-canonical"), 422, "ASSERTION_INVALID");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Reason", "not-a-jws"), "not-jws"), 422, "ASSERTION_INVALID");

    assertThat(votes(id)).isZero();
    assertThat(body(get("reviewer-1", ROOT + "/" + id)).path("policy").path("decision").asText()).isEqualTo("DRAFT");
  }

  @Test
  void deniesProposerSelfReviewIneligibleReviewersAndInsufficientAuthority() throws Exception {
    JsonNode draft = propose("policy-denials", 0, 1, List.of(PROPOSER, "reviewer-1"), "2020-01-01T00:00:00Z");
    String id = "policy-denials";
    assertCode(decide(PROPOSER, id, approval(id, draft, PROPOSER, "Self", REVIEWER_1_KEY, "key-reviewer-1"), "self"), 403, "SELF_REVIEW_FORBIDDEN");
    assertCode(decide("reviewer-3", id, approval(id, draft, "reviewer-3", "Ineligible", REVIEWER_3_KEY, "key-reviewer-3"), "ineligible"), 403, "REVIEWER_NOT_ELIGIBLE");
    assertCode(decide("reviewer-3", id, decision(1, "REJECT", "Ineligible rejection", null), "ineligible-reject"), 403, "REVIEWER_NOT_ELIGIBLE");

    String assertion = approval(id, draft, "reviewer-1", "No capability", REVIEWER_1_KEY, "key-reviewer-1");
    String writeOnly = token("reviewer-1", SERVICE, List.of("authorization.policy.write", "authorization.policy.read"));
    assertCode(post(writeOnly, ROOT + "/" + id + ":decide", "no-capability", decision(1, "APPROVE", "No capability", assertion)), 403, "CAPABILITY_REQUIRED");
    String global = token("reviewer-1", "*", List.of("authorization.policy.review"));
    assertCode(post(global, ROOT + "/" + id + ":decide", "global", decision(1, "APPROVE", "No capability", assertion)), 403, "SERVICE_SCOPE_REQUIRED");
    String otherService = token("reviewer-1", "service-other", List.of("authorization.policy.review"));
    assertCode(post(otherService, ROOT + "/" + id + ":decide", "other-service", decision(1, "APPROVE", "No capability", assertion)), 404, "POLICY_NOT_FOUND");
    assertThat(votes(id)).isZero();
  }

  @Test
  void quorumRequiresDistinctEligibleReviewersAndDuplicateVotesAreDenied() throws Exception {
    String id = "policy-quorum";
    JsonNode draft = propose(id, 0, 2, List.of("reviewer-1", "reviewer-2", "reviewer-3"), "2020-01-01T00:00:00Z");
    JsonNode first =
        body(decide("reviewer-1", id, approval(id, draft, "reviewer-1", "First", REVIEWER_1_KEY, "key-reviewer-1"), "quorum-1")).path("policy");
    assertThat(first.path("decision").asText()).isEqualTo("DRAFT");
    assertThat(first.path("current_eligible").asBoolean()).isFalse();
    assertThat(first.path("signed_assertion").isNull()).isTrue();
    assertThat(first.path("review").path("approval_count").asInt()).isEqualTo(1);
    assertThat(first.path("review").path("required_approval_count").asInt()).isEqualTo(2);

    assertCode(decide("reviewer-1", id, approval(id, draft, "reviewer-1", "Again", REVIEWER_1_KEY, "key-reviewer-1"), "quorum-dup"), 409, "DUPLICATE_VOTE");
    assertCode(decide("reviewer-1", id, decision(1, "REJECT", "Change of mind", null), "quorum-dup-reject"), 409, "DUPLICATE_VOTE");
    assertThat(votes(id)).isEqualTo(1);

    JsonNode second =
        body(decide("reviewer-2", id, approval(id, draft, "reviewer-2", "Second", REVIEWER_2_KEY, "key-reviewer-2"), "quorum-2")).path("policy");
    assertThat(second.path("decision").asText()).isEqualTo("APPROVED");
    assertThat(second.path("current_eligible").asBoolean()).isTrue();
    assertThat(second.path("reviewer_id").asText()).isEqualTo("reviewer-2");
    assertThat(second.path("review").path("votes")).hasSize(2);
    assertThat(second.path("review").path("valid_approval_count").asInt()).isEqualTo(2);

    assertCode(decide("reviewer-3", id, approval(id, draft, "reviewer-3", "Late", REVIEWER_3_KEY, "key-reviewer-3"), "quorum-late"), 409, "DECISION_FINAL");
    assertThat(votes(id)).isEqualTo(2);
  }

  @Test
  void rejectionIsUnsignedTerminalAndRequiresAnEligibleIndependentReviewer() throws Exception {
    String id = "policy-reject";
    JsonNode draft = propose(id, 0, 2, List.of("reviewer-1", "reviewer-2"), "2020-01-01T00:00:00Z");
    String signed = approval(id, draft, "reviewer-1", "Signed rejection", REVIEWER_1_KEY, "key-reviewer-1");
    assertCode(decide("reviewer-1", id, decision(1, "REJECT", "Signed rejection", signed), "reject-signed"), 400, "INVALID_REQUEST");
    assertCode(decide("reviewer-1", id, decision(1, "APPROVE", "Missing assertion", null), "approve-unsigned"), 400, "INVALID_REQUEST");

    JsonNode rejected = body(decide("reviewer-1", id, decision(1, "REJECT", "Unsafe", null), "reject-1")).path("policy");
    assertThat(rejected.path("decision").asText()).isEqualTo("REJECTED");
    assertThat(rejected.path("current_eligible").asBoolean()).isFalse();
    assertThat(rejected.path("signed_assertion").isNull()).isTrue();
    assertThat(rejected.path("review").path("rejection_count").asInt()).isEqualTo(1);
    assertCode(decide("reviewer-2", id, approval(id, draft, "reviewer-2", "After", REVIEWER_2_KEY, "key-reviewer-2"), "after-reject"), 409, "DECISION_FINAL");
  }

  @Test
  void idempotentRetryReplaysOneVoteAndChangedContentConflicts() throws Exception {
    String id = "policy-idempotent";
    JsonNode draft = propose(id, 0, 1, List.of("reviewer-1"), "2020-01-01T00:00:00Z");
    String request = approval(id, draft, "reviewer-1", "Retry", REVIEWER_1_KEY, "key-reviewer-1");
    String path = ROOT + "/" + id + ":decide";
    String token = token("reviewer-1", SERVICE, List.of("authorization.policy.review"));
    HttpResponse<String> first = post(token, path, "retry-key", request);
    HttpResponse<String> retry = post(token, path, "retry-key", request);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(retry.statusCode()).isEqualTo(200);
    assertThat(body(retry).path("policy").path("reviewed_at").asText())
        .isEqualTo(body(first).path("policy").path("reviewed_at").asText());
    assertThat(votes(id)).isEqualTo(1);
    String changed = decision(1, "REJECT", "Retry", null);
    assertCode(post(token, path, "retry-key", changed), 409, "IDEMPOTENCY_CONFLICT");
    assertCode(post(token, path, "different-key", request), 409, "DUPLICATE_VOTE");
  }

  private JsonNode propose(String id, long expectedRevision, int quorum, List<String> eligible, String effectiveFrom)
      throws Exception {
    String eligibleJson = objectMapper.writeValueAsString(eligible);
    String body =
        """
        {"id":"%s","service_id":"%s","expected_revision":%d,"expires_at":"2099-12-31T00:00:00Z",
         "definition":{"allowed_action_kinds":["READ"],"allowed_actor_ids":["actor-1"],
         "allowed_resource_ids":["resource-1"],"permitted_providers":["AZURE"],
         "permitted_regions":["eastus"],"required_approver_count":%d,
         "eligible_approver_ids":%s,"maximum_cost_usd":1},
         "reason":"Proposal","effective_from":"%s"}
        """.formatted(id, SERVICE, expectedRevision, quorum, eligibleJson, effectiveFrom);
    HttpResponse<String> response =
        post(token(PROPOSER, SERVICE, List.of("authorization.policy.write", "authorization.policy.read")), ROOT, id + "-propose-" + expectedRevision, body);
    assertThat(response.statusCode()).isEqualTo(200);
    return body(response).path("policy");
  }

  private HttpResponse<String> decide(String reviewer, String id, String body, String key) throws Exception {
    return post(token(reviewer, SERVICE, List.of("authorization.policy.review", "authorization.policy.read")), ROOT + "/" + id + ":decide", key, body);
  }

  private String decision(long revision, String decision, String reason, String assertion) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("expected_revision", revision);
    request.put("decision", decision);
    request.put("reason", reason);
    request.put("signed_assertion", assertion);
    return objectMapper.writeValueAsString(request);
  }

  private String approval(String id, JsonNode policy, String reviewer, String reason, KeyPair key, String kid) {
    return decision(policy.path("revision").asLong(), "APPROVE", reason, jws(claims(id, policy, reviewer, reason), key, kid, "RS256"));
  }

  private Map<String, Object> claims(String id, JsonNode policy, String reviewer, String reason) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("tenant_id", TENANT);
    claims.put("environment_id", ENVIRONMENT);
    claims.put("policy_id", id);
    claims.put("policy_revision", policy.path("revision").asLong());
    claims.put("service_id", SERVICE);
    claims.put("policy_digest", policy.path("digest").asText());
    claims.put("reviewer_id", reviewer);
    claims.put("decision", "APPROVE");
    claims.put("reason", reason);
    return claims;
  }

  private String jws(Map<String, Object> claims, KeyPair key, String kid, String alg) {
    return sign(OwnerReceiptSignature.canonicalize(objectMapper.writeValueAsString(claims)), key, kid, alg);
  }

  private String rawJws(String payload, KeyPair key, String kid) {
    return sign(payload, key, kid, "RS256");
  }

  private String sign(String payload, KeyPair key, String kid, String alg) {
    try {
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      String header =
          encoder.encodeToString(
              ("{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\",\"typ\":\"" + PolicyAssertionVerifier.TYPE + "\"}")
                  .getBytes(StandardCharsets.UTF_8));
      String signingInput = header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign((RSAPrivateKey) key.getPrivate());
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + encoder.encodeToString(signature.sign());
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException(error);
    }
  }

  private void assertCode(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(body(response).path("code").asText()).isEqualTo(code);
  }

  private int votes(String id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_decision_governance.policy_review_vote WHERE id = ?",
        Integer.class,
        id);
  }

  private static List<String> policyFields(JsonNode policy) {
    return policy.propertyNames().stream().toList();
  }

  private HttpResponse<String> post(String token, String path, String key, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String reviewer, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token(reviewer, SERVICE, List.of("authorization.policy.read")))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> response) {
    return objectMapper.readTree(response.body());
  }

  private static String token(String principalId, String serviceScope, List<String> capabilities) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            principalId, TENANT, ENVIRONMENT, serviceScope, "https://issuer.example",
            "subject-" + principalId, capabilities, List.of(), List.of());
    return new DelegationTokenIssuer(
            DelegationJwtEncoderFactory.create(DELEGATION_KEY, DELEGATION_KEY_ID),
            Clock.systemUTC(), "https://uacrf.local/s01", DELEGATION_KEY_ID)
        .issue(principal, "S10");
  }

  private static String trustSetJson() {
    return "{\"revision\":7,\"keys\":["
        + trustedKey("key-reviewer-1", REVIEWER_1_KEY, "reviewer-1") + ","
        + trustedKey("key-reviewer-2", REVIEWER_2_KEY, "reviewer-2") + ","
        + trustedKey("key-reviewer-3", REVIEWER_3_KEY, "reviewer-3") + "]}";
  }

  private static String trustedKey(String kid, KeyPair key, String reviewer) {
    return "{\"kid\":\"" + kid + "\",\"algorithm\":\"RS256\",\"public_key_base64\":\""
        + Base64.getEncoder().encodeToString(key.getPublic().getEncoded())
        + "\",\"tenant_id\":\"" + TENANT + "\",\"environment_id\":\"" + ENVIRONMENT
        + "\",\"service_id\":\"" + SERVICE + "\",\"reviewer_id\":\"" + reviewer + "\"}";
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
