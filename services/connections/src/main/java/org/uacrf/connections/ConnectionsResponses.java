package org.uacrf.connections;

import java.util.List;

final class ConnectionsResponses {
  private ConnectionsResponses() {}

  record Receipt(String id, long revision, String recordKind, String result, String runtimeEffect) {}

  record Page<T>(List<T> items, String nextCursor) {}

  record Connection(
      String id,
      String name,
      String provider,
      String readOnlyIdentityRef,
      String ownerId,
      String status,
      String statusReason,
      long revision) {}

  record Binding(String id, String connectionId, String sourceRef, String serviceRef, long revision) {}

  record IngestResult(String id, String status, String reason, long sequence) {}

  record Observation(
      String id,
      String sourceRef,
      long sequence,
      String observedAt,
      String kind,
      String payload,
      String payloadDigest) {}

  record Quarantined(
      String id, String observationId, String sourceRef, long sequence, String reason, String receivedAt) {}

  record Error(String code, String message, String requestId) {}
}
