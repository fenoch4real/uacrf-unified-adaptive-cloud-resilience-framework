package org.uacrf.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class DelegationJwtConfigurationTests {
  private static final String ISSUER = "https://uacrf.local/s01";
  private static final String KEY_ID = "test-key-1";
  private static final KeyPair TEST_KEY_PAIR = generateKeyPair(2048);
  private final DelegationJwtConfiguration configuration = new DelegationJwtConfiguration();

  @Test
  void issuesTokensAcceptedOnlyForTheirConfiguredAudienceAndKeyId() {
    var decoder = decoder("S02", TEST_KEY_PAIR);
    var issuer =
        new DelegationTokenIssuer(
            DelegationJwtEncoderFactory.create(TEST_KEY_PAIR, KEY_ID),
            Clock.systemUTC(),
            ISSUER,
            KEY_ID);
    var principal =
        new DelegationPrincipal(
            "user-1",
            "tenant-1",
            "environment-1",
            "*",
            "https://idp.example.test",
            "subject-1",
            List.of("catalog.service.read"),
            List.of("owner-1"),
            List.of());

    var jwt = decoder.decode(issuer.issue(principal, "S02"));

    assertThat(jwt.getSubject()).isEqualTo("user-1");
    assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("tenant-1");
    assertThat(jwt.getClaimAsString("service_scope")).isEqualTo("*");
    assertThat(jwt.getClaimAsStringList("capabilities")).containsExactly("catalog.service.read");
    assertThat(jwt.getHeaders().get("kid")).isEqualTo(KEY_ID);
    assertThatExceptionOfType(JwtException.class)
        .isThrownBy(() -> decoder.decode(issuer.issue(principal, "S01")));
  }

  @Test
  void rejectsRsaKeysShorterThan2048Bits() {
    KeyPair weakKeyPair = generateKeyPair(1024);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> DelegationKeyMaterial.decodeKeyPair(
            encode((RSAPublicKey) weakKeyPair.getPublic()),
            encode((RSAPrivateKey) weakKeyPair.getPrivate())))
        .withMessageContaining("2048 bits");
  }

  @Test
  void rejectsMismatchedRsaKeyPairs() {
    KeyPair otherKeyPair = generateKeyPair(2048);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> DelegationKeyMaterial.decodeKeyPair(
            encode((RSAPublicKey) TEST_KEY_PAIR.getPublic()),
            encode((RSAPrivateKey) otherKeyPair.getPrivate())))
        .withMessageContaining("do not match");
  }

  @Test
  void rejectsTokensWhoseLifetimeExceedsOneMinute() {
    var encoder = DelegationJwtEncoderFactory.create(TEST_KEY_PAIR, KEY_ID);
    var decoder = decoder("S02", TEST_KEY_PAIR);
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject("user-1")
            .audience(List.of("S02"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(61))
            .claim("tenant_id", "tenant-1")
            .claim("environment_id", "environment-1")
            .claim("service_scope", "*")
            .claim("actor_issuer", "https://idp.example.test")
            .claim("actor_subject", "subject-1")
            .build();
    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
            .getTokenValue();

    assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
  }

  @Test
  void rejectsDelegationTokensWithoutAnExplicitServiceScope() {
    var encoder = DelegationJwtEncoderFactory.create(TEST_KEY_PAIR, KEY_ID);
    var decoder = decoder("S02", TEST_KEY_PAIR);
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject("user-1")
            .audience(List.of("S02"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(30))
            .claim("tenant_id", "tenant-1")
            .claim("environment_id", "environment-1")
            .claim("actor_issuer", "https://idp.example.test")
            .claim("actor_subject", "subject-1")
            .build();
    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
            .getTokenValue();

    assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
  }

  @Test
  void rejectsTokensSignedWithAnUnconfiguredKeyId() {
    var encoder = DelegationJwtEncoderFactory.create(TEST_KEY_PAIR, "other-key");
    var decoder = decoder("S02", TEST_KEY_PAIR);
    var issuer =
        new DelegationTokenIssuer(encoder, Clock.systemUTC(), ISSUER, "other-key");
    var principal =
        new DelegationPrincipal(
            "user-1",
            "tenant-1",
            "environment-1",
            "*",
            "https://idp.example.test",
            "subject-1",
            List.of(),
            List.of(),
            List.of());

    assertThatExceptionOfType(JwtException.class)
        .isThrownBy(() -> decoder.decode(issuer.issue(principal, "S02")));
  }

  private JwtDecoder decoder(String audience, KeyPair keyPair) {
    var publicKey = (RSAPublicKey) keyPair.getPublic();
    return configuration.delegationJwtDecoder(publicKey, ISSUER, audience, KEY_ID);
  }

  private static KeyPair generateKeyPair(int keySize) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(keySize);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Unable to create test RSA keys", error);
    }
  }

  private static String encode(java.security.Key key) {
    return Base64.getEncoder().encodeToString(key.getEncoded());
  }
}
