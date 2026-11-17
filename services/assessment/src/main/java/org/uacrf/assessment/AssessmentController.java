package org.uacrf.assessment;

import static org.uacrf.assessment.AssessmentRequests.*;
import static org.uacrf.assessment.AssessmentResponses.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.uacrf.security.DelegationPrincipal;

@RestController
class AssessmentController {
  private final AssessmentService service;
  private final ModelVersionService modelVersions;
  private final IncidentLearningService incidentLearning;
  AssessmentController(AssessmentService service, ModelVersionService modelVersions,
      IncidentLearningService incidentLearning) {
    this.service = service;
    this.modelVersions = modelVersions;
    this.incidentLearning = incidentLearning;
  }
  @PostMapping("/v1/cases") @ResponseStatus(HttpStatus.CREATED)
  Case create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCase request) { return service.create(DelegationPrincipal.from(jwt), request); }
  @PostMapping("/v1/cases/{id}:seal")
  Case seal(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Transition request) { return service.seal(DelegationPrincipal.from(jwt), id, request); }
  @GetMapping("/v1/cases/{id}")
  Case get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.getCase(DelegationPrincipal.from(jwt), id); }
  @GetMapping("/v1/cases")
  List<Case> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(value="workload_id", required=false) String workloadId) { return service.listCases(DelegationPrincipal.from(jwt), workloadId); }
  @PostMapping("/internal/v1/cases/{id}:receipt")
  Receipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.receipt(DelegationPrincipal.from(jwt), id); }
  @PostMapping("/internal/v1/cases/{id}:material")
  CaseMaterial material(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.material(DelegationPrincipal.from(jwt), id); }
  @PostMapping("/v1/cases/{id}:evaluate") @ResponseStatus(HttpStatus.CREATED)
  Evaluation evaluate(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Evaluate request) { return service.evaluate(DelegationPrincipal.from(jwt), id, request); }
  @GetMapping("/v1/cases/{id}/evaluations")
  List<Evaluation> evaluations(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.evaluations(DelegationPrincipal.from(jwt), id); }
  @GetMapping("/v1/cases/{id}/evaluations:latest")
  Evaluation latest(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.latest(DelegationPrincipal.from(jwt), id); }
  @GetMapping("/v1/evaluations/{id}")
  Evaluation evaluation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.evaluation(DelegationPrincipal.from(jwt), id); }
  @GetMapping("/v1/recommendations")
  List<Recommendation> recommendations(@AuthenticationPrincipal Jwt jwt, @RequestParam(value="workload_id") String workloadId) { return service.recommendations(DelegationPrincipal.from(jwt), workloadId); }
  @PostMapping("/v1/model-versions") @ResponseStatus(HttpStatus.CREATED)
  ModelVersion retainModelVersion(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RetainModelVersion request) {
    return modelVersions.retain(DelegationPrincipal.from(jwt), request);
  }
  @GetMapping("/v1/model-versions")
  List<ModelVersion> modelVersions(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(value="model_id", required=false) String modelId) {
    return modelVersions.list(DelegationPrincipal.from(jwt), modelId);
  }
  @GetMapping("/v1/model-versions/{id}")
  ModelVersion modelVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return modelVersions.get(DelegationPrincipal.from(jwt), id);
  }
  @PostMapping("/internal/v1/model-evaluation-declarations") @ResponseStatus(HttpStatus.CREATED)
  ModelEvaluationDeclaration declareModelEvaluation(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody DeclareModelEvaluation request) {
    return modelVersions.declareEvaluation(DelegationPrincipal.from(jwt), request);
  }
  @GetMapping("/internal/v1/model-evaluation-declarations")
  ModelEvaluationDeclarationPage modelEvaluationDeclarations(@AuthenticationPrincipal Jwt jwt,
      @RequestParam("model_version_id") String modelVersionId,
      @RequestParam(value="limit", defaultValue="50") int limit,
      @RequestParam(value="after", required=false) String after) {
    return modelVersions.listEvaluationDeclarations(DelegationPrincipal.from(jwt), modelVersionId, limit, after);
  }
  @GetMapping("/internal/v1/model-evaluation-declarations/{id}")
  ModelEvaluationDeclaration modelEvaluationDeclaration(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return modelVersions.getEvaluationDeclaration(DelegationPrincipal.from(jwt), id);
  }
  @PostMapping("/v1/model-versions/{id}:review")
  ModelVersion reviewModelVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
      @Valid @RequestBody ReviewModelVersion request) {
    return modelVersions.review(DelegationPrincipal.from(jwt), id, request);
  }
  @PostMapping("/v1/model-versions/{id}:promote")
  ModelVersion promoteModelVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
      @Valid @RequestBody PromoteModelVersion request) {
    return modelVersions.promote(DelegationPrincipal.from(jwt), id, request);
  }
  @PostMapping("/v1/incidents") @ResponseStatus(HttpStatus.CREATED)
  Incident createIncident(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateIncident request) {
    return incidentLearning.createIncident(DelegationPrincipal.from(jwt), request);
  }
  @GetMapping("/v1/incidents/{id}")
  Incident getIncident(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return incidentLearning.getIncident(DelegationPrincipal.from(jwt), id);
  }
  @PostMapping("/v1/learning-records") @ResponseStatus(HttpStatus.CREATED)
  LearningRecord proposeLearningRecord(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ProposeLearningRecord request) {
    return incidentLearning.proposeLearningRecord(DelegationPrincipal.from(jwt), request);
  }
}
