package org.uacrf.security;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

public record DelegationPrincipal(
    String principalId,
    String tenantId,
    String environmentId,
    String serviceScope,
    String actorIssuer,
    String actorSubject,
    List<String> capabilities,
    List<String> eligibleOwnerIds,
    List<String> runtimePrincipalIds) {
  public DelegationPrincipal {
    if (serviceScope == null || serviceScope.isBlank()) {
      throw new IllegalArgumentException("Delegation service scope is required.");
    }
    capabilities = List.copyOf(capabilities);
    eligibleOwnerIds = List.copyOf(eligibleOwnerIds);
    runtimePrincipalIds = List.copyOf(runtimePrincipalIds);
  }

  public static DelegationPrincipal from(Jwt jwt) {
    return new DelegationPrincipal(
        jwt.getSubject(),
        jwt.getClaimAsString("tenant_id"),
        jwt.getClaimAsString("environment_id"),
        jwt.getClaimAsString("service_scope"),
        jwt.getClaimAsString("actor_issuer"),
        jwt.getClaimAsString("actor_subject"),
        stringList(jwt.getClaim("capabilities")),
        stringList(jwt.getClaim("eligible_owner_ids")),
        stringList(jwt.getClaim("runtime_principal_ids")));
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
  }
}
