package org.uacrf.sampledata;

import static org.uacrf.sampledata.Json.l;
import static org.uacrf.sampledata.Json.m;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A synthetic "Meridian Retail" estate that uses every node label and relationship type of the
 * Technical Approach data dictionary once or more. It is a conformance fixture, not provider
 * inventory: every record is marked SYNTHETIC_FIXTURE, and the Authorization, Execution and agent
 * signature records are deliberately void so they can never be mistaken for executable permits.
 */
final class GraphSample {
  static final String TENANT = "ten-meridian";
  static final String ENVIRONMENT = "env-prod";
  static final String REVISION = "rev-0001";
  static final String OBSERVED = "2026-11-12T09:30:00Z";
  static final String EXPIRES = "2026-12-12T09:30:00Z";
  static final String VOID_AFTER = "2026-11-12T08:00:00Z";

  private static final Map<String, Object> FOREIGN_KEYS = new LinkedHashMap<>();
  private static final Set<String> NUMBERS = Set.of(
      "sequence", "traffic_share", "traffic_weight", "capacity_ratio", "ttl", "magnitude", "request_rate", "volume",
      "timeout", "target", "error_budget", "contribution_status_weight");
  private static final Set<String> BOOLEANS = Set.of(
      "production", "independent_context_flag", "mandatory", "required", "active", "certified");
  private static final Set<String> LISTS = Set.of(
      "reason_codes", "evidence_refs", "zones", "ordered_eligible", "allowed_evidence", "tool_allowlist",
      "vulnerability_flags", "impact_dimensions", "rejected_with_reasons");

  static {
    FOREIGN_KEYS.put("tenant_id", TENANT);
    FOREIGN_KEYS.put("environment_id", ENVIRONMENT);
    FOREIGN_KEYS.put("graph_revision", REVISION);
    FOREIGN_KEYS.put("service_id", "svc-checkout-web");
    FOREIGN_KEYS.put("owner_team_id", "team-platform");
    FOREIGN_KEYS.put("component_id", "comp-checkout-web-ui");
    FOREIGN_KEYS.put("deployment_id", "dep-checkout-web-42");
    FOREIGN_KEYS.put("cluster_id", "cl-aws");
    FOREIGN_KEYS.put("account_id", "acct-aws");
    FOREIGN_KEYS.put("region_id", "reg-aws");
    FOREIGN_KEYS.put("failure_domain_id", "fd-aws-1a");
    FOREIGN_KEYS.put("model_id", "model-ranker");
    FOREIGN_KEYS.put("model_version_id", "mv-31");
    FOREIGN_KEYS.put("action_id", "act-checkout-rollback");
    FOREIGN_KEYS.put("authorization_id", "auth-sample-void");
    FOREIGN_KEYS.put("validation_plan_id", "vp-checkout-rollback");
    FOREIGN_KEYS.put("plan_id", "vp-checkout-rollback");
    FOREIGN_KEYS.put("journey_id", "j-place-order");
    FOREIGN_KEYS.put("case_id", "case-checkout-0001");
    FOREIGN_KEYS.put("assignment_id", "asg-ranker-review");
    FOREIGN_KEYS.put("event_id", "ev-aws-checkout");
    FOREIGN_KEYS.put("qualification_id", "eq-aws-checkout");
    FOREIGN_KEYS.put("source_model_id", "model-ranker");
    FOREIGN_KEYS.put("target_id", "mv-30");
    FOREIGN_KEYS.put("subject_id", "svc-checkout-web");
    FOREIGN_KEYS.put("provider_id", "xp-card-processor");
    FOREIGN_KEYS.put("owner", "team-platform");
  }

  private final List<Map<String, Object>> nodes = new ArrayList<>();
  private final List<Map<String, Object>> relationships = new ArrayList<>();
  private final DataDictionary dictionary;

  private GraphSample(DataDictionary dictionary) {
    this.dictionary = dictionary;
  }

