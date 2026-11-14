package org.uacrf.sampledata;

import static org.uacrf.sampledata.Json.compact;
import static org.uacrf.sampledata.Json.digest;
import static org.uacrf.sampledata.Json.l;
import static org.uacrf.sampledata.Json.m;
import static org.uacrf.sampledata.Json.round;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds the S03 intake fixtures. Everything is synthetic. The multi-cloud payloads are SHAPED like
 * AWS CloudWatch/Config, Azure Monitor/Resource Graph and Google Cloud Monitoring/Asset Inventory
 * responses but are not captured provider responses, and every connection is SAMPLE_LOCAL: the AWS,
 * AZURE and GCP connection types stay UNACCEPTED until provider conformance is proven.
 */
final class ConnectionsSamples {
  static final Instant START = Instant.parse("2026-11-12T09:00:00Z");
  static final Duration STEP = Duration.ofMinutes(5);
  static final int SAMPLES = 12;

  private ConnectionsSamples() {}

  record Service(String id, double latency, double errors, int replicas) {}

  // ---------------------------------------------------------------- local estate

  static Map<String, Object> localEstate() {
    Random rng = new Random(20261112);
    Map<String, Service> services = new LinkedHashMap<>();
    services.put("payments-api", new Service("svc-payments-api", 120, 0.002, 6));
    services.put("checkout-web", new Service("svc-checkout-web", 210, 0.004, 8));
    services.put("identity-gateway", new Service("svc-identity-gateway", 85, 0.001, 4));
    services.put("orders-service", new Service("svc-orders-service", 150, 0.003, 6));
    services.put("inventory-service", new Service("svc-inventory-service", 95, 0.002, 4));
    services.put("notifications-worker", new Service("svc-notifications-worker", 310, 0.006, 3));

    List<Map<String, Object>> deliveries = new ArrayList<>();
    for (int index = 1; index <= SAMPLES; index++) {
      for (String name : services.keySet()) {
        int sequence = index;
        if (name.equals("inventory-service") && index >= 6) {
          sequence = index + 1; // collector skipped sequence 6 (gap, still accepted)
        }
        if (name.equals("notifications-worker") && index == 5) {
          continue; // delivered late below
        }
        deliveries.add(
            delivery("ACCEPTED", localObservation(name, sequence, "service.health",
                localHealth(rng, services.get(name), name, index), 0, localRef(name))));
      }
      if (index == 4) {
        deliveries.add(
            delivery("ACCEPTED", localObservation("payments-api-deploys", 1, "deployment.event",
                compact(m("version", "2026.11.3", "change", "rollout-complete")), 30,
                localRef("payments-api") + "/deploys")));
      }
    }

    Map<String, Object> first = observationOf(deliveries.get(0));
    deliveries.add(delivery("DUPLICATE", new LinkedHashMap<>(first)));
    deliveries.add(
        delivery("QUARANTINED:STALE_SEQUENCE", localObservation("notifications-worker", 5, "service.health",
            localHealth(rng, services.get("notifications-worker"), "notifications-worker", 5), 0,
            localRef("notifications-worker"))));

    Map<String, Object> tampered = new LinkedHashMap<>(observationOf(deliveries.get(1)));
    tampered.put("id", "obs-checkout-web-tampered");
    tampered.put("sequence", (long) SAMPLES + 5);
    String payload = (String) tampered.get("payload");
    int zero = payload.indexOf("0.");
    tampered.put("payload", payload.substring(0, zero) + "0.9" + payload.substring(zero + 2));
    deliveries.add(delivery("QUARANTINED:DIGEST_MISMATCH", tampered));

    String conflictPayload = compact(m("availability", 1.0, "p95_latency_ms", 1.0, "error_rate", 0.0,
        "replicas_ready", 6, "replicas_desired", 6));
    Map<String, Object> conflict = new LinkedHashMap<>(first);
    conflict.put("payload", conflictPayload);
    conflict.put("payload_digest", digest(conflictPayload));
    deliveries.add(delivery("QUARANTINED:REPLAY_CONFLICT", conflict));

    List<Object> bindings = new ArrayList<>();
    for (Map.Entry<String, Service> entry : services.entrySet()) {
      bindings.add(binding("bind-" + entry.getKey(), localRef(entry.getKey()), entry.getValue().id()));
    }
    bindings.add(binding("bind-payments-api-deploys", localRef("payments-api") + "/deploys", "svc-payments-api"));

    return m(
        "_label", "SAMPLE DATA - synthetic, not real telemetry. Regenerate with the sample-data-tools module.",
        "connection", m("id", "conn-sample-eu-west", "name", "Sample EU-West production estate",
            "provider", "SAMPLE_LOCAL", "read_only_identity_ref", "sample/readonly-collector",
            "owner_id", "owner-platform", "expected_revision", 0),
        "bindings", bindings,
        "deliveries", deliveries);
  }

