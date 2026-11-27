package io.uacrf.recovery.api;

import io.uacrf.recovery.api.RecoveryDtos.*;
import io.uacrf.recovery.service.RecoveryOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/recovery")
public class RecoveryController {

    private final RecoveryOrchestrationService service;

    public RecoveryController(RecoveryOrchestrationService service) {
        this.service = service;
    }

    @GetMapping("/kill-switch")
    public KillSwitchDto getKillSwitch(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.getKillSwitch(tenantId, environmentId);
    }

    @PostMapping("/kill-switch")
    public KillSwitchDto updateKillSwitch(@Valid @RequestBody UpdateKillSwitchRequest request) {
        return service.setKillSwitch(request);
    }

    @GetMapping("/executions")
    public List<RecoveryExecutionDto> listExecutions(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listExecutions(tenantId, environmentId);
    }

    @PostMapping("/executions")
    public RecoveryExecutionDto execute(@Valid @RequestBody ExecuteRecoveryRequest request) {
        return service.executeRecovery(request);
    }
}
