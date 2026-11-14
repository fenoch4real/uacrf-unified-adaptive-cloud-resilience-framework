package org.uacrf.assessment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class AssessmentRequests {
  private AssessmentRequests() {}
  enum Comparator { LTE, GTE }
  enum Mode { SHADOW, DRY_RUN, LIVE }
  enum ModelDecision { APPROVE, REJECT }
  enum TargetMode { SHADOW, ADVISORY }
  record ObjectiveRef(@NotBlank @Size(max=200) String objectiveId, @Min(1) long revision,
      @NotBlank @Size(max=200) String digest) {}
  record GraphRef(@NotBlank @Size(max=200) String graphId, @Min(1) long revision,
      @NotBlank @Size(max=200) String digest) {}
  record CreateCase(@NotBlank @Size(max=200) String workloadId, @NotBlank @Size(max=200) String purpose,
      @NotBlank @Size(max=100) String metric, @NotNull Comparator comparator, @NotNull BigDecimal threshold,
      @NotBlank @Size(max=30) String unit, @Min(1) @Max(31536000) int windowSeconds,
      @NotNull @Valid ObjectiveRef objectiveRef, @NotNull @Valid GraphRef graphRef,
      @NotEmpty @Size(max=100) List<@NotBlank @Size(max=200) String> requiredSourceKinds) {}
  record Transition(@Min(1) long expectedRevision) {}
  record Measurement(@NotBlank @Size(max=200) String sourceReceiptId,
      @NotBlank @Size(max=200) String sourceKind, @NotNull Instant observedAt,
      @NotNull BigDecimal value, @NotBlank @Size(max=30) String unit) {}
  record Evaluate(@NotNull Instant observationCutoff, @NotNull Mode mode,
      @NotNull @Size(max=1000) List<@NotNull @Valid Measurement> measurements) {}
  record RetainModelVersion(@NotBlank @Size(max=200) String modelId,
      @NotBlank @Size(max=100) String version, @NotBlank @Size(max=1000) String artifactRef,
      @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String digest,
      @NotBlank @Size(max=1000) String modelCardRef,
      @NotBlank @Size(max=1000) String evaluationPackRef,
      @NotBlank @Size(max=2000) String reason) {}
  record ReviewModelVersion(@Min(1) long expectedRevision, @NotNull ModelDecision decision,
      @Size(max=100) List<@NotBlank @Size(max=200) String> evaluationResultIds,
      @NotBlank @Size(max=2000) String reason) {}
  record PromoteModelVersion(@Min(1) long expectedRevision, @NotNull TargetMode targetMode,
      @NotBlank @Size(max=2000) String reason) {}
  record CreateIncident(@NotBlank @Size(max=200) String workloadId,
      @NotBlank @Size(max=200) String sourceCaseId, @NotNull Instant observedFrom,
      @NotBlank @Size(max=4000) String description,
      @NotEmpty @Size(max=100) List<@NotBlank @Size(max=200) String> evidenceRefs,
      @NotBlank @Size(max=2000) String reason) {}
  record ActionItem(@NotBlank @Size(max=2000) String description,
      @NotBlank @Size(max=200) String ownerId, @NotNull Instant dueAt,
      @Size(max=1000) String proposedVersionRef,
      @NotBlank @Size(max=2000) String closureTest) {}
  record ProposeLearningRecord(@NotBlank @Size(max=200) String incidentId,
      @Size(max=200) String experimentId,
      @NotEmpty @Size(max=100) List<@NotBlank @Size(max=2000) String> hypotheses,
      @NotEmpty @Size(max=100) List<@NotNull @Valid ActionItem> actionItems,
      @NotEmpty @Size(max=100) List<@NotBlank @Size(max=200) String> evidenceRefs,
      @Min(1) long expectedRevision, @NotBlank @Size(max=2000) String reason) {}
}
