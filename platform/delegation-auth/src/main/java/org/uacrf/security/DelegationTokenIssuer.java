package org.uacrf.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

public final class DelegationTokenIssuer {
  private final JwtEncoder encoder;
  private final Clock clock;
  private final String issuer;
  private final String keyId;

  public DelegationTokenIssuer(JwtEncoder encoder, Clock clock, String issuer, String keyId) {
    this.encoder = encoder;
    this.clock = clock;
    this.issuer = issuer;
    DelegationKeyMaterial.validateKeyId(keyId);
    this.keyId = keyId;
  }

  public String issue(DelegationPrincipal principal, String audience) {
    Instant issuedAt = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(principal.principalId())
            .audience(List.of(audience))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(30))
            .id(UUID.randomUUID().toString())
            .claim("tenant_id", principal.tenantId())
            .claim("environment_id", principal.environmentId())
            .claim("service_scope", principal.serviceScope())
            .claim("actor_issuer", principal.actorIssuer())
            .claim("actor_subject", principal.actorSubject())
            .claim("capabilities", principal.capabilities())
            .claim("eligible_owner_ids", principal.eligibleOwnerIds())
            .claim("runtime_principal_ids", principal.runtimePrincipalIds())
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