  static Map<String, Object> build(DataDictionary dictionary, Map<String, Object> multiCloud) {
    GraphSample graph = new GraphSample(dictionary);
    graph.nodes(multiCloud);
    graph.relationships();
    return m(
        "fixture", "sample-multicloud-graph",
        "classification", "SYNTHETIC_FIXTURE",
        "notice", "Fictional Meridian Retail estate for conformance and UI work. Never provider inventory and never accepted evidence.",
        "tenant_id", TENANT,
        "dictionary", m("nodes", dictionary.nodes.size(), "relationships", dictionary.relationships.size()),
        "nodes", graph.nodes,
        "relationships", graph.relationships);
  }

  // ---------------------------------------------------------------- nodes

  @SuppressWarnings("unchecked")
  private void nodes(Map<String, Object> multiCloud) {
    Map<String, Map<String, Object>> firstByProvider = new LinkedHashMap<>();
    for (Map<String, Object> delivery : (List<Map<String, Object>>) multiCloud.get("deliveries")) {
      Map<String, Object> observation = ConnectionsSamples.observationOf(delivery);
      String provider = ((String) observation.get("kind")).split("\\.")[0];
      if ("ACCEPTED".equals(delivery.get("expect"))) {
        firstByProvider.putIfAbsent(provider, observation);
      }
    }
    String conn = "s03://connections/conn-sample-multicloud/observations/";

    node("Tenant", TENANT, "name", "Meridian Retail (sample)", "status", "ACTIVE");
    node("Environment", ENVIRONMENT, "class", "PRODUCTION", "production", true, "authority_tier", "TIER_1");
    for (String p : List.of("aws", "azure", "gcp")) {
      Map<String, Object> obs = firstByProvider.get(p);
      String id = "-" + p + "-checkout";
      node("EvidenceSource", "es-" + p, "source_type", "METRICS", "provider", p, "authority_class", "PROVIDER_NATIVE",
          "owner", "team-platform", "status", "SAMPLE_LOCAL");
      node("EvidenceEvent", "ev" + id, "occurred_at", ConnectionsSamples.START.toString(),
          "ingested_at", OBSERVED, "source_id", "es-" + p, "subject_id", subject(p), "signal_type", "latency_p99",
          "value", 212.4, "unit", "ms", "raw_reference", conn + obs.get("id"), "digest", obs.get("payload_digest"),
          "qualification_id", "eq" + id);
      node("EvidenceQualification", "eq" + id, "event_id", "ev" + id, "qualification_state", "SAMPLE_ONLY",
          "reason_codes", l("SYNTHETIC_FIXTURE"), "ruleset_version", "sample-1", "qualified_at", OBSERVED,
          "qualifier_id", "sample-qualifier", "expires_at", EXPIRES);
      node("CloudProvider", "cp-" + p, "name", p.toUpperCase(), "provider_code", p, "assurance_status", "UNASSURED");
      node("Account", "acct-" + p, "provider", p, "native_id", nativeAccount(p));
      node("Region", "reg-" + p, "provider", p, "native_id", ConnectionsSamples.REGION.get(p), "jurisdiction", "EU");
      node("AvailabilityZone", "az-" + p, "region_id", "reg-" + p, "provider", p, "native_id", ConnectionsSamples.REGION.get(p) + "-1");
      node("FailureDomain", "fd-" + p, "domain_type", "ZONE", "name", p + " primary zone", "scope", "REGIONAL");
      node("Cluster", "cl-" + p, "provider", p, "account_id", "acct-" + p, "region_id", "reg-" + p, "cluster_type", "KUBERNETES");
      node("ComputeNode", "cn-" + p, "cluster_id", "cl-" + p, "provider", p, "native_id", "node-" + p + "-01",
          "region", ConnectionsSamples.REGION.get(p), "zone", ConnectionsSamples.REGION.get(p) + "-1");
      node("CloudResource", "res-" + p, "provider", p, "account_id", "acct-" + p, "native_id", "lb-" + p + "-01",
          "resource_type", "LOAD_BALANCER", "region", ConnectionsSamples.REGION.get(p),
          "zone", ConnectionsSamples.REGION.get(p) + "-1");
      node("Network", "net-" + p, "provider", p, "account_id", "acct-" + p, "native_id", "vnet-" + p,
          "network_type", "VPC", "region", ConnectionsSamples.REGION.get(p));
    }
    node("DNSService", "dns-route53", "provider", "aws", "scope", "GLOBAL", "zones", l("meridian.example"),
        "failure_domain_id", "fd-aws");
    for (String s : List.of("checkout-web", "payments-api", "orders-service", "identity-gateway",
        "notifications-worker", "inventory-service", "recommendation-ranker")) {
      node("Service", "svc-" + s, "name", s, "domain", "retail", "criticality", s.startsWith("checkout") ? "TIER_1" : "TIER_2",
          "lifecycle", "ACTIVE", "catalog_version", "sample-1");
    }
    node("ApplicationComponent", "comp-checkout-web-ui", "component_type", "WEB", "version", "42.0.0", "lifecycle", "ACTIVE");
    node("Deployment", "dep-checkout-web-42", "version", "42.0.0", "provider", "aws", "status", "SUCCEEDED",
        "change_id", "chg-sample-0042");
    node("API", "api-place-order", "service_id", "svc-orders-service", "protocol", "HTTPS", "operation", "POST /orders",
        "classification", "INTERNAL", "lifecycle", "ACTIVE");
    node("Queue", "q-orders-events", "provider", "aws", "native_id", "orders-events", "delivery_semantics", "AT_LEAST_ONCE",
        "region", "eu-west-1");
    node("Database", "db-orders", "provider", "aws", "native_id", "orders-db", "database_type", "POSTGRES",
        "classification", "CONFIDENTIAL", "region", "eu-west-1", "durability_tier", "MULTI_AZ");
    node("DataStore", "ds-inventory", "provider", "gcp", "native_id", "inventory-store", "store_type", "DOCUMENT",
        "classification", "INTERNAL", "region", "europe-west1", "durability_tier", "REGIONAL");
    node("ExternalDependency", "ext-card-processor", "provider_id", "xp-card-processor", "dependency_type", "PAYMENT_GATEWAY",
        "criticality", "TIER_1", "contract_ref", "sample-contract-1", "status", "ACTIVE");
    node("Team", "team-platform", "name", "Platform (sample)", "on_call_ref", "sample-oncall-platform", "status", "ACTIVE");
    node("Team", "team-payments", "name", "Payments (sample)", "on_call_ref", "sample-oncall-payments", "status", "ACTIVE");
    node("Identity", "id-checkout-sa", "identity_type", "SERVICE_ACCOUNT", "authority", "idp-entra-sample",
        "native_subject", "checkout-web@sample", "privilege_tier", "STANDARD");
    node("IdentityProvider", "idp-entra-sample", "provider", "azure", "scope", "TENANT", "region", "westeurope",
        "independence_domain", "azure-identity", "status", "ACTIVE");
    node("EncryptionKey", "key-aws-kms-orders", "authority", "aws-kms", "purpose", "DATA_AT_REST", "region", "eu-west-1",
        "rotation_status", "CURRENT", "failure_domain_id", "fd-aws");
    node("Control", "ctrl-pci-dss-6-4", "framework", "PCI-DSS", "control_code", "6.4", "version", "4.0", "status", "ACTIVE",
        "measurement_ref", "sample-measurement-pci-6-4");
    node("Policy", "pol-change-freeze", "policy_type", "CHANGE_FREEZE", "version", "1", "status", "ACTIVE");
    node("SLO", "slo-checkout-latency", "sli_query", "p99(checkout.latency)", "target", 0.999, "window", "30d",
        "error_budget", 0.001, "breach_behavior", "FREEZE_CHANGES", "measurement_ref", "sample-measurement-checkout");
    node("AIModel", "model-ranker", "service_id", "svc-recommendation-ranker", "purpose", "RANKING", "risk_class", "LIMITED",
        "lifecycle", "ACTIVE");
    node("ModelVersion", "mv-30", "version", "3.0", "approval_status", "APPROVED", "lifecycle", "RETIRED");
    node("ModelVersion", "mv-31", "version", "3.1", "approval_status", "APPROVED", "lifecycle", "ACTIVE");
    node("ModelEndpoint", "me-ranker-prod", "endpoint", "https://ranker.sample.invalid/v1", "traffic_weight", 1.0,
        "status", "ACTIVE");
    node("FeatureStore", "fs-ranker-features", "provider", "gcp", "native_id", "ranker-features", "classification", "INTERNAL",
        "status", "ACTIVE");
    node("Dataset", "dset-popularity", "classification", "INTERNAL", "owner", "team-platform", "retention_class", "90D",
        "lineage_ref", "sample-lineage-popularity");
    node("ModelFallback", "mf-ranker-popularity", "capacity", 0.5, "certification_status", "SAMPLE_UNCERTIFIED",
        "tested_at", OBSERVED, "expires_at", VOID_AFTER);
    node("ExternalProvider", "xp-card-processor", "name", "Card processor (sample)", "service_type", "PAYMENTS",
        "criticality", "TIER_1", "assurance_status", "UNASSURED");
    node("Location", "loc-eu-west-1-dublin", "provider", "aws", "location_type", "METRO", "jurisdiction", "IE");
    node("CustomerJourney", "j-place-order", "name", "Place an order", "owner", "team-payments",
        "start_criteria", "cart submitted", "completion_criteria", "order confirmed", "operating_window", "24x7",
        "criticality", "TIER_1", "version", "1");
    node("JourneyStep", "step-submit-cart", "sequence", 1, "success_condition", "cart accepted", "timeout", 3000,
        "alternative_path", "retry", "failure_behavior", "SHOW_ERROR");
    node("JourneyStep", "step-pay", "sequence", 2, "success_condition", "payment authorised", "timeout", 5000,
        "alternative_path", "alternate card", "failure_behavior", "SHOW_ERROR");
    node("CustomerSegment", "seg-eu-retail", "definition", "EU retail consumers", "geography", "EU",
        "commitment", "99.9% checkout availability", "vulnerability_flags", l(), "measurement_method", "synthetic probe");
    node("EssentialFunction", "ef-order-capture", "name", "Order capture", "priority", 1,
        "maximum_tolerable_outage", "PT30M", "impact_dimensions", l("REVENUE", "CUSTOMER"));
    node("Impact", "imp-incomplete-orders", "impact_type", "REVENUE", "affected_scope", "svc-checkout-web",
        "time_window", "PT30M", "magnitude", 1.0, "unit", "SAMPLE_UNITS", "status", "ESTIMATED", "method", "sample-model");
    node("Outcome", "out-revenue-at-risk", "outcome_type", "REVENUE_AT_RISK", "owner", "team-payments", "status", "ESTIMATED",
        "measurement_method", "sample-model");
    node("Runbook", "rb-checkout-rollback", "action_type", "ROLLBACK", "version", "1", "certification_status", "SAMPLE_UNCERTIFIED",
        "tested_at", OBSERVED, "expires_at", VOID_AFTER);
    node("RecoveryPath", "rp-checkout-rollback-prev", "scenario", "BAD_DEPLOY", "certification_status", "SAMPLE_UNCERTIFIED",
        "witness_ref", "sample-witness", "tested_at", OBSERVED, "expires_at", VOID_AFTER);
    node("CandidateAction", "act-checkout-rollback", "action_type", "ROLLBACK", "target_selector", "dep-checkout-web-42",
        "bounds", "one deployment", "reversibility", "REVERSIBLE", "expected_effect", "restore p99 latency");
    node("Authorization", "auth-sample-void", "policy_version", "sample-1", "nonce", "sample-nonce-not-valid",
        "audience", "sample-void", "issued_at", "2026-11-12T07:00:00Z", "expires_at", VOID_AFTER, "status", "VOID_SAMPLE");
    node("Execution", "exec-sample-simulated", "provider_request_id", "sample-request", "idempotency_key", "sample-idem-1",
        "state", "SIMULATED", "started_at", OBSERVED, "completed_at", OBSERVED, "result_ref", "sample-result");
    node("ValidationPlan", "vp-checkout-rollback", "version", "1", "stable_window", "PT10M",
        "compensation_policy", "NONE", "status", "SAMPLE");
    node("ValidationResult", "vr-checkout-latency", "check_id", "latency-p99", "state", "SIMULATED", "observed_value", 198.0,
        "evidence_ref", "ev-aws-checkout", "completed_at", OBSERVED);
    node("GraphRevision", REVISION, "parent_revision", "rev-0000", "reconciliation_state", "SAMPLE", "created_at", OBSERVED);
    node("DecisionEnvelope", "dec-sample-0001", "context_hash", digest("context"), "policy_digest", digest("policy"),
        "evidence_refs", l("ev-aws-checkout"), "decision", "NO_ACTION_SAMPLE", "state", "SAMPLE", "created_at", OBSERVED);
    node("AgentAnalysisCase", "case-checkout-0001", "manifest_digest", digest("manifest"), "policy_version", "sample-1",
        "objective", "assess checkout latency", "deadline", EXPIRES, "state", "SAMPLE");
    node("AgentAssignment", "asg-ranker-review", "role", "ANALYST", "capability", "read-only", "independent_context_flag", true,
        "allowed_evidence", l("ev-gcp-checkout"), "tool_allowlist", l(), "expires_at", VOID_AFTER);
    node("AgentProposal", "prop-rollback-1", "assignment_id", "asg-ranker-review", "producer", "sample-agent",
        "prompt_digest", digest("prompt"), "evidence_refs", l("ev-aws-checkout"), "action", "act-checkout-rollback",
        "rationale", "Sample rationale; not an executable proposal.", "signature", "sample-unsigned-fixture");
    node("AgentDissentRecord", "dis-identity-1", "producer", "sample-agent", "disputed_claim", "latency is caused by deploy",
        "category", "CAUSALITY", "severity", "LOW", "evidence_refs", l("ev-azure-checkout"),
        "rationale", "Sample dissent.", "required_resolution", "NONE", "signature", "sample-unsigned-fixture");
    node("ArbitrationRecord", "arb-case-1", "rule_bundle_version", "sample-1", "ordered_eligible", l("prop-rollback-1"),
        "rejected_with_reasons", l(), "selected_proposal", "prop-rollback-1", "disposition", "SAMPLE_ONLY", "dissent", "dis-identity-1");
  }

