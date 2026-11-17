package org.uacrf.catalog;

import java.util.List;

final class CatalogResponses {
  private CatalogResponses() {}

  record Receipt(
      String id, long revision, String recordKind, String result, String runtimeEffect) {}

  record Page<T>(List<T> items, String nextCursor) {}

  record ServiceRecord(
      String id,
      String name,
      String ownerId,
      String lifecycle,
      int criticality,
      long revision) {}

  record Agent(
      String id,
      String serviceId,
      String name,
      String kind,
      String ownerId,
      String runtimePrincipalId,
      String purpose,
      long revision) {}

  record WorkloadVersionReceipt(
      String profileId,
      String id,
      long versionNumber,
      String digest,
      String objectRef,
      CatalogRequests.WorkloadVersionManifest manifest) {}

  record OwnerReceiptClaims(
      String protocol,
      String keyId,
      String receiptId,
      String issuerService,
      String audienceService,
      String tenantId,
      String environmentId,
      String actorId,
      String resourceType,
      String resourceId,
      String resourceVersion,
      long resourceRevision,
      String contentDigest,
      long authorityEpoch,
      boolean eligible,
      String purpose,
      String issuedAt,
      String validUntil) {}

  record OwnerReceipt(
      String protocol,
      String keyId,
      String receiptId,
      String issuerService,
      String audienceService,
      String tenantId,
      String environmentId,
      String actorId,
      String resourceType,
      String resourceId,
      String resourceVersion,
      long resourceRevision,
      String contentDigest,
      long authorityEpoch,
      boolean eligible,
      String purpose,
      String issuedAt,
      String validUntil,
      String signature) {}

  record Step(
      String id,
      int position,
      String name,
      String intendedOutcome,
      long revision) {}

  record Capability(
      String id,
      String capabilityKind,
      String capabilityId,
      String stepId,
      String criticality,
      long revision) {}

  record Journey(
      String id,
      String name,
      String ownerId,
      String purpose,
      String lifecycle,
      long revision,
      List<Step> steps,
      List<Capability> capabilities) {}

  record Error(String code, String message, String requestId) {}
}