  private static String localRef(String name) {
    return "sample:eu-west/prod/" + name;
  }

  private static String localHealth(Random rng, Service service, String name, int index) {
    double latency = service.latency() * uniform(rng, 0.92, 1.08);
    double errors = service.errors() * uniform(rng, 0.6, 1.4);
    int ready = service.replicas();
    if (name.equals("checkout-web") && index >= 7 && index <= 10) {
      int factor = index - 6;
      latency *= 1 + 0.9 * factor;
      errors += 0.012 * factor;
      ready = service.replicas() - (index >= 9 ? 1 : 0);
    }
    if (name.equals("orders-service") && index >= 8 && index <= 10) {
      latency *= 1.6;
      errors += 0.006;
    }
    double availability = Math.max(0.0, 1 - errors * 4);
    return compact(m("availability", round(availability, 4), "p95_latency_ms", round(latency, 1),
        "error_rate", round(errors, 4), "replicas_ready", ready, "replicas_desired", service.replicas()));
  }

  private static Map<String, Object> localObservation(
      String name, long sequence, String kind, String payload, long offsetSeconds, String ref) {
    Instant when = START.plus(STEP.multipliedBy(sequence - 1)).plusSeconds(offsetSeconds);
    return observation("obs-" + name + "-" + String.format("%04d", sequence), ref, sequence, when, kind, payload);
  }

  // ---------------------------------------------------------------- multi-cloud estate

  static final String AWS_ACCOUNT = "123456789012";
  static final String AZURE_SUBSCRIPTION = "11111111-2222-3333-4444-555555555555";
  static final String GCP_PROJECT = "meridian-sample-441";
  static final Map<String, String> REGION = Map.of("aws", "eu-west-1", "azure", "westeurope", "gcp", "europe-west1");
  static final Map<String, String> PRODUCT = Map.of("aws", "cloudwatch", "azure", "monitor", "gcp", "monitoring");

  /** service name -> provider, latency, errors, replicas. */
  record CloudService(String provider, double latency, double errors, int replicas) {}

  static final Map<String, CloudService> CLOUD_SERVICES = new LinkedHashMap<>();

  static {
    CLOUD_SERVICES.put("checkout-web", new CloudService("aws", 210, 0.004, 8));
    CLOUD_SERVICES.put("payments-api", new CloudService("aws", 120, 0.002, 6));
    CLOUD_SERVICES.put("orders-service", new CloudService("aws", 150, 0.003, 6));
    CLOUD_SERVICES.put("identity-gateway", new CloudService("azure", 85, 0.001, 4));
    CLOUD_SERVICES.put("notifications-worker", new CloudService("azure", 310, 0.006, 3));
    CLOUD_SERVICES.put("inventory-service", new CloudService("gcp", 95, 0.002, 4));
    CLOUD_SERVICES.put("recommendation-ranker", new CloudService("gcp", 60, 0.001, 3));
  }

  /** Samples whose delivery was lost to throttling or permission denial. */
  private static boolean isGap(String name, int index) {
    return (name.equals("notifications-worker") && (index == 8 || index == 9))
        || (name.equals("recommendation-ranker") && (index == 5 || index == 6));
  }

  record Health(double latencyMs, double errorRate, int ready, int desired, double queueAgeS, int requests) {}

