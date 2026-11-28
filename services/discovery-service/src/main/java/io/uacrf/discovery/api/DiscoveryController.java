package io.uacrf.discovery.api;

import io.uacrf.discovery.api.DiscoveryDtos.*;
import io.uacrf.discovery.service.DiscoveryIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class DiscoveryController {

    private final DiscoveryIngestionService service;

    public DiscoveryController(DiscoveryIngestionService service) {
        this.service = service;
    }

    @GetMapping("/connections")
    public List<ConnectionDto> listConnections(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listConnections(tenantId, environmentId);
    }

    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionDto createConnection(@Valid @RequestBody CreateConnectionRequest request) {
        return service.createConnection(request);
    }

    @PostMapping("/telemetry/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ObservationDto ingestTelemetry(@Valid @RequestBody TelemetryIngestRequest request) {
        return service.ingestObservation(request);
    }

    @GetMapping("/telemetry/events")
    public List<ObservationDto> listTelemetry(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listObservations(tenantId, environmentId);
    }

    @GetMapping("/discovery/edges")
    public List<DiscoveredEdgeDto> listDiscoveredEdges(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listDiscoveredEdges(tenantId, environmentId);
    }

    @PostMapping("/telemetry/sample-scenario")
    @ResponseStatus(HttpStatus.CREATED)
    public SampleScenarioResult loadSampleScenario(@Valid @RequestBody SampleScenarioRequest request) {
        return service.loadSampleLocalScenario(request);
    }

    @GetMapping("/telemetry/adapters")
    public List<java.util.Map<String, String>> listAdapters() {
        return service.listRegisteredAdapters();
    }

    @GetMapping("/inventory/profiles")
    public List<CloudInventoryProfile> listInventoryProfiles() {
        return service.listInventoryProfiles();
    }

    @GetMapping("/inventory/resource-families")
    public List<ResourceFamilyDescriptor> listResourceFamilies() {
        return service.listResourceFamilies();
    }

    @PostMapping("/inventory/normalize")
    public InventoryNormalizationResult normalizeInventory(@Valid @RequestBody InventoryNormalizeRequest request) {
        return service.normalizeInventory(request);
    }

    @PostMapping("/inventory/poll")
    public InventoryNormalizationResult pollInventory(@Valid @RequestBody InventoryPollRequest request) {
        return service.pollCloudInventory(request);
    }

    @PostMapping("/telemetry/poll/gcp")
    public RuntimeTelemetryPollResult pollGcpRuntimeTelemetry(@Valid @RequestBody GcpRuntimeTelemetryPollRequest request) {
        return service.pollGcpRuntimeTelemetry(request);
    }
}
