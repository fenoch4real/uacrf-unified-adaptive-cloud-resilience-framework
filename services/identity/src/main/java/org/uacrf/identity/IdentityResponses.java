package org.uacrf.identity;

import java.util.List;

final class IdentityResponses {
  private IdentityResponses() {}

  record MeAccess(
      String principalId,
      String displayName,
      String kind,
      String serviceId,
      String scope,
      List<String> capabilityIds) {}

  record CurrentContext(String tenantId, String tenantName, String environmentId, String environmentName) {}

  record CurrentContextList(List<CurrentContext> items, String nextCursor) {}

  record OwnerCandidate(
      String id,
      String displayName,
      String kind,
      boolean enabled,
      boolean ownerEligible,
      long revision) {}

  record OwnerPage(List<OwnerCandidate> items, String nextCursor) {}

  record Delegation(String accessToken, String tokenType, int expiresIn) {}

  record Error(String code, String message, String requestId) {}

  record IdentityProvider(String id, String displayName, String status) {}

  record IdentityProviderList(List<IdentityProvider> items) {}

  record Principal(
      String id,
      String displayName,
      String kind,
      boolean enabled,
      boolean ownerEligible,
      long revision) {}

  record PrincipalPage(List<Principal> items, String nextCursor) {}

  record Membership(
      String id,
      String principalId,
      String capability,
      String serviceScope,
      java.time.Instant expiresAt,
      long revision) {}

  record MembershipPage(List<Membership> items, String nextCursor) {}

  record MutationReceipt(String id, long revision, String result, String runtimeEffect) {}
}