  static Map<String, Object> multiCloudEstate() {
    Random rng = new Random(20261113);
    List<Map<String, Object>> deliveries = new ArrayList<>();
    Map<String, Integer> statusSequence = new LinkedHashMap<>(Map.of("aws", 0, "azure", 0, "gcp", 0));
    Map<String, Integer> inventorySequence = new LinkedHashMap<>(Map.of("aws", 0, "azure", 0, "gcp", 0));

    for (int index = 1; index <= SAMPLES; index++) {
      for (Map.Entry<String, CloudService> entry : CLOUD_SERVICES.entrySet()) {
        String name = entry.getKey();
        String provider = entry.getValue().provider();
        if (isGap(name, index)) {
          continue;
        }
        Health health = health(rng, name, entry.getValue(), index);
        String[] kindAndPayload = switch (provider) {
          case "aws" -> awsMetrics(name, index, health);
          case "azure" -> azureMetrics(name, index, health);
          default -> gcpMetrics(name, index, health);
        };
        deliveries.add(delivery("ACCEPTED", cloudObservation(provider, name, index, kindAndPayload[0],
            kindAndPayload[1], ref(provider, PRODUCT.get(provider), name), index, 0)));
      }
      for (String provider : List.of("aws", "azure", "gcp")) {
        String[] error = errorFor(provider, index);
        if (error != null) {
          int sequence = statusSequence.merge(provider, 1, Integer::sum);
          deliveries.add(delivery("ACCEPTED", cloudObservation(provider, "collection-status", sequence,
              error[0], error[1], ref(provider, PRODUCT.get(provider), "collection-status"), index, 20)));
        }
        if (index == 1 || index == SAMPLES) {
          for (String[] page : inventoryPages(provider, index)) {
            int sequence = inventorySequence.merge(provider, 1, Integer::sum);
            deliveries.add(delivery("ACCEPTED", cloudObservation(provider, "inventory", sequence, page[0],
                page[1], ref(provider, inventoryProduct(provider), "inventory"), index, 40)));
          }
        }
      }
    }

    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    for (Map<String, Object> delivery : deliveries) {
      byId.put((String) observationOf(delivery).get("id"), observationOf(delivery));
    }

    // Faults, appended in delivery order after the clean feed.
    deliveries.add(delivery("DUPLICATE", new LinkedHashMap<>(byId.get("obs-aws-checkout-web-0001"))));

    Health lateNotify = health(rng, "notifications-worker", CLOUD_SERVICES.get("notifications-worker"), 8);
    String[] azureLate = azureMetrics("notifications-worker", 8, lateNotify);
    deliveries.add(delivery("QUARANTINED:STALE_SEQUENCE", cloudObservation("azure", "notifications-worker-late", 8,
        azureLate[0], azureLate[1], ref("azure", "monitor", "notifications-worker"), 8, 0)));

    Health lateRanker = health(rng, "recommendation-ranker", CLOUD_SERVICES.get("recommendation-ranker"), 5);
    String[] gcpLate = gcpMetrics("recommendation-ranker", 5, lateRanker);
    deliveries.add(delivery("QUARANTINED:STALE_SEQUENCE", cloudObservation("gcp", "recommendation-ranker-late", 5,
        gcpLate[0], gcpLate[1], ref("gcp", "monitoring", "recommendation-ranker"), 5, 0)));

    Map<String, Object> tampered = new LinkedHashMap<>(byId.get("obs-gcp-inventory-service-0003"));
    tampered.put("id", "obs-gcp-inventory-service-tampered");
    tampered.put("sequence", (long) SAMPLES + 5);
    tampered.put("payload", ((String) tampered.get("payload")).replace("\"unit\":\"ms\"", "\"unit\":\"s\""));
    deliveries.add(delivery("QUARANTINED:DIGEST_MISMATCH", tampered));

    String conflictPayload = compact(m("MetricDataResults", l(), "Messages",
        l(m("Code", "InternalError", "Value", "replayed"))));
    Map<String, Object> conflict = new LinkedHashMap<>(byId.get("obs-aws-payments-api-0002"));
    conflict.put("payload", conflictPayload);
    conflict.put("payload_digest", digest(conflictPayload));
    deliveries.add(delivery("QUARANTINED:REPLAY_CONFLICT", conflict));

    List<Object> bindings = new ArrayList<>();
    for (Map.Entry<String, CloudService> entry : CLOUD_SERVICES.entrySet()) {
      String provider = entry.getValue().provider();
      bindings.add(binding("bind-" + provider + "-" + entry.getKey(),
          ref(provider, PRODUCT.get(provider), entry.getKey()), "svc-" + entry.getKey()));
    }
    for (String provider : List.of("aws", "azure", "gcp")) {
      bindings.add(binding("bind-" + provider + "-inventory",
          ref(provider, inventoryProduct(provider), "inventory"), null));
      bindings.add(binding("bind-" + provider + "-collection-status",
          ref(provider, PRODUCT.get(provider), "collection-status"), null));
    }

    return m(
        "_label", "SAMPLE DATA - synthetic, provider-SHAPED payloads, not captured provider responses. "
            + "Regenerate with the sample-data-tools module.",
        "connection", m("id", "conn-sample-multicloud",
            "name", "Sample multi-cloud estate (AWS, Azure, Google Cloud shapes)",
            "provider", "SAMPLE_LOCAL", "read_only_identity_ref", "sample/readonly-collector",
            "owner_id", "owner-platform", "expected_revision", 0),
        "provider_capability_matrix", capabilityMatrix(),
        "bindings", bindings,
        "deliveries", deliveries);
  }

