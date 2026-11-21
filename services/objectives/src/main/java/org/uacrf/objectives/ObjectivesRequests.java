package org.uacrf.objectives;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

final class ObjectivesRequests {
  private ObjectivesRequests() {}

  enum Comparator {
    GTE,
    LTE
  }

  enum Origin {
    LOCAL,
    IMPORTED
  }

  enum Decision {
    APPROVE,
    REJECT
  }

  record ProposeObjective(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String workloadId,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{0,99}$") String metric,
      @NotNull Comparator comparator,
      @NotNull BigDecimal target,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_./%-]{1,30}$") String unit,
      @Min(1) int windowSeconds,
      @NotNull Origin origin,
      @Size(max = 500) String sourceRef,
      @Size(max = 200) String sourceDefinitionId,
      @Size(max = 200) String sourceRevision,
      Instant sourceVerifiedAt) {}

  record ImportPreviewRequest(
      @NotBlank @Size(max = 200) String connectionId,
      @NotBlank @Size(max = 1000) String externalObjectiveId,
      @NotBlank @Size(max = 200) String targetWorkloadId,
      @Min(1) long mappingRevision,
      @Min(0) long expectedLocalRevision) {}

  record SyntheticMappingProposalRequest(
      @NotBlank @Size(max = 80) String targetUnit,
      boolean allowFractionToPercent,
      @NotBlank @Size(max = 1000) String reason) {}

  record Transition(@Min(1) long expectedRevision) {}

  record IdempotentSubmission(
      @Min(1) @Max(9223372036854775806L) long expectedRevision,
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String expectedContentDigest) {}

  record DecideObjective(
      @NotNull Decision decision,
      @NotBlank @Size(max = 1000) String reason,
      @Min(1) long expectedRevision) {}

  record IdempotentReview(
      @NotNull Decision decision,
      @NotBlank @Size(max = 1000) String reason,
      @Min(1) @Max(9223372036854775806L) long expectedRevision,
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String expectedContentDigest) {}

  record SetConstraint(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_./%-]{1,30}$") String unit,
      @DecimalMin("-1e15") BigDecimal minTarget,
      BigDecimal maxTarget) {}

  record ReceiptRequest(@NotBlank @Size(max = 200) String purpose) {}
}
