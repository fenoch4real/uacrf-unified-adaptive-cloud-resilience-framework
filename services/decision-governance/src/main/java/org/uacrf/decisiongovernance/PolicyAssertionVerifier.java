package org.uacrf.decisiongovernance;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the compact RS256 JWS that binds one human reviewer's approval to one revision. */
@Component
class PolicyAssertionVerifier {
  static final String TYPE = "uacrf.policy-review+jws";
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
  private static final Set<String> CLAIM_FIELDS =
      Set.of(
          "tenant_id",
          "environment_id",
          "policy_id",
          "policy_revision",
          "service_id",
          "policy_digest",
          "reviewer_id",
          "decision",
          "reason");
  private static final String BASE64URL = "^[A-Za-z0-9_-]+$";

  private final PolicyTrustSet trust;
  private final JsonMapper strict =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  PolicyAssertionVerifier(PolicyTrustSet trust) {
    this.trust = trust;
  }

  record Verified(PolicyTrustSet.TrustedKey key, String assertionDigest) {}

  Verified verify(
      String assertion,
      String tenantId,
      String environmentId,
      String policyId,
      long revision,
      String serviceId,
      String policyDigest,
      String reviewerId,
      String reason) {
    String[] parts = assertion.split("\\.", -1);
    if (parts.length != 3
        || !parts[0].matches(BASE64URL)
        || !parts[1].matches(BASE64URL)
        || !parts[2].matches(BASE64URL)) {
      throw invalid("signed_assertion must be a compact JWS.");
    }
    JsonNode header = parse(parts[0], "header");
    requireExact(header, HEADER_FIELDS, "header");
    if (!"RS256".equals(text(header, "alg")) || !TYPE.equals(text(header, "typ"))) {
      throw invalid("The JWS header must use alg RS256 and typ " + TYPE + ".");
    }
    String payloadText = decode(parts[1], "payload");
    if (!payloadText.equals(canonical(payloadText))) {
      throw invalid("The JWS payload must be RFC 8785 canonical JSON.");
    }
    JsonNode claims = parse(parts[1], "payload");
    requireExact(claims, CLAIM_FIELDS, "payload");

    String keyId = text(header, "kid");
    PolicyTrustSet.TrustedKey key = trust.key(keyId);
    if (key == null) {
      throw new DecisionGovernanceException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "ASSERTION_KEY_UNTRUSTED",
          "The assertion key is unknown, removed, or not in the current trust set.");
    }
    if (!key.inScope(tenantId, environmentId, serviceId, reviewerId)) {
      throw new DecisionGovernanceException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "ASSERTION_KEY_UNTRUSTED",
          "The assertion key is not trusted for this tenant, environment, service and reviewer.");
    }
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key.publicKey());
      verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
        throw signatureInvalid();
      }
    } catch (GeneralSecurityException | IllegalArgumentException error) {
      throw signatureInvalid();
    }

    JsonNode revisionNode = claims.get("policy_revision");
    boolean bound =
        tenantId.equals(text(claims, "tenant_id"))
            && environmentId.equals(text(claims, "environment_id"))
            && policyId.equals(text(claims, "policy_id"))
            && revisionNode.isIntegralNumber()
            && revisionNode.canConvertToLong()
            && revisionNode.longValue() == revision
            && serviceId.equals(text(claims, "service_id"))
            && policyDigest.equals(text(claims, "policy_digest"))
            && reviewerId.equals(text(claims, "reviewer_id"))
            && "APPROVE".equals(text(claims, "decision"))
            && reason.equals(text(claims, "reason"));
    if (!bound) {
      throw new DecisionGovernanceException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "ASSERTION_BINDING_MISMATCH",
          "The signed claims do not match this tenant, environment, policy revision, reviewer, decision and reason.");
    }
    return new Verified(
        key, PolicyTrustSet.sha256Hex(assertion.getBytes(StandardCharsets.US_ASCII)));
  }

  private JsonNode parse(String encoded, String name) {
    try {
      JsonNode node = strict.readTree(decode(encoded, name));
      if (node == null || !node.isObject()) {
        throw invalid("The JWS " + name + " must be a JSON object.");
      }
      return node;
    } catch (DecisionGovernanceException error) {
      throw error;
    } catch (RuntimeException error) {
      throw invalid("The JWS " + name + " is not strict JSON.");
    }
  }

  private static String decode(String encoded, String name) {
    try {
      return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      throw invalid("The JWS " + name + " is not base64url.");
    }
  }

  private static String canonical(String json) {
    try {
      return OwnerReceiptSignature.canonicalize(json);
    } catch (RuntimeException error) {
      throw invalid("The JWS payload is not canonicalizable JSON.");
    }
  }

  private static void requireExact(JsonNode node, Set<String> fields, String name) {
    Set<String> present = Set.copyOf(node.propertyNames());
    if (!present.equals(fields)) {
      throw invalid("The JWS " + name + " must contain exactly " + fields + ".");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : "";
  }

  private static DecisionGovernanceException invalid(String message) {
    return new DecisionGovernanceException(
        HttpStatus.UNPROCESSABLE_ENTITY, "ASSERTION_INVALID", message);
  }

  private static DecisionGovernanceException signatureInvalid() {
    return new DecisionGovernanceException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "ASSERTION_SIGNATURE_INVALID",
        "The assertion signature does not verify under the trusted key.");
  }
}