  private static Map<String, Object> capabilityMatrix() {
    return m(
        "_note", "Declared per provider, never inferred as equivalent. Every provider stays UNACCEPTED until conformance.",
        "aws", capability("NextToken", "ThrottlingException", "SAMPLE_SHAPE"),
        "azure", capability("$skipToken", "TooManyRequests", "UNSUPPORTED"),
        "gcp", capability("nextPageToken", "RESOURCE_EXHAUSTED", "UNSUPPORTED"));
  }

  private static Map<String, Object> capability(String pagination, String rateLimit, String configurationReceipts) {
    return m("acceptance", "UNACCEPTED", "inventory_snapshot", "SAMPLE_SHAPE", "signal_batches", "SAMPLE_SHAPE",
        "configuration_receipts", configurationReceipts, "source_definition_reads", "UNSUPPORTED",
        "execution", "UNSUPPORTED", "pagination", pagination, "rate_limit_signal", rateLimit);
  }

  private static Health health(Random rng, String name, CloudService service, int index) {
    double latency = service.latency() * uniform(rng, 0.92, 1.08);
    double errors = service.errors() * uniform(rng, 0.6, 1.4);
    double queueAge = uniform(rng, 2, 6);
    int ready = service.replicas();
    if (name.equals("checkout-web") && index >= 7 && index <= 10) {
      int factor = index - 6;
      latency *= 1 + 0.9 * factor;
      errors += 0.012 * factor;
      ready = service.replicas() - (index >= 9 ? 1 : 0);
    }
    if (name.equals("orders-service") && index >= 8 && index <= 10) {
      latency *= 1.6;
      errors += 0.006;
      queueAge = 40.0 * (index - 7);
    }
    if (name.equals("recommendation-ranker") && index >= 9 && index <= 10) {
      latency *= 3.4; // cross-cloud contributor to the checkout incident
    }
    return new Health(round(latency, 1), round(errors, 4), ready, service.replicas(), round(queueAge, 1),
        (int) (1000 * uniform(rng, 0.9, 1.1)));
  }

  static String windowStart(int index) {
    return START.plus(STEP.multipliedBy(index - 1)).toString();
  }

  static String windowEnd(int index) {
    return START.plus(STEP.multipliedBy(index)).toString();
  }

  private static String[] awsMetrics(String name, int index, Health h) {
    List<Object> results;
    if (name.equals("orders-service")) {
      results = l(awsResult("age", "ApproximateAgeOfOldestMessage", h.queueAgeS(), index),
          awsResult("visible", "ApproximateNumberOfMessagesVisible", (double) (h.requests() / 4), index));
    } else {
      results = l(awsResult("p95", "TargetResponseTime", round(h.latencyMs() / 1000, 4), index),
          awsResult("err", "HTTPCode_Target_5XX_Count", (double) Math.round(h.errorRate() * h.requests()), index),
          awsResult("healthy", "HealthyHostCount", (double) h.ready(), index));
    }
    return new String[] {"aws.cloudwatch.metrics", compact(m("MetricDataResults", results, "Messages", l()))};
  }

