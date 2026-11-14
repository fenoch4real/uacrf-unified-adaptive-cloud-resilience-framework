package org.uacrf.connections;

import java.time.Instant;
import java.util.List;

/**
 * Seam for real, read-only provider collection. Implementations pull provider telemetry and
 * inventory and return {@link Batch}es that the caller submits through the existing observation
 * intake. Until a collector passes the live read-only conformance suite its provider connection
 * stays UNACCEPTED, so the shipped AWS, Azure and GCP collectors return {@link Batch#notImplemented}
 * and never fabricate data.
 */
interface ProviderCollector {
  ConnectionsRequests.Provider provider();

  /** Pulls one bounded window for a bound source; {@code checkpoint} is null on first collection. */
  Batch collect(Request request);

  record Request(String connectionId, String sourceRef, String checkpoint, Instant from, Instant to) {}

  record Observation(String id, long sequence, Instant observedAt, String kind, String payload) {}

  /**
   * Outcome of one pull. A collector must map provider conditions explicitly: pagination into
   * {@code nextCheckpoint}, rate limits into RATE_LIMITED, permission denial into PERMISSION_DENIED
   * and outages into SOURCE_UNAVAILABLE, so intake can record them rather than lose them.
   */
  record Batch(Status status, List<Observation> observations, String nextCheckpoint, String detail) {
    enum Status {
      COLLECTED,
      RATE_LIMITED,
      PERMISSION_DENIED,
      SOURCE_UNAVAILABLE,
      NOT_IMPLEMENTED
    }

    static Batch notImplemented(String detail) {
      return new Batch(Status.NOT_IMPLEMENTED, List.of(), null, detail);
    }
  }
}