  private static String subject(String provider) {
    return switch (provider) {
      case "aws" -> "svc-checkout-web";
      case "azure" -> "svc-identity-gateway";
      default -> "svc-recommendation-ranker";
    };
  }

  private static String nativeAccount(String provider) {
    return switch (provider) {
      case "aws" -> ConnectionsSamples.AWS_ACCOUNT;
      case "azure" -> ConnectionsSamples.AZURE_SUBSCRIPTION;
      default -> ConnectionsSamples.GCP_PROJECT;
    };
  }

  private static String digest(String text) {
    return Json.digest("sample-graph:" + text);
  }

  private void node(String label, String id, Object... overrides) {
    DataDictionary.NodeDef def = dictionary.nodes.get(label);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(def.idProperty(), id);
    for (int i = 0; i < overrides.length; i += 2) {
      properties.put((String) overrides[i], overrides[i + 1]);
    }
    for (String key : def.required()) {
      properties.computeIfAbsent(key, k -> fill(k, id));
    }
    String environment = label.equals("Tenant") ? null : ENVIRONMENT;
    Map<String, Object> common = new LinkedHashMap<>();
    common.put("tenant_id", TENANT);
    common.put("canonical_id", "uacrf:" + TENANT + ":" + label.toLowerCase() + ":" + id);
    common.put("source_kind", GraphValidator.SYNTHETIC);
    common.put("source_id", "sample-graph-fixture");
    common.put("provenance", "sample-fixture");
    common.put("confidence", 0.9);
    common.put("first_observed_at", ConnectionsSamples.START.toString());
    common.put("last_observed_at", OBSERVED);
    common.put("expires_at", EXPIRES);
    common.put("lifecycle", "ACTIVE");
    common.put("schema_version", "1");
    common.put("environment_id", environment);
    common.put("graph_revision", REVISION);
    common.put("qualification_state", "SAMPLE_ONLY");
    common.put("valid_from", ConnectionsSamples.START.toString());
    common.put("valid_to", null);
    for (String key : def.common()) {
      properties.putIfAbsent(key, common.get(key));
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("label", label);
    entry.put("id", id);
    entry.put("properties", properties);
    nodes.add(entry);
  }

  private static Object fill(String key, String id) {
    if (FOREIGN_KEYS.containsKey(key)) {
      return FOREIGN_KEYS.get(key);
    }
    if (LISTS.contains(key)) {
      return l();
    }
    if (BOOLEANS.contains(key)) {
      return false;
    }
    if (NUMBERS.contains(key)) {
      return 1;
    }
    if (key.contains("digest")) {
      return digest(id + key);
    }
    if (key.equals("confidence")) {
      return 0.9;
    }
    if (key.endsWith("_at") || key.equals("valid_until") || key.equals("expiry") || key.startsWith("effective_")) {
      return OBSERVED;
    }
    if (key.contains("status") || key.endsWith("state")) {
      return "SAMPLE";
    }
    return "sample-" + key;
  }

  // ---------------------------------------------------------------- relationships

  private void relationships() {
    rel("EMITS", "es-aws", "ev-aws-checkout");
    rel("EMITS", "es-azure", "ev-azure-checkout");
    rel("EMITS", "es-gcp", "ev-gcp-checkout");
    rel("DESCRIBES_STATE_OF", "ev-aws-checkout", "svc-checkout-web");
    rel("DESCRIBES_STATE_OF", "ev-azure-checkout", "svc-identity-gateway");
    rel("DESCRIBES_STATE_OF", "ev-gcp-checkout", "svc-recommendation-ranker");
    rel("QUALIFIED_BY", "ev-aws-checkout", "eq-aws-checkout");
    rel("QUALIFIED_BY", "ev-azure-checkout", "eq-azure-checkout");
    rel("QUALIFIED_BY", "ev-gcp-checkout", "eq-gcp-checkout");
    rel("CORRELATED_WITH", "ev-aws-checkout", "ev-gcp-checkout");
    rel("SUPPORTED_BY_EVIDENCE", "dec-sample-0001", "ev-aws-checkout");
    rel("CONTRADICTED_BY", "imp-incomplete-orders", "ev-azure-checkout");
    rel("SUPERSEDES", "mv-31", "mv-30");
    rel("DEPENDS_ON", "svc-checkout-web", "svc-payments-api");
    rel("ROUTES_TO", "api-place-order", "svc-orders-service");
    rel("CALLS", "svc-checkout-web", "api-place-order");
    rel("READS_FROM", "svc-inventory-service", "ds-inventory");
    rel("WRITES_TO", "svc-orders-service", "db-orders");
    rel("PUBLISHES_TO", "svc-orders-service", "q-orders-events");
    rel("CONSUMES_FROM", "svc-notifications-worker", "q-orders-events");
    rel("RUNS_ON", "dep-checkout-web-42", "res-aws");
    rel("DEPLOYED_IN", "svc-checkout-web", ENVIRONMENT);
    rel("LOCATED_IN", "svc-checkout-web", "loc-eu-west-1-dublin");
    rel("INSTANCE_OF", "res-aws", "dep-checkout-web-42");
    rel("IMPLEMENTS", "dep-checkout-web-42", "svc-checkout-web");
    rel("PART_OF", "cl-aws", "reg-aws");
    rel("MEMBER_OF_FAILURE_DOMAIN", "res-aws", "fd-aws");
    rel("REPLICATES_TO", "db-orders", "ds-inventory");
    rel("FALLBACK_TO", "me-ranker-prod", "mf-ranker-popularity");
    rel("USES_MODEL", "svc-recommendation-ranker", "me-ranker-prod");
    rel("SERVES_MODEL", "me-ranker-prod", "mv-31");
    rel("USES_FEATURES_FROM", "me-ranker-prod", "fs-ranker-features");
    rel("TRAINED_FROM", "mv-31", "dset-popularity");
    rel("USES_DATASET", "mv-31", "dset-popularity");
    rel("USES_IDENTITY", "svc-identity-gateway", "idp-entra-sample");
    rel("USES_KEY", "db-orders", "key-aws-kms-orders");
    rel("USES_DNS", "svc-checkout-web", "dns-route53");
    rel("OWNED_BY", "svc-checkout-web", "team-platform");
    rel("PROTECTED_BY", "svc-payments-api", "ctrl-pci-dss-6-4");
    rel("PROVIDED_BY", "ext-card-processor", "xp-card-processor");
    rel("SUPPORTS_STEP", "svc-payments-api", "step-pay");
    rel("PART_OF_JOURNEY", "step-pay", "j-place-order");
    rel("ENABLES_FUNCTION", "j-place-order", "ef-order-capture");
    rel("SERVES_SEGMENT", "j-place-order", "seg-eu-retail");
    rel("GOVERNED_BY_SLO", "svc-checkout-web", "slo-checkout-latency");
    rel("AFFECTS", "svc-checkout-web", "imp-incomplete-orders");
    rel("CONTRIBUTES_TO", "imp-incomplete-orders", "out-revenue-at-risk");
    rel("MEASURED_BY", "imp-incomplete-orders", "slo-checkout-latency");
    rel("FAILURE_PROPAGATES_TO", "svc-recommendation-ranker", "svc-checkout-web");
    rel("TARGETED_BY_ACTION", "dep-checkout-web-42", "act-checkout-rollback");
    rel("AUTHORIZED_BY", "act-checkout-rollback", "auth-sample-void");
    rel("EXECUTES", "auth-sample-void", "exec-sample-simulated");
    rel("USES_RUNBOOK", "act-checkout-rollback", "rb-checkout-rollback");
    rel("USES_RECOVERY_PATH", "act-checkout-rollback", "rp-checkout-rollback-prev");
    rel("VALIDATED_BY", "act-checkout-rollback", "vp-checkout-rollback");
    rel("BOUND_TO_REVISION", "dec-sample-0001", REVISION);
    rel("RECORDED_IN", "prop-rollback-1", "dec-sample-0001");
    rel("ASSIGNED_TO", "asg-ranker-review", "case-checkout-0001");
    rel("PRODUCES_PROPOSAL", "asg-ranker-review", "prop-rollback-1");
    rel("RAISES_DISSENT", "asg-ranker-review", "dis-identity-1");
    rel("ARBITRATED_BY", "case-checkout-0001", "arb-case-1");
  }

  private void rel(String type, String source, String target) {
    DataDictionary.RelationshipDef def = dictionary.relationships.get(type);
    String relationshipId = "rel-" + String.format("%03d", relationships.size() + 1);
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String key : def.properties()) {
      properties.put(key, relationshipValue(key, source, target));
    }
    Map<String, Object> common = new LinkedHashMap<>();
    common.put("tenant_id", TENANT);
    common.put("relationship_id", relationshipId);
    common.put("source_kind", GraphValidator.SYNTHETIC);
    common.put("source_id", "sample-graph-fixture");
    common.put("provenance", "sample-fixture");
    common.put("confidence", 0.9);
    common.put("valid_from", ConnectionsSamples.START.toString());
    common.put("valid_to", null);
    common.put("observed_at", OBSERVED);
    common.put("expires_at", EXPIRES);
    common.put("graph_revision", REVISION);
    common.put("schema_version", "1");
    common.put("environment_id", ENVIRONMENT);
    common.put("qualification_state", "SAMPLE_ONLY");
    common.put("source_version", "sample-1");
    for (String key : def.common()) {
      properties.put(key, common.get(key));
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", relationshipId);
    entry.put("type", type);
    entry.put("source", source);
    entry.put("target", target);
    entry.put("properties", properties);
    relationships.add(entry);
  }

  private static Object relationshipValue(String key, String source, String target) {
    return switch (key) {
      case "prior_id" -> target;
      case "evidence_ref" -> "ev-aws-checkout";
      case "evidence_digest" -> digest("evidence");
      case "deployment_id" -> target;
      case "actor_id" -> "sample-actor";
      case "dependency_id", "route_id" -> "sample-" + source + "-" + target;
      case "active", "required", "mandatory", "certified" -> true;
      case "effective_to" -> null;
      case "retry_policy", "circuit_breaker" -> "sample-default";
      case "result" -> "SIMULATED";
      case "scope_digest", "subgraph_digest", "artifact_digest", "grant_digest" -> digest(key + source + target);
      case "context_hash" -> digest("context");
      case "idempotency_key" -> "sample-idem-1";
      case "nonce" , "nonce_status" -> "SAMPLE_VOID";
      case "expiry" -> GraphSample.VOID_AFTER;
      default -> fill(key, source + target);
    };
  }
}
