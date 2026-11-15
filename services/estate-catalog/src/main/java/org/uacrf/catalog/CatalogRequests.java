package org.uacrf.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

final class CatalogRequests {
  private CatalogRequests() {}

  record RegisterService(
      @NotBlank @Size(max = 2000) String reason,
      @Min(0) long expectedRevision,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String ownerId,
      @Min(1) @Max(5) int criticality,
      @NotBlank String lifecycle) {}

  record RegisterAgent(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceId,
      @NotBlank @Size(max = 200) String name,
      @NotNull AgentKind kind,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String ownerId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          String runtimePrincipalId,
      @NotBlank @Size(max = 2048) String purpose) {}

  record RegisterAgentVersion(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @Min(1) long versionNumber,
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String digest,
      @NotBlank @Size(max = 4096) String objectRef,
      @NotNull @Valid WorkloadVersionManifest manifest) {}

  record ActivateAgentVersion(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String versionId,
      @Min(1) long expectedRevision) {}

  record WorkloadVersionManifest(
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String instructionsDigest,
      @NotBlank @Size(max = 4096) String modelRef,
      @NotNull @Size(max = 100) java.util.List<@NotBlank @Size(max = 4096) String> knowledgeRefs,
      @NotNull @Size(max = 100) java.util.List<@NotNull @Valid WorkloadTool> tools,
      @NotBlank @Size(max = 2048) String memoryNamespace,
      @NotBlank @Size(max = 4096) String evaluationSuiteRef,
      @NotBlank @Size(max = 4096) String recoveryRulesRef,
      @NotBlank @Size(max = 2048) String runtimeBuild,
      @NotBlank @Size(max = 4096) String dataPolicyRef,
      @NotBlank @Size(max = 4096) String handoffRef,
      @NotNull @Valid WorkloadLimits limits) {}

  record WorkloadTool(
      @NotBlank @Size(max = 4096) String toolRef,
      @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String schemaDigest,
      @NotNull @Size(min = 1) java.util.List<@NotNull ToolEffect> effects,
      @Min(1) @Max(3_600_000) int timeoutMs) {}

  record WorkloadLimits(
      @Min(1) int steps,
      @Min(0) int tokens,
      @Min(0) int costUnits,
      @Min(1) int durationSeconds) {}

  enum ToolEffect {
    READ,
    WRITE,
    EXECUTE
  }

  record RegisterJourney(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String ownerId,
      @NotBlank @Size(max = 2000) String purpose,
      @NotNull JourneyLifecycle lifecycle) {}

  record RegisterStep(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @Min(1) int position,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 2000) String intendedOutcome) {}

  record LinkCapability(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotNull CapabilityKind capabilityKind,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          String capabilityId,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String stepId,
      @NotNull Criticality criticality) {}

  enum AgentKind {
    SINGLE_AGENT,
    MULTI_AGENT
  }

  enum JourneyLifecycle {
    DRAFT,
    ACTIVE,
    RETIRED
  }

  enum CapabilityKind {
    SERVICE,
    AGENT
  }

  enum Criticality {
    REQUIRED,
    OPTIONAL,
    UNKNOWN
  }
}
