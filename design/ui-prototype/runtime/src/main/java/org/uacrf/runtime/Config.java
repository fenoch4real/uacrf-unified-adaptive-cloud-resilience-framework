package org.uacrf.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

record Config(
    String tenant,
    String environment,
    String issuer,
    String audience,
    Map<String, RSAPublicKey> keys,
    String providerId,
    String providerName,
    String directoryUrl,
    String directoryIssuer,
    String directoryAudience,
    Map<String, RSAPublicKey> directoryKeys,
    String jdbcUrl,
    String user,
    String password,
    byte[] cursorKey,
    Path releaseRoot) {
  static String required(Map<String, String> env, String name) {
    String v = env.get(name);
    if (v == null || v.isBlank())
      throw new IllegalArgumentException("Missing server configuration: " + name);
    return v;
  }

  static Config load() throws Exception {
    var env = System.getenv();
    Path path = Path.of(required(env, "UACRF_CONFIG")).toAbsolutePath();
    JsonNode n = Json.read(Files.readString(path));
    byte[] cursor = Base64.getDecoder().decode(required(env, "UACRF_CURSOR_KEY"));
    if (cursor.length < 32
        || !n.path("issuer").asText().startsWith("https://")
        || !n.path("directory_url").asText().startsWith("https://"))
      throw new IllegalArgumentException(
          "HTTPS configuration and at least 32 random cursor-key bytes required");
    return new Config(
        field(n, "tenant"),
        field(n, "environment"),
        field(n, "issuer"),
        field(n, "audience"),
        keys(n.path("keys"), path.getParent()),
        field(n, "provider_id"),
        field(n, "provider_name"),
        field(n, "directory_url"),
        field(n, "directory_issuer"),
        field(n, "directory_audience"),
        keys(n.path("directory_keys"), path.getParent()),
        required(env, "UACRF_JDBC_URL"),
        required(env, "UACRF_DB_USER"),
        required(env, "UACRF_DB_PASSWORD"),
        cursor,
        Path.of(required(env, "UACRF_RELEASE_ROOT")).toAbsolutePath());
  }

  static String field(JsonNode n, String key) {
    var v = n.path(key);
    if (!v.isTextual() || v.asText().isBlank())
      throw new IllegalArgumentException("Missing configuration field " + key);
    return v.asText();
  }

  static Map<String, RSAPublicKey> keys(JsonNode n, Path base) throws Exception {
    var keys = new HashMap<String, RSAPublicKey>();
    var it = n.fields();
    while (it.hasNext()) {
      var e = it.next();
      String pem = Files.readString(base.resolve(e.getValue().asText()));
      String raw =
          pem.replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      var key =
          (RSAPublicKey)
              KeyFactory.getInstance("RSA")
                  .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(raw)));
      if (key.getModulus().bitLength() < 2048)
        throw new IllegalArgumentException("RSA keys must be at least 2048 bits");
      keys.put(e.getKey(), key);
    }
    if (keys.isEmpty())
      throw new IllegalArgumentException("Approved verification keys are required");
    return Map.copyOf(keys);
  }
}
