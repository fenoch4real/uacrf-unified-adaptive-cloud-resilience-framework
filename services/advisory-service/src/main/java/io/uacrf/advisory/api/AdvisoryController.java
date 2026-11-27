package io.uacrf.advisory.api;

import io.uacrf.advisory.api.AdvisoryDtos.*;
import io.uacrf.advisory.service.AdvisoryAgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/advisory")
public class AdvisoryController {

    private final AdvisoryAgentService service;

    public AdvisoryController(AdvisoryAgentService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public AdvisoryConfigDto getConfig(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.getConfig(tenantId, environmentId);
    }

    @PostMapping("/config/toggle")
    public AdvisoryConfigDto toggle(@RequestParam UUID tenantId,
                                    @RequestParam UUID environmentId,
                                    @RequestParam boolean enabled) {
        return service.setAdvisoryEnabled(tenantId, environmentId, enabled);
    }

    @GetMapping("/findings")
    public List<AdvisoryFindingDto> listFindings(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listFindings(tenantId, environmentId);
    }

    @PostMapping("/runs")
    public AdvisoryFindingDto runAdvisory(@Valid @RequestBody RunAdvisoryRequest request) {
        return service.runAdvisory(request);
    }

    @PostMapping("/findings/{findingId}/review")
    public List<AdvisoryFindingDto> reviewFinding(@PathVariable UUID findingId,
                                                  @Valid @RequestBody ReviewFindingRequest request) {
        return service.reviewFinding(findingId, request);
    }

    @PostMapping("/arbitrate")
    public ArbitratedOutcome arbitrate(@RequestBody List<AgentProposalInput> proposals) {
        return service.arbitrate(proposals);
    }
}
