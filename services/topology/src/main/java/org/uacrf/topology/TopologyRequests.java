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
  record Review(@NotNull Decision decision, @NotNull @Min(1) Long expectedRevision) {}
  record Accept(@NotBlank @Size(max=200) String scope, @NotNull @Min(0) Long expectedRevisionNumber) {}
}
