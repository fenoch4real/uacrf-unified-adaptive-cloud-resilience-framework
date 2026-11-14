package org.uacrf.identity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

final class IdentityRequests {
  private IdentityRequests() {}

  record Delegation(
      @NotBlank @Pattern(regexp = "^S0[234]$") String audience,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceId,
      @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 200) String> capabilityIds,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String tenantId,
      @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String environmentId) {}

  record RevokeMembership(
      @Min(1) long expectedRevision, @NotBlank @Size(max = 2000) String reason) {}

  record CreateGrantDecision(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String principalId,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String capability,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String serviceScope,
      @NotNull Instant expiresAt,
      @NotBlank @Size(max = 2000) String reason) {}

  record CreateMembership(
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          String id,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String principalId,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String capability,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String serviceScope,
      @NotNull Instant expiresAt,
      @NotBlank @Size(max = 2000) String reason,
      @Min(0) @Max(0) long expectedRevision,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String authorizationRef) {}

  record AdmitPrincipal(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String providerId,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String directorySubjectRef,
      @NotBlank @Size(max = 2000) String reason,
      @Min(0) @Max(0) long expectedRevision) {}

  record SetPrincipalStatus(
      @NotNull Boolean enabled,
      @Min(1) long expectedRevision,
      @NotBlank @Size(max = 2000) String reason,
      @Pattern(regexp = "^\\S+$") @Size(max = 200) String grantReviewRef) {}
}
