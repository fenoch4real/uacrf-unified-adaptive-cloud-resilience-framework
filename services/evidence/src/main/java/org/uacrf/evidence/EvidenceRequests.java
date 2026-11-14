package org.uacrf.evidence;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class EvidenceRequests {
  private EvidenceRequests() {}

  enum SourceClass {
    SAMPLE_LOCAL,
    PROVIDER
  }

  enum Decision {
    QUALIFY,
    REJECT
  }

  record SubmitEvidence(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotNull SourceClass sourceClass,
      @NotBlank @Size(max = 500) @Pattern(regexp = "^\\S+$") String sourceRef,
      @Min(1) long sequence,
      @NotNull java.time.Instant observedAt,
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{0,99}$") String kind,
      @NotBlank @Size(max = 16384) String payload,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payloadDigest) {}

  record ReviewEvidence(
      @NotNull Decision decision,
      @NotBlank @Size(max = 1000) String reason,
      @Min(1) long expectedRevision) {}

  record ReceiptRequest(
      @NotBlank @Size(max = 200) String purpose,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedDigest,
      @NotNull java.time.Instant cutoff) {}

  record InboxEvent(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String eventId,
      @NotBlank @Pattern(regexp = "^S[0-9]{2}$") String sourceService,
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{0,99}$") String eventType,
      @NotBlank @Size(max = 200) String aggregateId,
      @Min(1) long aggregateSequence,
      @NotBlank @Size(max = 16384) String payload,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payloadDigest) {}
}
