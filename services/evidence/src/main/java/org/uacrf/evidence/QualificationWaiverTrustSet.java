package org.uacrf.evidence;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class QualificationWaiverTrustSet {
  private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final Set<String> ROOT_FIELDS = Set.of("revision", "issuer", "audience", "keys");
  private static final Set<String> KEY_FIELDS =
      Set.of(
          "kid",
          "algorithm",
          "public_key_base64",
          "tenant_id",
          "environment_id",
          "service_id",
          "reviewer_id");

  private final long revision;
  private final String issuer;
  private final String audience;
  private final String digest;
  private final Map<String, TrustedKey> keys;
  private final boolean configured;

  QualificationWaiverTrustSet(
      @Value("${uacrf.qualification-waiver.trust-set-json:}") String json) {
    if (json == null || json.isBlank()) {
      configured = false;
      revision = 0;
      issuer = null;
      audience = null;
      digest = null;
      keys = Map.of();
      return;
    }
    JsonMapper mapper =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    try {
      JsonNode root = mapper.readTree(json);
      requireFields(root, ROOT_FIELDS);
      long parsedRevision = integer(root, "revision");
      String parsedIssuer = text(root, "issuer");
      String parsedAudience = text(root, "audience");
      if (parsedRevision < 1 || parsedIssuer.length() > 200 || parsedAudience.length() > 200) {
        throw new IllegalArgumentException("revision, issuer or audience is out of bounds.");
      }
      JsonNode nodes = root.get("keys");
      if (!nodes.isArray() || nodes.size() > 1000) {
        throw new IllegalArgumentException("keys must be an array of at most 1000 entries.");
      }
      Map<String, TrustedKey> parsed = new TreeMap<>();
      Set<String> fingerprints = new HashSet<>();
      for (JsonNode node : nodes) {
        TrustedKey key = parseKey(node);
        if (parsed.put(key.keyId(), key) != null) {
          throw new IllegalArgumentException("Duplicate kid " + key.keyId() + ".");
        }
        if (!fingerprints.add(key.keyDigest())) {
          throw new IllegalArgumentException("A public key may appear only once.");
        }
      }
      revision = parsedRevision;
      issuer = parsedIssuer;
      audience = parsedAudience;
      keys = Map.copyOf(parsed);
      digest = computeDigest(mapper, revision, issuer, audience, parsed);
      configured = true;
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "uacrf.qualification-waiver.trust-set-json is invalid: " + error.getMessage(), error);
    } catch (RuntimeException error) {
      throw new IllegalStateException(
          "uacrf.qualification-waiver.trust-set-json is not valid strict JSON.", error);
    }
  }

  boolean configured() {
    return configured;
  }

  long revision() {
    return revision;
  }

  String issuer() {
    return issuer;
  }

  String audience() {
    return audience;
  }

  String digest() {
    return digest;
  }

  TrustedKey key(String keyId) {
    return keys.get(keyId);
  }

  boolean stillTrusts(
      String keyId,
      String keyDigest,
      String tenantId,
      String environmentId,
      String serviceId,
      String reviewerId) {
    TrustedKey key = keys.get(keyId);
    return key != null
        && key.keyDigest().equals(keyDigest)
        && key.inScope(tenantId, environmentId, serviceId, reviewerId);
  }

  private static TrustedKey parseKey(JsonNode node) {
    requireFields(node, KEY_FIELDS);
    for (String field :
        List.of("kid", "tenant_id", "environment_id", "service_id", "reviewer_id")) {
      if (!text(node, field).matches(ID_PATTERN)) {
        throw new IllegalArgumentException(field + " is malformed.");
      }
    }
    String keyId = text(node, "kid");
    if (!"RS256".equals(text(node, "algorithm"))) {
      throw new IllegalArgumentException("algorithm must be RS256.");
    }
    byte[] encoded;
    try {
      encoded = Base64.getDecoder().decode(text(node, "public_key_base64"));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("public_key_base64 for " + keyId + " is not base64.");
    }
    PublicKey publicKey;
    try {
      publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalArgumentException(
          "public_key_base64 for " + keyId + " is not an RSA public key.");
    }
    RSAPublicKey rsa = (RSAPublicKey) publicKey;
    int bits = rsa.getModulus().bitLength();
    if (bits < 2048 || bits > 8192) {
      throw new IllegalArgumentException("RSA key " + keyId + " must be 2048 to 8192 bits.");
    }
    return new TrustedKey(
        keyId,
        rsa,
        sha256Hex(encoded),
        Base64.getEncoder().encodeToString(encoded),
        text(node, "tenant_id"),
        text(node, "environment_id"),
        text(node, "service_id"),
        text(node, "reviewer_id"));
  }

  private static String computeDigest(
      JsonMapper mapper, long revision, String issuer, String audience, Map<String, TrustedKey> keys) {
    List<Map<String, String>> normalizedKeys = new ArrayList<>();
    for (TrustedKey key : keys.values()) {
      Map<String, String> entry = new TreeMap<>();
      entry.put("kid", key.keyId());
      entry.put("algorithm", "RS256");
      entry.put("public_key_base64", key.publicKeyBase64());
      entry.put("tenant_id", key.tenantId());
      entry.put("environment_id", key.environmentId());
      entry.put("service_id", key.serviceId());
      entry.put("reviewer_id", key.reviewerId());
      normalizedKeys.add(entry);
    }
    Map<String, Object> material = new TreeMap<>();
    material.put("revision", revision);
    material.put("issuer", issuer);
    material.put("audience", audience);
    material.put("keys", normalizedKeys);
    String canonical = OwnerReceiptSignature.canonicalize(mapper.writeValueAsString(material));
    return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireFields(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields)) {
      throw new IllegalArgumentException("The trust-set object must contain exactly " + fields + ".");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be a non-empty string.");
    }
    return value.textValue();
  }

  private static long integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer.");
    }
    return value.longValue();
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime.", error);
    }
  }

  record TrustedKey(
      String keyId,
      RSAPublicKey publicKey,
      String keyDigest,
      String publicKeyBase64,
      String tenantId,
      String environmentId,
      String serviceId,
      String reviewerId) {
    boolean inScope(String tenant, String environment, String service, String reviewer) {
      return tenantId.equals(tenant)
          && environmentId.equals(environment)
          && serviceId.equals(service)
          && reviewerId.equals(reviewer);
    }
  }
}
