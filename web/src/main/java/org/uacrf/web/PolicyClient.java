package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class PolicyClient {
  private static final String ROOT = "/v1/authorization-policy-bundles";
  private static final String AUTHORITY = "SIGNED_POLICY_CONFIGURATION_NOT_ACTION_AUTHORIZATION";
  private static final ObjectMapper MATERIAL_MAPPER = new ObjectMapper();
  private final RestClient restClient;

  PolicyClient(@Qualifier("policyRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  PolicyPage list(String token, String serviceId, String cursor) {
    PolicyPage page = restClient.get().uri(uri -> {
      var request = uri.path(ROOT).queryParam("service_id", serviceId).queryParam("page_size", 25);
      if (cursor != null) request.queryParam("cursor", cursor);
      return request.build();
    }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(PolicyPage.class);
    if (page == null || page.items() == null || page.items().size() > 25
        || !"AUTHORIZED_VISIBLE_SCOPE_ONLY".equals(page.coverage())
        || (page.nextCursor() != null && (!validCursor(page.nextCursor())
            || page.nextCursor().equals(cursor) || page.items().isEmpty()))
        || page.items().stream().anyMatch(item -> !valid(item, serviceId))
        || new HashSet<>(page.items().stream().map(PolicySummary::id).toList()).size() != page.items().size()) {
      throw new IllegalStateException("S10 returned an invalid policy page.");
    }
    for (int i = 1; i < page.items().size(); i++) {
      if (page.items().get(i - 1).proposedAt().isBefore(page.items().get(i).proposedAt())) {
        throw new IllegalStateException("S10 returned policies out of order.");
      }
    }
    return page;
  }

  Policy read(String token, String serviceId, String id, long revision) {
    PolicyResult result = restClient.get().uri(uri -> uri.path(ROOT + "/{id}")
        .queryParam("revision", revision).build(id))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(PolicyResult.class);
    return validate(result, serviceId, id, revision);
  }

  Policy propose(String token, String idempotencyKey, Proposal request) {
    PolicyResult result = restClient.post().uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", idempotencyKey)
        .body(request).retrieve().body(PolicyResult.class);
    Policy policy = validate(result, request.serviceId(), request.id(), request.expectedRevision() + 1);
    try {
      JsonNode material = MATERIAL_MAPPER.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .readTree(policy.material().canonicalText());
      JsonNode definition = material.path("definition");
      JsonNode expected = MATERIAL_MAPPER.valueToTree(request.definition());
      if (!policy.expiresAt().equals(request.expiresAt())
          || !policy.effectiveFrom().equals(request.effectiveFrom())
          || !policy.proposalReason().equals(request.reason())
          || !definition.isObject()
          || !definition.path("allowed_action_kinds").equals(expected.path("allowed_action_kinds"))
          || !definition.path("allowed_actor_ids").equals(expected.path("allowed_actor_ids"))
          || !definition.path("allowed_resource_ids").equals(expected.path("allowed_resource_ids"))
          || !definition.path("permitted_providers").equals(expected.path("permitted_providers"))
          || !definition.path("permitted_regions").equals(expected.path("permitted_regions"))
          || !definition.path("required_approver_count").equals(expected.path("required_approver_count"))
          || !definition.path("eligible_approver_ids").equals(expected.path("eligible_approver_ids"))
          || !definition.path("maximum_cost_usd").isNumber()
          || definition.path("maximum_cost_usd").decimalValue().compareTo(request.definition().maximumCostUsd()) != 0) {
        throw new IllegalStateException("S10 returned a proposal that does not match the request.");
      }
    } catch (JacksonException error) {
      throw new IllegalStateException("S10 returned invalid proposal material.", error);
    }
    return policy;
  }

  Policy reject(String token, String idempotencyKey, String serviceId, String id,
      long revision, String digest, String reason) {
    PolicyResult result = restClient.post().uri(ROOT + "/{id}:decide", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", idempotencyKey)
        .body(new Decision(revision, "REJECT", reason, null))
        .retrieve().body(PolicyResult.class);
    Policy policy = validate(result, serviceId, id, revision);
    if (!digest.equals(policy.digest()) || !"REJECTED".equals(policy.decision())
        || policy.currentEligible() || policy.review().rejectionCount() != 1
        || policy.review().votes().stream().noneMatch(v -> "REJECT".equals(v.decision())
            && reason.equals(v.reason()))) {
      throw new IllegalStateException("S10 returned a rejection that does not match the request.");
    }
    return policy;
  }

  Policy approve(String token, String idempotencyKey, String serviceId, String id,
      long revision, String digest, String reason, String assertion) {
    PolicyResult result = restClient.post().uri(ROOT + "/{id}:decide", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", idempotencyKey)
        .body(new Decision(revision, "APPROVE", reason, assertion))
        .retrieve().body(PolicyResult.class);
    Policy policy = validate(result, serviceId, id, revision);
    String assertionDigest = sha256(assertion);
    if (!digest.equals(policy.digest()) || policy.review().votes().stream().noneMatch(v ->
        "APPROVE".equals(v.decision()) && reason.equals(v.reason())
            && assertionDigest.equals(v.assertionDigest()))) {
      throw new IllegalStateException("S10 returned an approval that does not match the request.");
    }
    return policy;
  }

  private static Policy validate(PolicyResult result, String serviceId, String id, long revision) {
    Policy policy = result == null ? null : result.policy();
    if (policy == null || !valid(policy.summary(), serviceId) || !id.equals(policy.id())
        || policy.revision() == null || revision != policy.revision()
        || policy.material() == null || !"SHA-256".equals(policy.material().algorithm())
        || !"UTF-8".equals(policy.material().encoding()) || policy.material().canonicalText() == null
        || !policy.digest().equals(policy.material().digest())
        || !policy.digest().equals(sha256(policy.material().canonicalText()))
        || !materialMatches(policy)
        || policy.review() == null || policy.review().requiredApprovalCount() == null
        || policy.review().approvalCount() == null || policy.review().validApprovalCount() == null
        || policy.review().rejectionCount() == null || policy.review().votes() == null
        || policy.review().requiredApprovalCount() < 0 || policy.review().approvalCount() < 0
        || policy.review().validApprovalCount() < 0 || policy.review().rejectionCount() < 0
        || policy.review().validApprovalCount() > policy.review().approvalCount()
        || policy.review().votes().stream().anyMatch(v -> v == null || !identifier(v.reviewerId())
            || v.decision() == null || !List.of("APPROVE", "REJECT").contains(v.decision()) || v.reviewedAt() == null
            || v.currentlyTrusted() == null || v.reason() == null)) {
      throw new IllegalStateException("S10 returned invalid policy details.");
    }
    return policy;
  }

  private static boolean valid(PolicySummary item, String serviceId) {
    return item != null && identifier(item.id()) && serviceId.equals(item.serviceId())
        && item.revision() != null && item.revision() > 0 && item.expiresAt() != null
        && item.effectiveFrom() != null && item.proposedAt() != null
        && identifier(item.proposedBy()) && item.proposalReason() != null
        && item.digest() != null && item.digest().matches("[0-9a-f]{64}")
        && item.decision() != null && List.of("DRAFT", "APPROVED", "REJECTED").contains(item.decision())
        && item.currentEligible() != null
        && (!item.currentEligible() || "APPROVED".equals(item.decision()))
        && AUTHORITY.equals(item.authority());
  }

  static boolean identifier(String value) {
    return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}");
  }

  static boolean validCursor(String cursor) {
    return cursor != null && cursor.matches("[A-Za-z0-9_-]{1,500}");
  }

  private static boolean materialMatches(Policy policy) {
    try {
      JsonNode material = MATERIAL_MAPPER.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .readTree(policy.material().canonicalText());
      return material != null && material.isObject() && material.path("definition").isObject()
          && material.path("id").isTextual() && policy.id().equals(material.path("id").textValue())
          && material.path("service_id").isTextual()
          && policy.serviceId().equals(material.path("service_id").textValue())
          && material.path("effective_from").isTextual()
          && policy.effectiveFrom().equals(Instant.parse(material.path("effective_from").textValue()))
          && material.path("expires_at").isTextual()
          && policy.expiresAt().equals(Instant.parse(material.path("expires_at").textValue()));
    } catch (JacksonException | DateTimeParseException error) {
      return false;
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }

  record PolicyPage(List<PolicySummary> items, @JsonProperty("next_cursor") String nextCursor,
      String coverage) {}
  record PolicySummary(String id, Long revision, @JsonProperty("service_id") String serviceId,
      @JsonProperty("expires_at") Instant expiresAt, String digest,
      @JsonProperty("proposed_by") String proposedBy, @JsonProperty("proposal_reason") String proposalReason,
      @JsonProperty("proposed_at") Instant proposedAt, String decision,
      @JsonProperty("current_eligible") Boolean currentEligible, String authority,
      @JsonProperty("effective_from") Instant effectiveFrom) {}
  record Material(String algorithm, String encoding, @JsonProperty("canonical_text") String canonicalText,
      String digest) {}
  record Vote(@JsonProperty("reviewer_id") String reviewerId, String decision, String reason,
      @JsonProperty("reviewed_at") Instant reviewedAt, @JsonProperty("currently_trusted") Boolean currentlyTrusted,
      @JsonProperty("assertion_digest") String assertionDigest) {}
  record Review(@JsonProperty("required_approval_count") Integer requiredApprovalCount,
      @JsonProperty("approval_count") Integer approvalCount,
      @JsonProperty("valid_approval_count") Integer validApprovalCount,
      @JsonProperty("rejection_count") Integer rejectionCount, List<Vote> votes) {}
  record PolicyResult(Policy policy) {}
  record Definition(@JsonProperty("allowed_action_kinds") List<String> allowedActionKinds,
      @JsonProperty("allowed_actor_ids") List<String> allowedActorIds,
      @JsonProperty("allowed_resource_ids") List<String> allowedResourceIds,
      @JsonProperty("permitted_providers") List<String> permittedProviders,
      @JsonProperty("permitted_regions") List<String> permittedRegions,
      @JsonProperty("required_approver_count") Integer requiredApproverCount,
      @JsonProperty("eligible_approver_ids") List<String> eligibleApproverIds,
      @JsonProperty("maximum_cost_usd") BigDecimal maximumCostUsd) {}
  record Proposal(String id, @JsonProperty("service_id") String serviceId,
      @JsonProperty("expected_revision") long expectedRevision,
      @JsonProperty("expires_at") Instant expiresAt, Definition definition, String reason,
      @JsonProperty("effective_from") Instant effectiveFrom) {}
  record Decision(@JsonProperty("expected_revision") long expectedRevision, String decision,
      String reason, @JsonProperty("signed_assertion") String signedAssertion) {}
  record Policy(String id, Long revision, @JsonProperty("service_id") String serviceId,
      @JsonProperty("expires_at") Instant expiresAt, String digest,
      @JsonProperty("proposed_by") String proposedBy, @JsonProperty("proposal_reason") String proposalReason,
      @JsonProperty("proposed_at") Instant proposedAt, String decision,
      @JsonProperty("current_eligible") Boolean currentEligible, String authority,
      @JsonProperty("effective_from") Instant effectiveFrom, Material material, Review review) {
    PolicySummary summary() {
      return new PolicySummary(id, revision, serviceId, expiresAt, digest, proposedBy, proposalReason,
          proposedAt, decision, currentEligible, authority, effectiveFrom);
    }
  }
}
