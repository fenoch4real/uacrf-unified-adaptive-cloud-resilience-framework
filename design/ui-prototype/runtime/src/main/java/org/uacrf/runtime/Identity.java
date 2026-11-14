package org.uacrf.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

final class Identity {
  static ObjectNode verify(
      String token, String issuer, String audience, Map<String, RSAPublicKey> keys, int maxAge) {
    try {
      if (token.length() > 65536) throw new IllegalArgumentException();
      SignedJWT jwt = SignedJWT.parse(token);
      var header = jwt.getHeader();
      var key = keys.get(header.getKeyID());
      if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
          || key == null
          || !jwt.verify(new RSASSAVerifier(key))
          || header.getCriticalParams() != null) throw new IllegalArgumentException();
      JsonNode parsed = Json.read(jwt.getPayload().toString());
      if (!(parsed instanceof ObjectNode c)) throw new IllegalArgumentException();
      long now = Instant.now().getEpochSecond();
      for (String name : List.of("iat", "exp", "nbf"))
        if (!c.path(name).isIntegralNumber() || !c.path(name).canConvertToLong())
          throw new IllegalArgumentException();
      long iat = c.path("iat").asLong(), exp = c.path("exp").asLong(), nbf = c.path("nbf").asLong();
      boolean aud =
          c.path("aud").isTextual()
              ? audience.equals(c.path("aud").asText())
              : c.path("aud").isArray()
                  && java.util.stream.StreamSupport.stream(c.path("aud").spliterator(), false)
                      .anyMatch(x -> x.isTextual() && audience.equals(x.asText()));
      if (!issuer.equals(c.path("iss").asText())
          || !aud
          || !c.path("sub").isTextual()
          || c.path("sub").asText().isBlank()
          || c.path("sub").asText().length() > 200
          || !c.path("jti").isTextual()
          || c.path("jti").asText().isBlank()
          || iat > now
          || exp <= now
          || nbf > now
          || exp <= iat
          || exp - iat > maxAge) throw new IllegalArgumentException();
      return c;
    } catch (Exception e) {
      throw new Problem(401, "Invalid or expired verified identity");
    }
  }

  interface Source {
    ObjectNode verified(String kind, String reference);
  }

  static final class Directory implements Source {
    private final Config cfg;
    private final HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    Directory(Config cfg) {
      this.cfg = cfg;
    }

    public ObjectNode verified(String kind, String ref) {
      try {
        if (ref == null
            || ref.isBlank()
            || ref.length() > 200
            || !Set.of("identity", "grant", "review").contains(kind))
          throw new IllegalArgumentException();
        URI uri =
            URI.create(
                cfg.directoryUrl().replaceAll("/$", "")
                    + "/"
                    + kind
                    + "/"
                    + URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20"));
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null)
          throw new IllegalArgumentException();
        var request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/jwt");
        String credential = System.getenv("UACRF_DIRECTORY_TOKEN");
        if (credential != null) request.header("Authorization", "Bearer " + credential);
        String token;
        var response =
            client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
          byte[] bytes = stream.readNBytes(65537);
          if (response.statusCode() != 200 || bytes.length > 65536)
            throw new IllegalArgumentException();
          token = new String(bytes, StandardCharsets.UTF_8);
        }
        return assertion(cfg, kind, ref, token);
      } catch (Exception e) {
        throw new Problem(503, "Verified directory or authorization source unavailable");
      }
    }
  }

  static ObjectNode assertion(Config cfg, String kind, String ref, String token) {
    ObjectNode c =
        verify(token, cfg.directoryIssuer(), cfg.directoryAudience(), cfg.directoryKeys(), 300);
    if (!cfg.tenant().equals(c.path("tenant_id").asText())
        || !cfg.environment().equals(c.path("environment_id").asText())
        || !kind.equals(c.path("purpose").asText())
        || !ref.equals(c.path("reference").asText()))
      throw new Problem(503, "Source binding rejected");
    if (kind.equals("identity")) {
      if (!cfg.issuer().equals(c.path("identity_issuer").asText())
          || !Set.of("HUMAN", "SYSTEM", "AGENT").contains(c.path("identity_kind").asText())
          || !c.path("identity_subject").isTextual()
          || c.path("identity_subject").asText().isBlank()
          || c.path("identity_subject").asText().length() > 200
          || !c.path("display_name").isTextual()
          || c.path("display_name").asText().isBlank()
          || c.path("display_name").asText().length() > 200)
        throw new Problem(503, "Source identity rejected");
      ObjectNode n = Json.M.createObjectNode();
      n.put("provider_id", cfg.providerId())
          .put("issuer", cfg.issuer())
          .put("subject", c.path("identity_subject").asText())
          .put("display_name", c.path("display_name").asText())
          .put("kind", c.path("identity_kind").asText())
          .put("exp", c.path("exp").asLong())
          .put("jti", c.path("jti").asText())
          .put("assertion_digest", Json.hash(token));
      return n;
    }
    c.put("assertion_digest", Json.hash(token));
    return c;
  }
}
