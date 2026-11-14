package org.uacrf.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class DelegationTokenValidator implements OAuth2TokenValidator<Jwt> {
  private static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);

  private final String issuer;
  private final String audience;
  private final String keyId;

  DelegationTokenValidator(String issuer, String audience, String keyId) {
    this.issuer = issuer;
    this.audience = audience;
    this.keyId = keyId;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    Instant issuedAt = jwt.getIssuedAt();
    Instant expiresAt = jwt.getExpiresAt();
    if (!issuer.equals(jwt.getIssuer().toString())
        || !jwt.getAudience().contains(audience)
        || !keyId.equals(jwt.getHeaders().get("kid"))
        || issuedAt == null
        || expiresAt == null
        || !expiresAt.isAfter(issuedAt)
        || Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0
        || blank(jwt.getSubject())
        || blank(jwt.getClaimAsString("tenant_id"))
        || blank(jwt.getClaimAsString("environment_id"))
        || !validServiceScope(jwt.getClaimAsString("service_scope"))
        || blank(jwt.getClaimAsString("actor_issuer"))
        || blank(jwt.getClaimAsString("actor_subject"))) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Invalid UACRF service delegation", null));
    }
    return OAuth2TokenValidatorResult.success();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean validServiceScope(String value) {
    return "*".equals(value)
        || (value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$"));
  }
}
