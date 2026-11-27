package io.uacrf.objectives.api;

import io.uacrf.objectives.api.ObjectivesDtos.*;
import io.uacrf.objectives.service.ObjectiveImportService;
import io.uacrf.objectives.service.ObjectivesControlService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ObjectivesController {

    private final ObjectivesControlService service;
    private final ObjectiveImportService importService;

    public ObjectivesController(ObjectivesControlService service, ObjectiveImportService importService) {
        this.service = service;
        this.importService = importService;
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

    @GetMapping("/objectives/import-conflicts")
    public List<ObjectiveImportService.ImportConflictDto> listConflicts(
            @RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return importService.listConflicts(tenantId, environmentId);
    }

    @PostMapping("/objectives/import-preview")
    public ObjectiveImportService.ImportConflictDto previewImport(
            @Valid @RequestBody ObjectiveImportService.ImportPreviewRequest request) {
        return importService.previewImport(request);
    }

    @PostMapping("/objectives/import-resolve/{conflictId}")
    public ObjectiveImportService.ImportConflictDto resolveConflict(
            @PathVariable UUID conflictId,
            @Valid @RequestBody ObjectiveImportService.ResolveConflictRequest request) {
        return importService.resolveConflict(conflictId, request);
    }
}
