package org.uacrf.topology;

import jakarta.validation.constraints.*;
import java.time.Instant;

final class TopologyRequests {
  private TopologyRequests() {}
  enum ProvenanceKind { OBSERVED, AUTHORED }
  enum Decision { ACCEPT, REJECT }
  record Propose(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Size(max=100) String sourceType,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String sourceId,
      @NotBlank @Size(max=100) String relationshipType,
      @NotBlank @Size(max=100) String targetType,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String targetId,
      @NotNull ProvenanceKind provenanceKind, @Size(max=200) String sourceSystem,
      @Size(max=200) String sourceRevision, Instant verifiedAt) {}
  record EvidenceBackedPropose(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank String sourceType,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String sourceId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String sourceVersion,
      @NotBlank String relationshipType,
      @NotBlank String targetType,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String targetId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String targetVersion,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String evidenceId,
      @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String evidenceDigest,
      @NotNull @Min(1) Long evidenceRevision) {}
  record Review(@NotNull Decision decision, @NotNull @Min(1) Long expectedRevision) {}
  record Accept(@NotBlank @Size(max=200) String scope, @NotNull @Min(0) Long expectedRevisionNumber) {}
  record ClassifyDifferences(
      @NotBlank @Size(max=200) String serviceId,
      @NotBlank @Size(max=200) String caseId,
      @NotBlank @Size(max=2000) String reason,
      @NotNull @Min(0) Long expectedRevision,
      @NotBlank @Size(max=200) String authorizedGraphRevisionId,
      @NotBlank @Size(max=200) String currentGraphRevisionId,
      @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String selectionDigest) {}
  record SelectSubgraph(
      @NotBlank @Size(max=200) String serviceId,
      @NotBlank @Size(max=200) String caseId,
      @NotBlank @Size(max=200) String graphRevisionId,
      @NotBlank @Size(max=2000) String reason,
      @NotNull @Min(0) Long expectedRevision,
      @NotBlank @Size(max=200) String queryId,
      @NotBlank @Size(max=2048) String queryParametersRef,
      @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String queryParametersDigest,
      @NotBlank @Size(max=500) String purpose,
      @NotNull @Min(1) @Max(10000) Integer maximumNodes) {}
}
