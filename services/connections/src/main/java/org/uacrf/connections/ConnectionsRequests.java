package org.uacrf.connections;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class ConnectionsRequests {
  private ConnectionsRequests() {}

  enum Provider {
    SAMPLE_LOCAL,
    AWS,
    AZURE,
    GCP
  }

  record RegisterConnection(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max = 200) String name,
      @NotNull Provider provider,
      @NotBlank @Size(max = 500) @Pattern(regexp = "^\\S+$") String readOnlyIdentityRef,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String ownerId,
      @Min(0) long expectedRevision) {}

  record BindSource(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max = 500) @Pattern(regexp = "^\\S+$") String sourceRef,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceRef,
      @Min(0) long expectedRevision) {}

  record SubmitObservation(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max = 500) @Pattern(regexp = "^\\S+$") String sourceRef,
      @Min(1) long sequence,
      @NotNull java.time.Instant observedAt,
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{0,99}$") String kind,
      @NotBlank @Size(max = 16384) String payload,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payloadDigest) {}
}