  private static Map<String, Object> awsResult(String id, String label, double value, int index) {
    return m("Id", id, "Label", label, "StatusCode", "Complete", "Timestamps", l(windowEnd(index)),
        "Values", l(value));
  }

  private static String[] azureMetrics(String name, int index, Health h) {
    String resource;
    String namespace;
    List<Object[]> series = new ArrayList<>();
    if (name.equals("notifications-worker")) {
      resource = "/subscriptions/" + AZURE_SUBSCRIPTION
          + "/resourceGroups/rg-meridian-notify/providers/Microsoft.ServiceBus/namespaces/sb-meridian-notify";
      namespace = "Microsoft.ServiceBus/namespaces";
      series.add(new Object[] {"ActiveMessages", "Active Messages", "Count", "average", (double) (h.requests() / 3)});
      series.add(new Object[] {"DeadletteredMessages", "Dead-lettered Messages", "Count", "average",
          round(h.errorRate() * 40, 1)});
    } else {
      resource = "/subscriptions/" + AZURE_SUBSCRIPTION
          + "/resourceGroups/rg-meridian-identity/providers/Microsoft.Web/sites/app-identity-gateway";
      namespace = "Microsoft.Web/sites";
      series.add(new Object[] {"HttpResponseTime", "Response Time", "Seconds", "average",
          round(h.latencyMs() / 1000, 4)});
      series.add(new Object[] {"Http5xx", "Http Server Errors", "Count", "total",
          (double) Math.round(h.errorRate() * h.requests())});
    }
    List<Object> value = new ArrayList<>();
    for (Object[] s : series) {
      value.add(m("id", resource + "/providers/Microsoft.Insights/metrics/" + s[0],
          "type", "Microsoft.Insights/metrics", "name", m("value", s[0], "localizedValue", s[1]), "unit", s[2],
          "timeseries", l(m("metadatavalues", l(), "data", l(m("timeStamp", windowEnd(index), (String) s[3], s[4]))))));
    }
    return new String[] {"azure.monitor.metrics", compact(m("cost", 59,
        "timespan", windowStart(index) + "/" + windowEnd(index), "interval", "PT5M", "namespace", namespace,
        "resourceregion", REGION.get("azure"), "value", value))};
  }

  private static String[] gcpMetrics(String name, int index, Health h) {
    Map<String, Object> resource;
    List<Object[]> series = new ArrayList<>();
    if (name.equals("recommendation-ranker")) {
      resource = m("type", "aiplatform.googleapis.com/Endpoint", "labels",
          m("project_id", GCP_PROJECT, "location", REGION.get("gcp"), "endpoint_id", "5551200987"));
      series.add(new Object[] {"aiplatform.googleapis.com/prediction/online/prediction_latencies", "GAUGE",
          "DOUBLE", h.latencyMs()});
      series.add(new Object[] {"aiplatform.googleapis.com/prediction/online/error_count", "DELTA", "INT64",
          (double) Math.round(h.errorRate() * h.requests())});
    } else {
      resource = m("type", "cloud_run_revision", "labels", m("project_id", GCP_PROJECT, "location",
          REGION.get("gcp"), "service_name", "inventory-service", "revision_name", "inventory-service-00042-qux"));
      series.add(new Object[] {"run.googleapis.com/request_latencies", "GAUGE", "DOUBLE", h.latencyMs()});
      series.add(new Object[] {"run.googleapis.com/container/instance_count", "GAUGE", "INT64",
          (double) h.ready()});
    }
    List<Object> timeSeries = new ArrayList<>();
    for (Object[] s : series) {
      boolean isDouble = "DOUBLE".equals(s[2]);
      Object pointValue = isDouble ? s[3] : String.valueOf(((Double) s[3]).longValue());
      timeSeries.add(m("metric", m("type", s[0], "labels", m()), "resource", resource, "metricKind", s[1],
          "valueType", s[2], "points", l(m("interval", m("startTime", windowStart(index), "endTime", windowEnd(index)),
              "value", m(isDouble ? "doubleValue" : "int64Value", pointValue)))));
    }
    return new String[] {"gcp.monitoring.timeseries", compact(m("timeSeries", timeSeries, "unit", "ms"))};
  }

