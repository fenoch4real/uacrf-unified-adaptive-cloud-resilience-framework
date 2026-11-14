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
