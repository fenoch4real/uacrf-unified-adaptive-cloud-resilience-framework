package io.uacrf.assessment.api;

import io.uacrf.assessment.api.AssessmentDtos.*;
import io.uacrf.assessment.service.AssessmentEngineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class AssessmentController {

    private final AssessmentEngineService service;

    public AssessmentController(AssessmentEngineService service) {
        this.service = service;
    }

    @GetMapping("/assessments")
    public List<AssessmentDto> listAssessments(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listAssessments(tenantId, environmentId);
    }

    @GetMapping("/assessments/{assessmentId}")
    public AssessmentDto getAssessment(@PathVariable UUID assessmentId,
                                       @RequestParam UUID tenantId,
                                       @RequestParam UUID environmentId) {
        return service.getAssessment(tenantId, environmentId, assessmentId);
    }

    @PostMapping("/assessments/{assessmentId}/reviews")
    public AssessmentDto recordReview(@PathVariable UUID assessmentId,
                                      @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
                                      @Valid @RequestBody ReviewDecisionRequest request) {
        return service.recordReview(assessmentId, actorId, request);
    }

    @PostMapping("/assessments/{assessmentId}/envelopes")
    public DecisionEnvelopeDto sealEnvelope(@PathVariable UUID assessmentId,
                                            @RequestParam UUID tenantId,
                                            @RequestParam UUID environmentId,
                                            @RequestHeader(value = "X-UACRF-Actor-Name", defaultValue = "Platform Authority") String actorName) {
        return service.sealDecisionEnvelope(tenantId, environmentId, assessmentId, actorName);
    }

    @PostMapping("/path-certifications")
    public PathCertificationDto certifyPaths(@Valid @RequestBody CertifyPathsRequest request) {
        return service.certifyRecoveryPaths(request);
    }

    @PostMapping("/decision-replays")
    public ReplayVerificationResult verifyReplay(@Valid @RequestBody VerifyReplayRequest request) {
        return service.verifyReplay(request);
    }
}
