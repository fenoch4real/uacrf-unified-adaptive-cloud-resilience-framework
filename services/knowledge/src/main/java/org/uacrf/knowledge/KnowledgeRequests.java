package org.uacrf.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

final class KnowledgeRequests {
  private KnowledgeRequests() {}

  enum Classification {
    PUBLIC,
    INTERNAL,
    RESTRICTED
  }

  enum Decision {
    APPROVE,
    REJECT
  }

  record StageItem(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String itemId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$") String version,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 500) String sourceRef,
      @NotNull @Size(max = 262144) String content,
      @NotNull Classification classification,
      @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 200) String> allowedPurposes,
      @NotNull @Size(max = 100) List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String> targetWorkloadIds,
      @NotNull Instant reviewDeadline,
      @NotBlank @Size(max = 2000) String reason) {}

  record ReviewItem(
      @NotNull Decision decision,
      @Min(1) long expectedRevision,
      @NotBlank @Size(max = 2000) String reason) {}

  record BoundReviewItem(
      @NotNull Decision decision,
      @Min(1) long expectedRevision,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$") String expectedVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedSourceDigest,
      @NotBlank @Size(max = 2000) String reason) {}

  record WithdrawItem(
      @Min(1) long expectedRevision,
      @NotBlank @Size(max = 2000) String reason) {}

  record RunRetrieval(
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String caseId,
      @NotBlank @Size(max = 200) String purpose,
      @NotNull Classification maxClassification,
      @NotBlank @Size(max = 2000) String query,
      @Min(1) int maxResults,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String workloadId) {}

  record ReceiptRequest(
      @NotBlank @Size(max = 200) String caseId,
      @NotBlank @Size(max = 200) String purpose,
      @NotNull Classification classification,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String manifestDigest) {}
}
