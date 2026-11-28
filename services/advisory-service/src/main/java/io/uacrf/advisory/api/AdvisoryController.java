package io.uacrf.advisory.api;

import io.uacrf.advisory.api.AdvisoryDtos.*;
import io.uacrf.advisory.service.AdvisoryAgentService;
import io.uacrf.advisory.service.EnterpriseLlmGateway;
import io.uacrf.advisory.service.OrchestrationRunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class AdvisoryController {

    private final AdvisoryAgentService service;
    private final EnterpriseLlmGateway llmGateway;
    private final OrchestrationRunService orchestrationService;

    public AdvisoryController(AdvisoryAgentService service,
                              EnterpriseLlmGateway llmGateway,
                              OrchestrationRunService orchestrationService) {
        this.service = service;
        this.llmGateway = llmGateway;
        this.orchestrationService = orchestrationService;
    }

    @GetMapping("/v1/advisory/config")
    public AdvisoryConfigDto getConfig(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.getConfig(tenantId, environmentId);
    }

    @GetMapping("/v1/advisory/llm-providers")
    public List<EnterpriseLlmGateway.LlmProviderProfile> listLlmProviders() {
        return llmGateway.listProviderProfiles();
    }

    @GetMapping("/v1/advisory/llm-status")
    public Map<String, Object> getLlmStatus() {
        return llmGateway.getGatewayStatus();
    }

    @PostMapping("/v1/advisory/config/toggle")
    public AdvisoryConfigDto toggle(@RequestParam UUID tenantId,
                                    @RequestParam UUID environmentId,
                                    @RequestParam boolean enabled) {
        return service.setAdvisoryEnabled(tenantId, environmentId, enabled);
    }

    @GetMapping("/v1/advisory/findings")
    public List<AdvisoryFindingDto> listFindings(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listFindings(tenantId, environmentId);
    }

    @PostMapping("/v1/advisory/runs")
    public AdvisoryFindingDto runAdvisory(@Valid @RequestBody RunAdvisoryRequest request) {
        return service.runAdvisory(request);
    }

    @PostMapping("/v1/advisory/findings/{findingId}/review")
    public List<AdvisoryFindingDto> reviewFinding(@PathVariable UUID findingId,
                                                  @Valid @RequestBody ReviewFindingRequest request) {
        return service.reviewFinding(findingId, request);
    }

    @PostMapping("/v1/advisory/arbitrate")
    public ArbitratedOutcome arbitrate(@RequestBody List<AgentProposalInput> proposals) {
        return service.arbitrate(proposals);
    }

    @GetMapping("/v1/orchestration-runs")
    public List<OrchestrationRunService.OrchestrationRunDto> listOrchestrationRuns(
            @RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return orchestrationService.listRuns(tenantId, environmentId);
    }

    @PostMapping("/v1/orchestration-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrchestrationRunService.OrchestrationRunDto admitOrchestrationRun(
            @Valid @RequestBody OrchestrationRunService.AdmitRunRequest req) {
        return orchestrationService.admitRun(req);
    }

    @PostMapping("/v1/orchestration-runs/{runId}/checkpoint")
    public OrchestrationRunService.OrchestrationRunDto recordCheckpoint(
            @PathVariable UUID runId, @Valid @RequestBody OrchestrationRunService.StepCheckpointRequest req) {
        return orchestrationService.recordStepCheckpoint(runId, req);
    }

    @PostMapping("/v1/orchestration-runs/{runId}/cancel")
    public OrchestrationRunService.OrchestrationRunDto cancelRun(
            @PathVariable UUID runId, @RequestParam(defaultValue = "Operator cancelled") String reason) {
        return orchestrationService.cancelRun(runId, reason);
    }

    @PostMapping("/v1/orchestration-runs/{runId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrchestrationRunService.OrchestrationRunDto resumeRun(
            @PathVariable UUID runId, @Valid @RequestBody OrchestrationRunService.ResumeRunRequest req) {
        return orchestrationService.resumeFromCheckpoint(runId, req);
    }

    @GetMapping("/v1/orchestration-runs/{runId}/events")
    public List<Map<String, Object>> runEvents(@PathVariable UUID runId) {
        return orchestrationService.listEvents(runId);
    }
}
