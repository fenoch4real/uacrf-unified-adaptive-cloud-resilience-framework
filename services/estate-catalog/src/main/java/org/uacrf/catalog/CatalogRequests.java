package org.uacrf.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
