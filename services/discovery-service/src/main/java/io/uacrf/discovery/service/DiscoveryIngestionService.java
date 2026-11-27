package io.uacrf.discovery.service;

import io.uacrf.contracts.graph.Neo4jOntology;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.discovery.adapter.SampleLocalScenarioGenerator;
import io.uacrf.discovery.api.DiscoveryDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DiscoveryIngestionService {

    private final DiscoveryRepository repository;
    private final SampleLocalScenarioGenerator sampleGenerator;
    private final RestClient evidenceClient;
    private final RestClient graphClient;
    private final boolean forwardDownstream;

    public DiscoveryIngestionService(
            DiscoveryRepository repository,
            SampleLocalScenarioGenerator sampleGenerator,
            @Value("${uacrf.discovery.evidence-base-url}") String evidenceBaseUrl,
            @Value("${uacrf.discovery.graph-base-url}") String graphBaseUrl,
            @Value("${uacrf.discovery.forward-downstream:false}") boolean forwardDownstream) {
        this.repository = repository;
        this.sampleGenerator = sampleGenerator;
        this.evidenceClient = RestClient.builder().baseUrl(evidenceBaseUrl).build();
        this.graphClient = RestClient.builder().baseUrl(graphBaseUrl).build();
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
        String payloadJson = CanonicalJson.toJson(req);
        String digest = CanonicalJson.digestObject(req);
        ObservationDto obs = new ObservationDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.serviceId(),
                req.subjectCanonicalId(), req.provider(), req.signalType(), req.metricName(),
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

        if (forwardDownstream) {
            try {
                graphClient.post().uri("/v1/graph/project-edges")
                        .body(Map.of(
                                "tenantId", req.tenantId(),
                                "environmentId", req.environmentId(),
                                "edges", batch.edges()
                        ))
                        .retrieve().toBodilessEntity();
                evidenceClient.post().uri("/v1/evidence/ingest-observations")
                        .body(Map.of(
                                "tenantId", req.tenantId(),
                                "environmentId", req.environmentId(),
                                "observations", batch.observations()
                        ))
                        .retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // Outbox record preserves durability if downstream is temporarily unavailable
            }
        }

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

    public List<ObservationDto> listObservations(UUID tenantId, UUID environmentId) {
        return repository.listObservations(tenantId, environmentId);
    }

    public List<DiscoveredEdgeDto> listDiscoveredEdges(UUID tenantId, UUID environmentId) {
        return repository.listDiscoveredEdges(tenantId, environmentId);
    }
}
