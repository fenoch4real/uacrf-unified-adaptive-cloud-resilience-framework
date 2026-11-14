package org.uacrf.sampledata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class SampleFixturesTests {
  private static final Path ROOT = Path.of(".");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void committedFixturesMatchTheGenerators() throws IOException {
    Map<String, String> rendered = SampleFixtures.render();
    if (Boolean.getBoolean("uacrf.sample.regenerate")) {
      SampleFixtures.write(ROOT, rendered);
    }
    for (Map.Entry<String, String> file : rendered.entrySet()) {
      assertThat(Files.readString(ROOT.resolve(file.getKey())))
          .as("%s is stale; run ./mvnw -pl sample-data test -Duacrf.sample.regenerate=true", file.getKey())
          .isEqualTo(file.getValue());
    }
  }

  @Test
  void graphCoversTheWholeDataDictionaryAndConforms() throws IOException {
    DataDictionary dictionary = DataDictionary.load(SampleFixtures.DICTIONARY);
    Map<String, Object> graph = graph(dictionary);
    GraphValidator validator = new GraphValidator(dictionary);
    GraphValidator.Result result = validator.validate(graph, GraphSample.TENANT);

    assertThat(dictionary.nodes).hasSize(58);
    assertThat(dictionary.relationships).hasSize(55);
    assertThat(result.problems()).isEmpty();
    assertThat(validator.coverageGaps(result)).isEmpty();
  }

  @Test
  void validatorRejectsAliasesUndefinedPropertiesAndBadEndpoints() throws IOException {
    DataDictionary dictionary = DataDictionary.load(SampleFixtures.DICTIONARY);
    Map<String, Object> graph = MAPPER.readValue(Json.compact(graph(dictionary)), new TypeReference<>() {});
    List<Map<String, Object>> nodes = castList(graph.get("nodes"));
    List<Map<String, Object>> relationships = castList(graph.get("relationships"));
    Map<String, Object> service = nodes.stream().filter(n -> "Service".equals(n.get("label"))).findFirst().orElseThrow();
    Map<String, Object> properties = cast(service.get("properties"));
    properties.put("serviceId", "alias");
    properties.put("invented_property", 1);
    properties.put("source_kind", "AWS_API");
    relationships.stream().filter(r -> "OWNED_BY".equals(r.get("type"))).findFirst().orElseThrow()
        .put("target", "ev-aws-checkout");
    relationships.add(new java.util.LinkedHashMap<>(relationships.getFirst()));

    List<String> problems = new GraphValidator(dictionary).validate(graph, GraphSample.TENANT).problems();

    assertThat(problems).anyMatch(p -> p.contains("serviceId is not snake_case"));
    assertThat(problems).anyMatch(p -> p.contains("undefined property invented_property"));
    assertThat(problems).anyMatch(p -> p.contains("not marked SYNTHETIC_FIXTURE"));
    assertThat(problems).anyMatch(p -> p.contains("target EvidenceEvent is not allowed"));
    assertThat(problems).anyMatch(p -> p.contains("repeats relationship_id"));
  }

  @Test
  void multiCloudFeedCarriesProviderShapedPagingErrorsAndFaults() {
    Map<String, Object> estate = ConnectionsSamples.multiCloudEstate();
    List<Map<String, Object>> deliveries = castList(estate.get("deliveries"));
    List<String> kinds = new ArrayList<>();
    for (Map<String, Object> delivery : deliveries) {
      kinds.add((String) ConnectionsSamples.observationOf(delivery).get("kind"));
    }

    assertThat(kinds).contains("aws.cloudwatch.metrics", "azure.monitor.metrics", "gcp.monitoring.timeseries",
        "aws.config.resources", "azure.resourcegraph.rows", "gcp.assetinventory.assets");
    assertThat(kinds).anyMatch(k -> k.startsWith("aws.") && k.endsWith(".error"));
    assertThat(kinds).anyMatch(k -> k.startsWith("azure.") && k.endsWith(".error"));
    assertThat(kinds).anyMatch(k -> k.startsWith("gcp.") && k.endsWith(".error"));
    assertThat(deliveries.stream().map(d -> d.get("expect")).distinct().toList())
        .contains("ACCEPTED", "DUPLICATE", "QUARANTINED:DIGEST_MISMATCH", "QUARANTINED:REPLAY_CONFLICT", "QUARANTINED:STALE_SEQUENCE");
    assertThat(cast(estate.get("connection")).get("provider")).isEqualTo("SAMPLE_LOCAL");
  }

  private static Map<String, Object> graph(DataDictionary dictionary) {
    return GraphSample.build(dictionary, ConnectionsSamples.multiCloudEstate());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
