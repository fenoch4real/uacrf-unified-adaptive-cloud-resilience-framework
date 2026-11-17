package org.uacrf.decisiongovernance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class PolicyRequests {
  private PolicyRequests() {}

  record ProposePolicy(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceId,
      @NotNull @Min(0) Long expectedRevision,
      @NotNull Instant expiresAt,
      @NotNull @Valid Definition definition,
      @NotBlank @Size(max = 2000) String reason,
      @NotNull Instant effectiveFrom) {}

  record DecidePolicy(
      @NotNull @Min(1) Long expectedRevision,
      @NotNull Decision decision,
      @NotBlank @Size(max = 2000) String reason,
      @Size(min = 1, max = 16000) String signedAssertion) {}

  enum Decision {
    APPROVE,
    REJECT
  }

  record Definition(
      @NotEmpty @Size(max = 100)
          List<@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,79}$") String> allowedActionKinds,
      @NotEmpty @Size(max = 100)
          List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String>
              allowedActorIds,
      @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 200) String> allowedResourceIds,
      @NotEmpty @Size(max = 100) List<@NotNull Provider> permittedProviders,
      @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 200) String> permittedRegions,
      @NotNull @Min(0) @Max(10) Integer requiredApproverCount,
      @NotEmpty @Size(max = 100)
          List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String>
              eligibleApproverIds,
      @NotNull @DecimalMin("0") @DecimalMax("1000000000") BigDecimal maximumCostUsd) {}

  enum Provider {
    AWS,
    AZURE,
    GCP,
    AGENT_RUNTIME
  }
}
