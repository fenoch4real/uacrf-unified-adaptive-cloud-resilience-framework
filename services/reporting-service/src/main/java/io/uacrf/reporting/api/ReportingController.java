package io.uacrf.reporting.api;

import io.uacrf.reporting.api.ReportingDtos.*;
import io.uacrf.reporting.service.ComplianceAssuranceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ReportingController {

    private final ComplianceAssuranceService service;

    public ReportingController(ComplianceAssuranceService service) {
        this.service = service;
    }

    @GetMapping("/reporting/metering")
    public List<MeteringEventDto> listMetering(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listMetering(tenantId, environmentId);
    }

    @PostMapping("/reporting/metering")
    @ResponseStatus(HttpStatus.CREATED)
    public MeteringEventDto recordMetering(@Valid @RequestBody RecordMeteringRequest request) {
        return service.recordMetering(request);
    }

    @GetMapping("/reporting/crosswalk")
    public List<ComplianceControlMapping> complianceCrosswalk(@RequestParam(required = false) String framework) {
        return service.getComplianceCrosswalk(framework);
    }

    @GetMapping("/assurance-rooms")
    public List<AssuranceRoomSummaryDto> listAssuranceRooms(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listAssuranceRooms(tenantId, environmentId);
    }

    @PostMapping("/assurance-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedAssuranceRoomResponse createAssuranceRoom(@Valid @RequestBody CreateAssuranceRoomRequest request) {
        return service.createAssuranceRoom(request);
    }

    @GetMapping("/assurance-rooms/{roomId}/bundle")
    public AssuranceEvidenceBundleDto getBundle(@PathVariable UUID roomId,
                                                @RequestParam(name = "token", required = false) String token) {
        return service.accessAssuranceBundle(roomId, token);
    }
}
