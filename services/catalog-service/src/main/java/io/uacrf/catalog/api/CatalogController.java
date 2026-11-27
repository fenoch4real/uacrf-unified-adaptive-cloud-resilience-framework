package io.uacrf.catalog.api;

import io.uacrf.catalog.api.CatalogDtos.*;
import io.uacrf.catalog.service.CatalogService;
import io.uacrf.catalog.service.ComputeAndAliasService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class CatalogController {

    private final CatalogService service;
    private final ComputeAndAliasService computeAliasService;

    public CatalogController(CatalogService service, ComputeAndAliasService computeAliasService) {
        this.service = service;
        this.computeAliasService = computeAliasService;
    }

    @GetMapping("/v1/services")
    public List<ServiceView> listServices(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listServices(tenantId, environmentId);
    }

    @PostMapping("/v1/services")
    public ResponseEntity<ServiceView> registerService(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerService(actorId, idempotencyKey, request));
    }

    @GetMapping("/v1/agent-workloads")
    public List<AgentWorkloadView> listAgentWorkloads(
            @RequestParam UUID tenantId,
            @RequestParam UUID environmentId,
            @RequestParam(required = false) UUID parentServiceId) {
        return service.listAgentWorkloads(tenantId, environmentId, parentServiceId);
    }

    @PostMapping("/v1/agent-workloads")
    public ResponseEntity<AgentWorkloadView> registerAgentWorkload(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RegisterAgentWorkloadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.registerAgentWorkload(actorId, idempotencyKey, request));
    }

    @PostMapping("/v1/agent-workloads/{workloadId}/versions")
    public ResponseEntity<WorkloadVersionView> registerWorkloadVersion(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID workloadId,
            @Valid @RequestBody RegisterWorkloadVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.registerWorkloadVersion(actorId, workloadId, request));
    }

    @PostMapping("/v1/agent-workloads/{workloadId}:activate")
    public WorkloadVersionView activateWorkloadVersion(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID workloadId,
            @Valid @RequestBody ActivateWorkloadVersionRequest request) {
        return service.activateWorkloadVersion(actorId, workloadId, request);
    }

    @GetMapping("/v1/customer-journeys")
    public List<JourneyView> listJourneys(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listJourneys(tenantId, environmentId);
    }

    @GetMapping("/v1/customer-journeys/{journeyId}")
    public JourneyDetailView getJourneyDetail(
            @RequestParam UUID tenantId,
            @RequestParam UUID environmentId,
            @PathVariable UUID journeyId) {
        return service.getJourneyDetail(tenantId, environmentId, journeyId);
    }

    @PostMapping("/v1/customer-journeys")
    public ResponseEntity<JourneyView> createJourney(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateJourneyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createJourney(actorId, idempotencyKey, request));
    }

    @PostMapping("/v1/customer-journeys/{journeyId}/steps")
    public ResponseEntity<JourneyStepView> addJourneyStep(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID journeyId,
            @Valid @RequestBody AddJourneyStepRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addJourneyStep(actorId, journeyId, request));
    }

    @PostMapping("/v1/customer-journeys/{journeyId}/capabilities")
    public ResponseEntity<JourneyCapabilityView> linkCapability(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID journeyId,
            @Valid @RequestBody LinkJourneyCapabilityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.linkCapability(actorId, idempotencyKey, journeyId, request));
    }

    @PutMapping("/v1/customer-journeys/{journeyId}/impact-profile")
    public ImpactProfileView saveImpactProfileDraft(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID journeyId,
            @Valid @RequestBody UpsertImpactProfileRequest request) {
        return service.saveImpactProfileDraft(actorId, journeyId, request);
    }

    @PostMapping("/v1/customer-journeys/{journeyId}/impact-profiles/{revision}:submit")
    public ImpactProfileView submitImpactProfile(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID journeyId,
            @PathVariable long revision,
            @RequestParam UUID tenantId,
            @RequestParam UUID environmentId) {
        return service.submitImpactProfile(actorId, journeyId, revision, tenantId, environmentId);
    }

    @PostMapping("/v1/customer-journeys/{journeyId}/impact-profiles/{revision}:review")
    public ImpactProfileView reviewImpactProfile(
            @RequestHeader("X-UACRF-Actor-Id") UUID reviewerId,
            @PathVariable UUID journeyId,
            @PathVariable long revision,
            @Valid @RequestBody ReviewImpactProfileRequest request) {
        return service.reviewImpactProfile(reviewerId, journeyId, revision, request);
    }

    @GetMapping("/v1/shared-resources")
    public List<SharedResourceView> listSharedResources(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listSharedResources(tenantId, environmentId);
    }

    @PostMapping("/v1/shared-resources")
    public ResponseEntity<SharedResourceView> registerSharedResource(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSharedResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.registerSharedResource(actorId, idempotencyKey, request));
    }

    @PostMapping("/v1/shared-resources/{sharedAssetId}/consumers")
    public ResponseEntity<Void> linkSharedResourceConsumer(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID sharedAssetId,
            @Valid @RequestBody LinkSharedResourceConsumerRequest request) {
        service.linkSharedResourceConsumer(actorId, sharedAssetId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/v1/compute-placements")
    public List<ComputeAndAliasService.ComputePlacementDto> listComputePlacements(
            @RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return computeAliasService.listPlacements(tenantId, environmentId);
    }

    @PostMapping("/v1/compute-placements")
    @ResponseStatus(HttpStatus.CREATED)
    public ComputeAndAliasService.ComputePlacementDto registerComputePlacement(
            @Valid @RequestBody ComputeAndAliasService.RegisterComputePlacementRequest request) {
        return computeAliasService.registerPlacement(request);
    }

    @GetMapping("/v1/provider-aliases")
    public List<ComputeAndAliasService.ProviderAliasDto> listProviderAliases(
            @RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return computeAliasService.listAliases(tenantId, environmentId);
    }

    @PostMapping("/v1/provider-aliases")
    @ResponseStatus(HttpStatus.CREATED)
    public ComputeAndAliasService.ProviderAliasDto registerProviderAlias(
            @Valid @RequestBody ComputeAndAliasService.RegisterProviderAliasRequest request) {
        return computeAliasService.registerAlias(request);
    }
}