  private static String[] errorFor(String provider, int index) {
    if (provider.equals("aws") && index == 3) {
      return new String[] {"aws.error", compact(m("__type", "ThrottlingException", "message", "Rate exceeded",
          "retry_after_ms", 1200))};
    }
    if (provider.equals("aws") && index == 11) {
      return new String[] {"aws.error", compact(m("__type", "AccessDeniedException", "message",
          "User is not authorized to perform: config:SelectResourceConfig"))};
    }
    if (provider.equals("azure") && (index == 8 || index == 9)) {
      return new String[] {"azure.error", compact(m("error", m("code", "TooManyRequests", "message",
          "Too many requests. Retry after " + (index == 8 ? 30 : 60) + "s.")))};
    }
    if (provider.equals("gcp") && (index == 5 || index == 6)) {
      return new String[] {"gcp.error", compact(m("error", m("code", 403, "message",
          "Permission 'monitoring.timeSeries.list' denied on resource.", "status", "PERMISSION_DENIED")))};
    }
    return null;
  }

  private static String inventoryProduct(String provider) {
    return switch (provider) {
      case "aws" -> "config";
      case "azure" -> "resourcegraph";
      default -> "assetinventory";
    };
  }

  private static List<String[]> inventoryPages(String provider, int snapshot) {
    List<String[]> pages = new ArrayList<>();
    switch (provider) {
      case "aws" -> {
        String[][] resources = {
          {"AWS::ElasticLoadBalancingV2::LoadBalancer", "app/checkout-alb/50dc6c495c0c9188", "checkout-alb"},
          {"AWS::EKS::Cluster", "checkout-eks", "checkout-eks"},
          {"AWS::EKS::Nodegroup", "checkout-eks/ng-general", "ng-general"},
          {"AWS::RDS::DBInstance", "db-ORDERS7XQ2", "orders-db"},
          {"AWS::RDS::DBInstance", "db-ORDERS7XQ2-replica", "orders-db-replica"},
          {"AWS::SQS::Queue", "orders-events", "orders-events"},
          {"AWS::KMS::Key", "1234abcd-12ab-34cd-56ef-1234567890ab", "orders-data-key"},
          {"AWS::EC2::VPC", "vpc-0a1b2c3d4e5f60718", "checkout-vpc"},
          {"AWS::Route53::HostedZone", "Z0123456789ABCDEFGHIJ", "meridian-sample.example"}};
        for (int page = 0; page < 3; page++) {
          List<Object> identifiers = new ArrayList<>();
          for (int i = page * 3; i < page * 3 + 3; i++) {
            identifiers.add(m("resourceType", resources[i][0], "resourceId", resources[i][1],
                "resourceName", resources[i][2]));
          }
          Map<String, Object> body = m("resourceIdentifiers", identifiers);
          if (page < 2) {
            body.put("nextToken", "sample-token-" + snapshot + "-" + (page + 1));
          }
          pages.add(new String[] {"aws.config.resources", compact(body)});
        }
      }
      case "azure" -> {
        String[][] resources = {
          {"microsoft.web/sites", "app-identity-gateway", "rg-meridian-identity"},
          {"microsoft.web/serverfarms", "asp-identity", "rg-meridian-identity"},
          {"microsoft.servicebus/namespaces", "sb-meridian-notify", "rg-meridian-notify"},
          {"microsoft.containerservice/managedclusters", "identity-aks", "rg-meridian-identity"},
          {"microsoft.network/virtualnetworks", "vnet-identity", "rg-meridian-identity"},
          {"microsoft.managedidentity/userassignedidentities", "id-notify", "rg-meridian-notify"}};
        List<Object> columns = l(m("name", "id", "type", "string"), m("name", "type", "type", "string"),
            m("name", "location", "type", "string"));
        for (int page = 0; page < 2; page++) {
          List<Object> rows = new ArrayList<>();
          for (int i = page * 3; i < page * 3 + 3; i++) {
            rows.add(l("/subscriptions/" + AZURE_SUBSCRIPTION + "/resourceGroups/" + resources[i][2]
                + "/providers/" + resources[i][0] + "/" + resources[i][1], resources[i][0], REGION.get("azure")));
          }
          Map<String, Object> body = m("totalRecords", resources.length, "count", rows.size(),
              "resultTruncated", page == 0 ? "true" : "false", "data", m("columns", columns, "rows", rows));
          if (page == 0) {
            body.put("$skipToken", "sample-skip-" + snapshot + "-1");
          }
          pages.add(new String[] {"azure.resourcegraph.rows", compact(body)});
        }
      }
      default -> {
        String loc = "/locations/" + REGION.get("gcp");
        String[][] assets = {
          {"run.googleapis.com/Service", "//run.googleapis.com/projects/" + GCP_PROJECT + loc + "/services/inventory-service"},
          {"aiplatform.googleapis.com/Endpoint", "//aiplatform.googleapis.com/projects/" + GCP_PROJECT + loc + "/endpoints/5551200987"},
          {"container.googleapis.com/Cluster", "//container.googleapis.com/projects/" + GCP_PROJECT + loc + "/clusters/ml-gke"},
          {"compute.googleapis.com/Network", "//compute.googleapis.com/projects/" + GCP_PROJECT + "/global/networks/ml-vpc"},
          {"firestore.googleapis.com/Database", "//firestore.googleapis.com/projects/" + GCP_PROJECT + "/databases/(default)"},
          {"aiplatform.googleapis.com/FeatureOnlineStore", "//aiplatform.googleapis.com/projects/" + GCP_PROJECT + loc + "/featureOnlineStores/ranker-features"}};
        for (int page = 0; page < 2; page++) {
          List<Object> list = new ArrayList<>();
          for (int i = page * 3; i < page * 3 + 3; i++) {
            list.add(m("name", assets[i][1], "assetType", assets[i][0], "updateTime", windowStart(1)));
          }
          Map<String, Object> body = m("assets", list);
          if (page == 0) {
            body.put("nextPageToken", "sample-page-" + snapshot + "-1");
          }
          pages.add(new String[] {"gcp.assetinventory.assets", compact(body)});
        }
      }
    }
    return pages;
  }

