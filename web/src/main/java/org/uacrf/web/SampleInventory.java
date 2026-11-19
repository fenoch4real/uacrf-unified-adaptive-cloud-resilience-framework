package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class SampleInventory {
  private static final Set<String> KINDS =
      Set.of("aws.config.resources", "azure.resourcegraph.rows", "gcp.assetinventory.assets");
  private final Connection connection;
  private final List<Binding> bindings;
  private final List<Observation> observations;

  SampleInventory(ObjectMapper mapper) throws IOException {
    Fixture fixture;
    try (var input = new ClassPathResource(
        "sample-data/connections/sample-multicloud-estate.json").getInputStream()) {
      fixture = mapper.readValue(input, Fixture.class);
    }
    if (fixture == null || fixture.label() == null || !fixture.label().startsWith("SAMPLE DATA")
        || fixture.connection() == null || !"SAMPLE_LOCAL".equals(fixture.connection().provider())
        || fixture.connection().expectedRevision() != 0
        || fixture.bindings() == null || fixture.deliveries() == null) {
      throw new IllegalStateException("The bundled sample inventory fixture is invalid.");
    }
    this.connection = fixture.connection();
    List<Observation> selected = new ArrayList<>();
    Set<String> sources = new HashSet<>();
    Set<String> kinds = new HashSet<>();
    Set<String> ids = new HashSet<>();
    Map<String, Long> sequences = new HashMap<>();
    for (Delivery delivery : fixture.deliveries()) {
      Observation item = delivery.observation();
      if (item != null && KINDS.contains(item.kind()) && "ACCEPTED".equals(delivery.expect())) {
        if (item.id() == null || !ids.add(item.id()) || item.sourceRef() == null
            || !item.sourceRef().startsWith("sample:") || item.sequence() <= sequences.getOrDefault(item.sourceRef(), 0L)
            || item.payload() == null || !sha256(item.payload()).equals(item.payloadDigest())) {
          throw new IllegalStateException("A bundled sample inventory observation is invalid.");
        }
        Instant.parse(item.observedAt());
        sequences.put(item.sourceRef(), item.sequence());
        sources.add(item.sourceRef());
        kinds.add(item.kind());
        selected.add(item);
      }
    }
    List<Binding> selectedBindings = fixture.bindings().stream()
        .filter(binding -> sources.contains(binding.sourceRef())).toList();
    if (!kinds.equals(KINDS) || selectedBindings.size() != sources.size()
        || selectedBindings.stream().anyMatch(binding -> binding.id() == null
            || binding.serviceRef() != null || binding.expectedRevision() != 0)
        || selectedBindings.stream().map(Binding::sourceRef).distinct().count() != sources.size()) {
      throw new IllegalStateException("Bundled sample inventory source bindings are invalid.");
    }
    this.bindings = List.copyOf(selectedBindings);
    this.observations = List.copyOf(selected);
  }

  Connection connection(String id, String ownerId) {
    return new Connection(id, connection.name(), "SAMPLE_LOCAL", connection.readOnlyIdentityRef(), ownerId, 0);
  }

  List<Binding> bindings(String connectionId) {
    return bindings.stream()
        .map(binding -> new Binding("sample-bind-" + sha256(connectionId + "\n" + binding.id()).substring(0, 32),
            binding.sourceRef(), null, 0))
        .toList();
  }

  List<Observation> observations(String connectionId) {
    return observations.stream().map(item -> new Observation(connectionId + "-" + item.id(),
        item.sourceRef(), item.sequence(), item.observedAt(), item.kind(), item.payload(),
        item.payloadDigest())).toList();
  }

  static String connectionId(BrowserContext context, String principal, String owner) {
    return "sample-estate-" + sha256(context.tenantId() + "\n" + context.environmentId()
        + "\n" + principal + "\n" + owner).substring(0, 32);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Fixture(@JsonProperty("_label") String label, Connection connection,
      List<Binding> bindings, List<Delivery> deliveries) {}
  record Connection(String id, String name, String provider,
      @JsonProperty("read_only_identity_ref") String readOnlyIdentityRef,
      @JsonProperty("owner_id") String ownerId, @JsonProperty("expected_revision") long expectedRevision) {}
  record Binding(String id, @JsonProperty("source_ref") String sourceRef,
      @JsonProperty("service_ref") String serviceRef, @JsonProperty("expected_revision") long expectedRevision) {}
  record Delivery(String expect, Observation observation) {}
  record Observation(String id, @JsonProperty("source_ref") String sourceRef, long sequence,
      @JsonProperty("observed_at") String observedAt, String kind, String payload,
      @JsonProperty("payload_digest") String payloadDigest) {}
}
