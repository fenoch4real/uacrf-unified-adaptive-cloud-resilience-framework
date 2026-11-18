package org.uacrf.evidence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class QualificationWaiverAssertionVerifier {
  static final String TYPE = "uacrf.qualification-waiver+jws";
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
  private static final String BASE64URL = "^[A-Za-z0-9_-]+$";
  private static final Duration MAX_LIFETIME = Duration.ofHours(24);

  private final QualificationWaiverTrustSet trust;
  private final EvidenceContractSchemas contracts;
  private final JsonMapper strict =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  QualificationWaiverAssertionVerifier(
      QualificationWaiverTrustSet trust, EvidenceContractSchemas contracts) {
    this.trust = trust;
    this.contracts = contracts;
  }

  record Verified(
      QualificationWaiverTrustSet.TrustedKey key, String assertionDigest, String assertionJti) {}

  Verified verify(
      String assertion,
      DelegationPrincipal actor,
      String waiverId,
      String serviceId,
      String waiverDigest) {
    if (!trust.configured()) {
      throw new EvidenceException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRUST_SET_UNAVAILABLE",
          "Qualification-waiver approvals are disabled because no trust set is configured.");
    }
    String[] parts = assertion.split("\\.", -1);
    if (parts.length != 3
        || !parts[0].matches(BASE64URL)
        || !parts[1].matches(BASE64URL)
        || !parts[2].matches(BASE64URL)) {
      throw invalid("signed_assertion must be a compact JWS.");
    }
    JsonNode header = parse(parts[0], "header");
    if (!Set.copyOf(header.propertyNames()).equals(HEADER_FIELDS)
        || !"RS256".equals(text(header, "alg"))
        || !TYPE.equals(text(header, "typ"))) {
      throw invalid("The JWS header must contain only alg=RS256, kid and the expected typ.");
    }
    String payloadText = decode(parts[1], "payload");
    if (!payloadText.equals(canonical(payloadText))) {
      throw invalid("The JWS payload must be RFC 8785 canonical JSON.");
    }
    JsonNode claims = parse(parts[1], "payload");
    if (!contracts.validWaiverAttestation(claims)) {
      throw invalid("The JWS payload does not match the qualification-waiver attestation schema.");
    }
    String keyId = text(header, "kid");
    QualificationWaiverTrustSet.TrustedKey key = trust.key(keyId);
    if (key == null
        || !key.inScope(
            actor.tenantId(), actor.environmentId(), serviceId, actor.principalId())) {
      throw untrustedKey();
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

    long issuedAt = integer(claims, "iat");
    long notBefore = integer(claims, "nbf");
    long expiresAt = integer(claims, "exp");
    Instant now = Instant.now();
    if (issuedAt > now.getEpochSecond()
        || notBefore > now.getEpochSecond()
        || expiresAt <= now.getEpochSecond()
        || expiresAt <= issuedAt
        || notBefore > expiresAt
        || lifetimeExceedsLimit(issuedAt, expiresAt)) {
      throw invalid("The waiver assertion is expired, not yet valid, or exceeds its 24-hour lifetime.");
    }
    if (!trust.issuer().equals(text(claims, "iss"))
        || !trust.audience().equals(text(claims, "aud"))
        || !actor.principalId().equals(text(claims, "sub"))
        || !actor.tenantId().equals(text(claims, "tenant_id"))
        || !actor.environmentId().equals(text(claims, "environment_id"))
        || !"qualification-waiver".equals(text(claims, "purpose"))
        || !waiverId.equals(text(claims, "waiver_id"))
        || !waiverDigest.equals(text(claims, "waiver_digest"))
        || !actor.principalId().equals(text(claims, "approved_by"))) {
      throw new EvidenceException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "ASSERTION_BINDING_MISMATCH",
          "The signed claims do not match the current reviewer, scope and waiver digest.");
    }
    String assertionDigest =
        HexFormat.of()
            .formatHex(
                sha256(assertion.getBytes(StandardCharsets.US_ASCII)));
    return new Verified(key, assertionDigest, text(claims, "jti"));
  }

  private JsonNode parse(String encoded, String name) {
    try {
      JsonNode node = strict.readTree(decode(encoded, name));
      if (node == null || !node.isObject()) {
        throw invalid("The JWS " + name + " must be a JSON object.");
      }
      return node;
    } catch (EvidenceException error) {
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

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : "";
  }

  private static long integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(field + " must be an integer.");
    }
    return value.longValue();
  }

  private static boolean lifetimeExceedsLimit(long issuedAt, long expiresAt) {
    try {
      return Math.subtractExact(expiresAt, issuedAt) > MAX_LIFETIME.getSeconds();
    } catch (ArithmeticException error) {
      return true;
    }
  }

  private static byte[] sha256(byte[] input) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(input);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime.", error);
    }
  }

  private static EvidenceException invalid(String message) {
    return new EvidenceException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSERTION_INVALID", message);
  }

  private static EvidenceException untrustedKey() {
    return new EvidenceException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "ASSERTION_KEY_UNTRUSTED",
        "The assertion key is not trusted for this tenant, environment, service and reviewer.");
  }

  private static EvidenceException signatureInvalid() {
    return new EvidenceException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "ASSERTION_SIGNATURE_INVALID",
        "The assertion signature does not verify under the trusted key.");
  }
}
