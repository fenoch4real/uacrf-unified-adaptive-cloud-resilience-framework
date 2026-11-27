package io.uacrf.objectives.api;

import io.uacrf.objectives.api.ObjectivesDtos.*;
import io.uacrf.objectives.service.ObjectivesControlService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ObjectivesController {

    private final ObjectivesControlService service;

    public ObjectivesController(ObjectivesControlService service) {
        this.service = service;
    }

    @GetMapping("/objectives")
    public List<ObjectiveDto> listObjectives(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listObjectives(tenantId, environmentId);
    }

    @GetMapping("/control/evaluations")
    public List<RiskEvaluationDto> listEvaluations(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listRiskEvaluations(tenantId, environmentId);
    }

    @PostMapping("/control/evaluate")
    public RiskEvaluationDto evaluateRisk(@Valid @RequestBody EvaluateRiskRequest request) {
        return service.evaluateRisk(request);
    }
}
