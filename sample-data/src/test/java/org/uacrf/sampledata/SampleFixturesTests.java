package org.uacrf.sampledata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  void historicalMermaidRelationshipExamplesUseDictionaryEndpoints() throws IOException {
    DataDictionary dictionary = DataDictionary.load(SampleFixtures.DICTIONARY);
    Path mermaid = SampleFixtures.DICTIONARY.resolveSibling("mermaid");
    Pattern nodePattern = Pattern.compile("^\\s*(N\\d+)\\[\"([^<\"]+)<br/>", Pattern.MULTILINE);
    Pattern edgePattern = Pattern.compile("^\\s*(N\\d+)\\s*-->\\|([^|]+)\\|\\s*(N\\d+)", Pattern.MULTILINE);
    String previews = Files.readString(mermaid.resolve("README.md"));
    for (String file :
        List.of("02_evidence_to_outcome.mmd", "03_failure_propagation.mmd", "04_recovery_propagation.mmd")) {
      String text = Files.readString(mermaid.resolve(file));
      if (!file.equals("03_failure_propagation.mmd")) {
        assertThat(previews).as("%s preview", file).contains("```mermaid\n" + text + "```");
      }
      Map<String, String> nodes = new java.util.HashMap<>();
      Matcher nodeMatcher = nodePattern.matcher(text);
      while (nodeMatcher.find()) {
        nodes.put(nodeMatcher.group(1), nodeMatcher.group(2));
      }
      Matcher edgeMatcher = edgePattern.matcher(text);
      int edges = 0;
      while (edgeMatcher.find()) {
        edges++;
        DataDictionary.RelationshipDef relationship = dictionary.relationships.get(edgeMatcher.group(2));
        assertThat(relationship).as("%s: %s", file, edgeMatcher.group()).isNotNull();
        assertThat(dictionary.resolve(relationship.source()))
            .as("%s: %s source", file, edgeMatcher.group())
            .contains(nodes.get(edgeMatcher.group(1)));
        assertThat(dictionary.resolve(relationship.target()))
            .as("%s: %s target", file, edgeMatcher.group())
            .contains(nodes.get(edgeMatcher.group(3)));
      }
      assertThat(edges).as("%s relationship examples", file).isPositive();
    }
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

  @Test
  void sampleInventoryDeliveriesMatchCollectorObservationsAndKeepSimulatedPagesSeparate() {
    Map<String, Object> estate = ConnectionsSamples.multiCloudEstate();
    List<Map<String, Object>> deliveries = castList(estate.get("deliveries"));
    Map<String, List<String>> expectedFields =
        Map.of(
            "aws.config.resources",
            List.of("resourceId", "resourceType", "awsRegion", "configurationItemCaptureTime"),
            "azure.resourcegraph.rows",
            List.of("id", "name", "type", "location", "subscriptionId", "resourceGroup"),
            "gcp.assetinventory.assets",
            List.of("name", "assetType", "updateTime"));
    for (Map.Entry<String, List<String>> entry : expectedFields.entrySet()) {
      List<Map<String, Object>> observations =
          deliveries.stream()
              .map(ConnectionsSamples::observationOf)
              .filter(observation -> entry.getKey().equals(observation.get("kind")))
              .toList();
      assertThat(observations).as(entry.getKey()).isNotEmpty();
      for (Map<String, Object> observation : observations) {
        Map<String, Object> payload =
            MAPPER.readValue((String) observation.get("payload"), new TypeReference<>() {});
        assertThat(payload.keySet()).containsExactlyInAnyOrderElementsOf(entry.getValue());
        if (entry.getKey().equals("aws.config.resources")) {
          assertThat(payload.get("configurationItemCaptureTime")).isEqualTo(observation.get("observed_at"));
        }
      }
    }

    Map<String, Object> pages = cast(estate.get("simulated_inventory_response_pages"));
    assertThat(pages.keySet()).containsExactlyInAnyOrder("aws", "azure", "gcp");
    assertThat(Json.compact(pages)).contains("nextToken", "$skipToken", "nextPageToken");
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