  private static String ref(String provider, String product, String name) {
    return "sample:" + provider + "/" + REGION.get(provider) + "/" + product + "/" + name;
  }

  private static Map<String, Object> cloudObservation(
      String provider, String name, long sequence, String kind, String payload, String ref, int index,
      long offsetSeconds) {
    Instant when = START.plus(STEP.multipliedBy(index)).plusSeconds(offsetSeconds);
    return observation("obs-" + provider + "-" + name + "-" + String.format("%04d", sequence), ref, sequence, when,
        kind, payload);
  }

  // ---------------------------------------------------------------- shared shapes

  private static double uniform(Random rng, double low, double high) {
    return low + (high - low) * rng.nextDouble();
  }

  private static Map<String, Object> observation(
      String id, String sourceRef, long sequence, Instant observedAt, String kind, String payload) {
    return m("id", id, "source_ref", sourceRef, "sequence", sequence, "observed_at", observedAt.toString(),
        "kind", kind, "payload", payload, "payload_digest", digest(payload));
  }

  private static Map<String, Object> delivery(String expect, Map<String, Object> observation) {
    return m("expect", expect, "observation", observation);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> observationOf(Map<String, Object> delivery) {
    return (Map<String, Object>) delivery.get("observation");
  }

  private static Map<String, Object> binding(String id, String sourceRef, String serviceRef) {
    Map<String, Object> binding = m("id", id, "source_ref", sourceRef);
    if (serviceRef != null) {
      binding.put("service_ref", serviceRef);
    }
    binding.put("expected_revision", 0);
    return binding;
  }
}
