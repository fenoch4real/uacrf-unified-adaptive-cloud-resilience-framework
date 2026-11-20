package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentProfileVersionClient {
  private static final Set<String> RECEIPT_FIELDS =
      Set.of("profile_id", "id", "version_number", "digest", "object_ref", "manifest");
  private static final Set<String> MANIFEST_FIELDS =
      Set.of(
          "instructions_digest",
          "model_ref",
          "knowledge_refs",
          "tools",
          "memory_namespace",
          "evaluation_suite_ref",
          "recovery_rules_ref",
          "runtime_build",
          "data_policy_ref",
          "handoff_ref",
          "limits");
  private static final Set<String> TOOL_FIELDS =
      Set.of("tool_ref", "schema_digest", "effects", "timeout_ms");
  private static final Set<String> LIMIT_FIELDS =
      Set.of("steps", "tokens", "cost_units", "duration_seconds");

  private final URI catalogBase;
  private final OwnerReceiptClient.DelegationProvider delegations;
  private final InternalJsonClient http;

  record WorkloadTool(String toolRef, String schemaDigest, List<String> effects, int timeoutMs) {
    WorkloadTool {
      effects = List.copyOf(effects);
    }
  }

  record WorkloadLimits(int steps, int tokens, int costUnits, int durationSeconds) {}

  record Manifest(
      String instructionsDigest,
      String modelRef,
      List<String> knowledgeRefs,
      List<WorkloadTool> tools,
      String memoryNamespace,
      String evaluationSuiteRef,
      String recoveryRulesRef,
      String runtimeBuild,
      String dataPolicyRef,
      String handoffRef,
      WorkloadLimits limits) {
    Manifest {
      knowledgeRefs = List.copyOf(knowledgeRefs);
      tools = List.copyOf(tools);
    }
  }

  record ActiveVersion(
      String profileId,
      String id,
      long versionNumber,
      String digest,
      String objectRef,
      Manifest manifest) {
    ProfileConfigurationCheck.VersionPins pins() {
      return new ProfileConfigurationCheck.VersionPins(
          id,
          digest,
          manifest.modelRef(),
          manifest.instructionsDigest(),
          manifest.limits().tokens(),
          manifest.limits().costUnits(),
          manifest.limits().durationSeconds());
    }
  }

  AgentProfileVersionClient(
      URI catalogBase,
      boolean allowLoopbackHttp,
      OwnerReceiptClient.DelegationProvider delegations,
      InternalJsonClient http) {
    this.catalogBase = InternalJsonClient.validateBase(catalogBase, allowLoopbackHttp);
    this.delegations = java.util.Objects.requireNonNull(delegations, "delegations");
    this.http = java.util.Objects.requireNonNull(http, "http");
  }

  Map<String, ActiveVersion> fetchActive(
      String tenantId,
      String environmentId,
      Map<String, ProfileConfigurationCheck.VersionPins> expectedPins)
      throws IOException, InterruptedException {
    Map<String, ProfileConfigurationCheck.VersionPins> snapshot =
        ProfileConfigurationCheck.snapshot(expectedPins);
    String token =
        delegations.delegation(
            tenantId, environmentId, "S02", List.of("catalog.agent.read"));
    Map<String, ActiveVersion> active = new HashMap<>();
    for (var entry : snapshot.entrySet()) {
      String profileId = entry.getKey();
      String versionId = entry.getValue().versionId();
      InternalJsonClient.requireId(profileId);
      InternalJsonClient.requireId(versionId);
      Map<?, ?> response =
          http.get(
              catalogBase.resolve("/internal/v1/workload-versions/" + versionId), token);
      ActiveVersion version = parse(response);
      if (!profileId.equals(version.profileId())
          || !versionId.equals(version.id())
          || !entry.getValue().equals(version.pins())) {
        throw new IOException("S02 active workload version does not match the requested profile pins.");
      }
      active.put(profileId, version);
    }
    http.requireBudget();
    return Map.copyOf(active);
  }

  private static ActiveVersion parse(Map<?, ?> response) throws IOException {
    requireFields(response, RECEIPT_FIELDS, "workload version receipt");
    String profileId = text(response.get("profile_id"), 200, "profile_id");
    String id = text(response.get("id"), 200, "id");
    long versionNumber = integer(response.get("version_number"), 1, Long.MAX_VALUE, "version_number");
    String digest = digest(response.get("digest"), "digest");
    String objectRef = text(response.get("object_ref"), 4096, "object_ref");
    if (!(response.get("manifest") instanceof Map<?, ?> manifestValue)) {
      throw new IOException("S02 returned an invalid workload version manifest.");
    }
    Manifest manifest = parseManifest(manifestValue);
    String computedDigest = Sha256.hex(JsonBytes.write(manifestValue));
    if (!digest.equals(computedDigest)) {
      throw new IOException("S02 workload version digest does not match its manifest.");
    }
    return new ActiveVersion(profileId, id, versionNumber, digest, objectRef, manifest);
  }

  private static Manifest parseManifest(Map<?, ?> value) throws IOException {
    requireFields(value, MANIFEST_FIELDS, "workload version manifest");
    String instructionsDigest = digest(value.get("instructions_digest"), "instructions_digest");
    String modelRef = text(value.get("model_ref"), 4096, "model_ref");
    List<String> knowledgeRefs = textList(value.get("knowledge_refs"), 100, 4096, "knowledge_refs");
    List<WorkloadTool> tools = parseTools(value.get("tools"));
    String memoryNamespace = text(value.get("memory_namespace"), 2048, "memory_namespace");
    String evaluationSuiteRef = text(value.get("evaluation_suite_ref"), 4096, "evaluation_suite_ref");
    String recoveryRulesRef = text(value.get("recovery_rules_ref"), 4096, "recovery_rules_ref");
    String runtimeBuild = text(value.get("runtime_build"), 2048, "runtime_build");
    String dataPolicyRef = text(value.get("data_policy_ref"), 4096, "data_policy_ref");
    String handoffRef = text(value.get("handoff_ref"), 4096, "handoff_ref");
    if (!(value.get("limits") instanceof Map<?, ?> limits)) {
      throw new IOException("S02 returned invalid workload version limits.");
    }
    requireFields(limits, LIMIT_FIELDS, "workload version limits");
    return new Manifest(
        instructionsDigest,
        modelRef,
        knowledgeRefs,
        tools,
        memoryNamespace,
        evaluationSuiteRef,
        recoveryRulesRef,
        runtimeBuild,
        dataPolicyRef,
        handoffRef,
        new WorkloadLimits(
            boundedInteger(limits.get("steps"), 1, Integer.MAX_VALUE, "steps"),
            boundedInteger(limits.get("tokens"), 0, Integer.MAX_VALUE, "tokens"),
            boundedInteger(limits.get("cost_units"), 0, Integer.MAX_VALUE, "cost_units"),
            boundedInteger(limits.get("duration_seconds"), 1, Integer.MAX_VALUE, "duration_seconds")));
  }

  private static List<WorkloadTool> parseTools(Object value) throws IOException {
    if (!(value instanceof List<?> entries) || entries.size() > 100) {
      throw new IOException("S02 returned invalid workload version tools.");
    }
    List<WorkloadTool> tools = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> tool)) {
        throw new IOException("S02 returned an invalid workload version tool.");
      }
      requireFields(tool, TOOL_FIELDS, "workload version tool");
      String toolRef = text(tool.get("tool_ref"), 4096, "tool_ref");
      String schemaDigest = digest(tool.get("schema_digest"), "schema_digest");
      List<String> effects = textList(tool.get("effects"), 3, 10, "effects");
      Set<String> uniqueEffects = new HashSet<>(effects);
      if (uniqueEffects.size() != effects.size()
          || !Set.of("READ", "WRITE", "EXECUTE").containsAll(uniqueEffects)) {
        throw new IOException("S02 returned invalid workload tool effects.");
      }
      tools.add(
          new WorkloadTool(
              toolRef,
              schemaDigest,
              effects,
              boundedInteger(tool.get("timeout_ms"), 1, 3_600_000, "timeout_ms")));
    }
    return List.copyOf(tools);
  }

  private static List<String> textList(Object value, int maxItems, int maxLength, String field)
      throws IOException {
    if (!(value instanceof List<?> entries) || entries.size() > maxItems) {
      throw new IOException("S02 returned invalid " + field + ".");
    }
    List<String> result = new ArrayList<>();
    for (Object entry : entries) {
      result.add(text(entry, maxLength, field));
    }
    return List.copyOf(result);
  }

  private static String text(Object value, int maxLength, String field) throws IOException {
    if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
      throw new IOException("S02 returned invalid " + field + ".");
    }
    return text;
  }

  private static String digest(Object value, String field) throws IOException {
    String digest = text(value, 64, field);
    if (!digest.matches("[a-f0-9]{64}")) {
      throw new IOException("S02 returned invalid " + field + ".");
    }
    return digest;
  }

  private static int boundedInteger(Object value, int min, int max, String field) throws IOException {
    long number = integer(value, min, max, field);
    return (int) number;
  }

  private static long integer(Object value, long min, long max, String field) throws IOException {
    long number;
    if (value instanceof Integer integer) {
      number = integer.longValue();
    } else if (value instanceof Long integer) {
      number = integer;
    } else {
      throw new IOException("S02 returned invalid " + field + ".");
    }
    if (number < min || number > max) {
      throw new IOException("S02 returned invalid " + field + ".");
    }
    return number;
  }

  private static void requireFields(Map<?, ?> value, Set<String> required, String field)
      throws IOException {
    if (value.size() != required.size()
        || !value.keySet().stream().allMatch(key -> key instanceof String name && required.contains(name))) {
      throw new IOException("S02 returned an invalid " + field + ".");
    }
  }

  private static final class JsonBytes {
    private static final tools.jackson.databind.ObjectMapper JSON =
        tools.jackson.databind.json.JsonMapper.builder().build();

    private JsonBytes() {}

    private static byte[] write(Object value) throws IOException {
      try {
        return JSON.writeValueAsBytes(value);
      } catch (tools.jackson.core.JacksonException error) {
        throw new IOException("Unable to verify the S02 workload version digest.", error);
      }
    }
  }
}
