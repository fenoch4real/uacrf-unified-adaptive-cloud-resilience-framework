package io.uacrf.discovery.service;

import io.uacrf.contracts.graph.Neo4jOntology;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.discovery.adapter.AwsRuntimeTelemetryPoller;
import io.uacrf.discovery.adapter.CloudInventoryPollerGateway;
import io.uacrf.discovery.adapter.GcpRuntimeTelemetryPoller;
import io.uacrf.discovery.adapter.SampleLocalScenarioGenerator;
import io.uacrf.discovery.adapter.TelemetryAdapter;
import io.uacrf.discovery.api.DiscoveryDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DiscoveryIngestionService {

    private final DiscoveryRepository repository;
    private final SampleLocalScenarioGenerator sampleGenerator;
    private final CloudInventoryPollerGateway inventoryGateway;
    private final GcpRuntimeTelemetryPoller gcpRuntimePoller;
    private final AwsRuntimeTelemetryPoller awsRuntimePoller;
    private final PiiPhiScrubber scrubber;
    private final Map<String, TelemetryAdapter> adapters;
    private final RestClient evidenceClient;
    private final RestClient graphClient;
    private final RestClient catalogClient;
    private final boolean forwardDownstream;

    public DiscoveryIngestionService(
            DiscoveryRepository repository,
            SampleLocalScenarioGenerator sampleGenerator,
            CloudInventoryPollerGateway inventoryGateway,
            GcpRuntimeTelemetryPoller gcpRuntimePoller,
            AwsRuntimeTelemetryPoller awsRuntimePoller,
            PiiPhiScrubber scrubber,
            List<TelemetryAdapter> adapterList,
            @Value("${uacrf.discovery.evidence-base-url}") String evidenceBaseUrl,
            @Value("${uacrf.discovery.graph-base-url}") String graphBaseUrl,
            @Value("${uacrf.discovery.catalog-base-url:http://localhost:8083}") String catalogBaseUrl,
            @Value("${uacrf.discovery.forward-downstream:false}") boolean forwardDownstream) {
        this.repository = repository;
        this.sampleGenerator = sampleGenerator;
        this.inventoryGateway = inventoryGateway;
        this.gcpRuntimePoller = gcpRuntimePoller;
        this.awsRuntimePoller = awsRuntimePoller;
        this.scrubber = scrubber;
        this.adapters = adapterList.stream().collect(Collectors.toMap(
                a -> a.providerKey().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        this.evidenceClient = RestClient.builder().baseUrl(evidenceBaseUrl).build();
        this.graphClient = RestClient.builder().baseUrl(graphBaseUrl).build();
        this.catalogClient = RestClient.builder().baseUrl(catalogBaseUrl).build();
        this.forwardDownstream = forwardDownstream;
    }

    public List<ConnectionDto> listConnections(UUID tenantId, UUID environmentId) {
        return repository.listConnections(tenantId, environmentId);
    }

    @Transactional
    public ConnectionDto createConnection(CreateConnectionRequest req) {
        ConnectionDto dto = new ConnectionDto(
                UUID.randomUUID(),
                req.tenantId(),
                req.environmentId(),
                req.connectionName(),
                req.provider(),
                "CONNECTED",
                req.accessLevel(),
                req.telemetrySource() != null ? req.telemetrySource() : "LIVE_ADAPTER",
                req.affectedSource(),
                req.impactNote(),
                "NOT_CONFIGURED",
                Instant.now()
        );
        return repository.insertConnection(dto);
    }

    @Transactional
    public ObservationDto ingestObservation(TelemetryIngestRequest req) {
        Instant now = Instant.now();
        String sourceTag = req.telemetrySource() != null && !req.telemetrySource().isBlank()
                ? req.telemetrySource() : "LIVE_ADAPTER";
        var scrubbedDims = scrubber.scrubDimensions(req.dimensions());
        var scrubbedReq = new TelemetryIngestRequest(
                req.tenantId(), req.environmentId(), req.serviceId(),
                scrubber.scrubText(req.subjectCanonicalId()).sanitizedText(),
                req.provider(), req.signalType(),
                scrubber.scrubText(req.metricName()).sanitizedText(),
                req.value(), req.unit(), req.threshold(), req.severity(),
                sourceTag, scrubbedDims
        );
        String payloadJson = scrubber.scrubText(CanonicalJson.toJson(scrubbedReq)).sanitizedText();
        String digest = CanonicalJson.digestObject(scrubbedReq);
        ObservationDto obs = new ObservationDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.serviceId(),
                scrubbedReq.subjectCanonicalId(), req.provider(), req.signalType(), scrubbedReq.metricName(),
                req.value(), req.unit(), req.threshold(), req.severity(),
                sourceTag, digest, "minio://uacrf-raw/" + digest.substring(0, 16),
                now, now
        );
        repository.insertObservation(obs, payloadJson);
        repository.updateConnectionCollection(req.tenantId(), req.environmentId(), req.provider(), now);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "telemetry.observation.ingested",
                "TelemetryObservation", obs.observationId().toString(), UUID.randomUUID(), CanonicalJson.toJson(obs));
        return obs;
    }

    public List<Map<String, String>> listRegisteredAdapters() {
        return adapters.values().stream()
                .map(a -> Map.of("providerKey", a.providerKey(), "telemetrySource", a.telemetrySource()))
                .toList();
    }

    @Transactional
    public SampleScenarioResult loadSampleLocalScenario(SampleScenarioRequest req) {
        String key = req.scenarioKey() != null && !req.scenarioKey().isBlank()
                ? req.scenarioKey() : "checkout-defective-deployment-v43";
        var batch = sampleGenerator.collectSnapshot(req.tenantId(), req.environmentId(), key);

        for (ObservationDto obs : batch.observations()) {
            repository.insertObservation(obs, CanonicalJson.toJson(obs));
        }
        for (DiscoveredEdgeDto edge : batch.edges()) {
            Neo4jOntology.requireNodeLabel(edge.sourceLabel());
            Neo4jOntology.requireNodeLabel(edge.targetLabel());
            Neo4jOntology.requireRelationshipType(edge.relationshipType());
            repository.upsertDiscoveredEdge(edge);
        }

        repository.insertOutbox(
                req.tenantId(), req.environmentId(),
                "discovery.sample_local.loaded", "CollectionRun", UUID.randomUUID().toString(),
                UUID.randomUUID(), CanonicalJson.toJson(Map.of(
                        "scenarioKey", key,
                        "telemetrySource", SampleLocalScenarioGenerator.SOURCE_TAG,
                        "observationsCount", batch.observations().size(),
                        "edgesCount", batch.edges().size()
                ))
        );

        forwardBatchDownstream(req.tenantId(), req.environmentId(), batch.observations(), batch.edges());

        return new SampleScenarioResult(
                key,
                SampleLocalScenarioGenerator.SOURCE_TAG,
                "SAMPLE_ONLY",
                batch.observations().size(),
                batch.edges().size(),
                batch.observations(),
                batch.edges(),
                "Illustrative SAMPLE_LOCAL multi-cloud and AI lineage fixture loaded; missing probe evidence remains UNKNOWN per No-Synthetic-Fallback."
        );
    }

    public List<CloudInventoryProfile> listInventoryProfiles() {
        return inventoryGateway.listProfiles();
    }

    public List<ResourceFamilyDescriptor> listResourceFamilies() {
        return inventoryGateway.listResourceFamilies();
    }

    @Transactional
    public InventoryNormalizationResult normalizeInventory(InventoryNormalizeRequest req) {
        var result = inventoryGateway.normalizeBatch(
                req.tenantId(), req.environmentId(), req.provider(), req.telemetrySource(), req.assets());
        if (req.persist()) {
            persistNormalizedBatch(req.tenantId(), req.environmentId(), result);
        }
        return result;
    }

    @Transactional
    public InventoryNormalizationResult pollCloudInventory(InventoryPollRequest req) {
        var result = inventoryGateway.pollAndNormalize(
                req.tenantId(), req.environmentId(), req.provider(), req.scopeFilter());
        persistNormalizedBatch(req.tenantId(), req.environmentId(), result);
        return result;
    }

    @Transactional
    public RuntimeTelemetryPollResult pollGcpRuntimeTelemetry(GcpRuntimeTelemetryPollRequest req) {
        var result = gcpRuntimePoller.poll(req);
        if (req.persist()) {
            persistRuntimeTelemetry(req.tenantId(), req.environmentId(), "GCP",
                    "discovery.gcp_runtime.polled", result.observations(), Map.of(
                            "provider", "GCP",
                            "projectId", result.projectId(),
                            "metricsCount", result.metricsCount(),
                            "logsCount", result.logsCount()
                    ));
        }
        return result;
    }

    @Transactional
    public AwsRuntimeTelemetryPollResult pollAwsRuntimeTelemetry(AwsRuntimeTelemetryPollRequest req) {
        var result = awsRuntimePoller.poll(req);
        if (req.persist()) {
            persistRuntimeTelemetry(req.tenantId(), req.environmentId(), "AWS",
                    "discovery.aws_runtime.polled", result.observations(), Map.of(
                            "provider", "AWS",
                            "region", result.region(),
                            "metricsCount", result.metricsCount(),
                            "logsCount", result.logsCount()
                    ));
        }
        return result;
    }

    private void persistRuntimeTelemetry(UUID tenantId, UUID environmentId, String provider,
                                         String eventType, List<ObservationDto> observations,
                                         Map<String, Object> summaryPayload) {
        for (ObservationDto obs : observations) {
            repository.insertObservation(obs, CanonicalJson.toJson(obs));
        }
        repository.updateConnectionCollection(tenantId, environmentId, provider, Instant.now());
        repository.insertOutbox(tenantId, environmentId, eventType, "CollectionRun",
                UUID.randomUUID().toString(), UUID.randomUUID(), CanonicalJson.toJson(summaryPayload));
    }

    private void persistNormalizedBatch(UUID tenantId, UUID environmentId, InventoryNormalizationResult result) {
        for (ObservationDto obs : result.observations()) {
            repository.insertObservation(obs, CanonicalJson.toJson(obs));
        }
        for (DiscoveredEdgeDto edge : result.edges()) {
            Neo4jOntology.requireNodeLabel(edge.sourceLabel());
            Neo4jOntology.requireNodeLabel(edge.targetLabel());
            Neo4jOntology.requireRelationshipType(edge.relationshipType());
            repository.upsertDiscoveredEdge(edge);
        }
        repository.updateConnectionCollection(tenantId, environmentId, result.provider(), Instant.now());
        repository.insertOutbox(
                tenantId, environmentId,
                "discovery.cloud_inventory.normalized", "CollectionRun", UUID.randomUUID().toString(),
                UUID.randomUUID(), CanonicalJson.toJson(Map.of(
                        "provider", result.provider(),
                        "telemetrySource", result.telemetrySource(),
                        "assetsNormalized", result.assetsNormalized(),
                        "edgesCount", result.edges().size(),
                        "computePlacementsCount", result.computePlacements().size()
                ))
        );
        forwardBatchDownstream(tenantId, environmentId, result.observations(), result.edges(), result.computePlacements());
    }

    private void forwardBatchDownstream(UUID tenantId, UUID environmentId,
                                        List<ObservationDto> observations, List<DiscoveredEdgeDto> edges) {
        forwardBatchDownstream(tenantId, environmentId, observations, edges, List.of());
    }

    private void forwardBatchDownstream(UUID tenantId, UUID environmentId,
                                        List<ObservationDto> observations, List<DiscoveredEdgeDto> edges,
                                        List<NormalizedComputePlacementDto> placements) {
        if (!forwardDownstream) {
            return;
        }
        try {
            if (!edges.isEmpty()) {
                graphClient.post().uri("/v1/graph/project-edges")
                        .body(Map.of("tenantId", tenantId, "environmentId", environmentId, "edges", edges))
                        .retrieve().toBodilessEntity();
            }
            for (var p : placements) {
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("tenantId", tenantId);
                body.put("environmentId", environmentId);
                body.put("serviceCanonicalId", p.serviceCanonicalId());
                body.put("cloudProvider", p.cloudProvider());
                body.put("runtimeKind", p.runtimeKind());
                body.put("region", p.region());
                body.put("availabilityZone", p.availabilityZone());
                body.put("failureDomain", p.failureDomain());
                body.put("clusterOrPool", p.clusterOrPool());
                body.put("minCapacity", p.minCapacity());
                body.put("maxCapacity", p.maxCapacity());
                body.put("currentCapacity", p.currentCapacity());
                body.put("autoscalingEnabled", p.autoscalingEnabled());
                catalogClient.post().uri("/v1/compute-placements")
                        .body(body)
                        .retrieve().toBodilessEntity();
            }
        } catch (Exception ignored) {
            // Outbox record preserves durability if downstream is temporarily unavailable
        }
    }

    public List<ObservationDto> listObservations(UUID tenantId, UUID environmentId) {
        return repository.listObservations(tenantId, environmentId);
    }

    public List<DiscoveredEdgeDto> listDiscoveredEdges(UUID tenantId, UUID environmentId) {
        return repository.listDiscoveredEdges(tenantId, environmentId);
    }
}
