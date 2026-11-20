package org.uacrf.edge;

import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

// Only public assessment operations are routed; the internal receipt stays unrouted.
@RestController
@RequestMapping("/internal/assessment")
class AssessmentRouteController {
  private final AssessmentRouteClient assessment;

  AssessmentRouteController(AssessmentRouteClient assessment) {
    this.assessment = assessment;
  }

  @PostMapping("/v1/cases")
  ResponseEntity<String> create(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> assessment.create(authorization, requestBody));
  }

  @PostMapping("/v1/cases:idempotent-create")
  ResponseEntity<String> createIdempotently(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> assessment.createIdempotently(authorization, idempotencyKey, requestBody));
  }

  @PostMapping("/v1/cases/{id}:seal")
  ResponseEntity<String> seal(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> assessment.transition(authorization, id, "seal", requestBody));
  }

  @GetMapping("/v1/cases/{id}")
  ResponseEntity<String> getCase(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.getCase(authorization, id));
  }

  @GetMapping("/v1/cases/{id}/incidents")
  ResponseEntity<String> listCaseIncidents(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(value = "limit", required = false) String limit,
      @RequestParam(value = "after", required = false) String after) {
    return forward(() -> assessment.listCaseIncidents(authorization, id, limit, after));
  }

  @GetMapping("/v1/cases")
  ResponseEntity<String> listCases(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(value = "workload_id", required = false) String workloadId) {
    return forward(() -> assessment.listCases(authorization, workloadId));
  }

  @PostMapping("/v1/cases/{id}:evaluate")
  ResponseEntity<String> evaluate(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> assessment.evaluate(authorization, id, requestBody));
  }

  @PostMapping("/v1/cases/{id}:idempotent-evaluate")
  ResponseEntity<String> evaluateIdempotently(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @PathVariable String id, @RequestBody String requestBody) {
    return forward(() -> assessment.evaluateIdempotently(authorization, id, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/cases/{id}/evaluations")
  ResponseEntity<String> evaluations(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.evaluations(authorization, id));
  }

  @GetMapping("/v1/cases/{id}/evaluations:latest")
  ResponseEntity<String> latestEvaluation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.latestEvaluation(authorization, id));
  }

  @GetMapping("/v1/evaluations/{id}")
  ResponseEntity<String> evaluation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.evaluation(authorization, id));
  }

  @GetMapping("/v1/recommendations")
  ResponseEntity<String> recommendations(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam("workload_id") String workloadId) {
    return forward(() -> assessment.recommendations(authorization, workloadId));
  }

  @PostMapping("/v1/model-versions")
  ResponseEntity<String> retainModelVersion(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> assessment.retainModelVersion(authorization, requestBody));
  }

  @GetMapping("/v1/model-versions")
  ResponseEntity<String> modelVersions(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(value = "model_id", required = false) String modelId) {
    return forward(() -> assessment.modelVersions(authorization, modelId));
  }

  @GetMapping("/v1/model-versions/{id}")
  ResponseEntity<String> modelVersion(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.modelVersion(authorization, id));
  }

  @PostMapping("/v1/model-versions/{id}:review")
  ResponseEntity<String> reviewModelVersion(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> assessment.reviewModelVersion(authorization, id, requestBody));
  }

  @PostMapping("/v1/model-versions/{id}:promote")
  ResponseEntity<String> promoteModelVersion(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> assessment.promoteModelVersion(authorization, id, requestBody));
  }

  @PostMapping("/v1/incidents")
  ResponseEntity<String> createIncident(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> assessment.createIncident(authorization, requestBody));
  }

  @PostMapping("/v1/incidents:idempotent-create")
  ResponseEntity<String> createIncidentIdempotently(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String key, @RequestBody String requestBody) {
    return forward(() -> assessment.createIncidentIdempotently(authorization, key, requestBody));
  }

  @GetMapping("/v1/incidents/{id}")
  ResponseEntity<String> getIncident(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.getIncident(authorization, id));
  }

  @GetMapping("/v1/incidents/{id}/learning-records")
  ResponseEntity<String> listIncidentLearningRecords(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(value = "limit", required = false) String limit,
      @RequestParam(value = "after", required = false) String after) {
    return forward(() -> assessment.listIncidentLearningRecords(authorization, id, limit, after));
  }

  @PostMapping("/v1/learning-records")
  ResponseEntity<String> proposeLearningRecord(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> assessment.proposeLearningRecord(authorization, requestBody));
  }

  @GetMapping("/v1/learning-records/{id}")
  ResponseEntity<String> getLearningRecord(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.getLearningRecord(authorization, id));
  }

  @PostMapping("/v1/learning-records/{id}:review")
  ResponseEntity<String> reviewLearningRecord(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @RequestBody String requestBody) {
    return forward(() -> assessment.reviewLearningRecord(authorization, id, requestBody));
  }

  @GetMapping("/v1/learning-records/{id}/review")
  ResponseEntity<String> getLearningReview(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> assessment.getLearningReview(authorization, id));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"ASSESSMENT_UNAVAILABLE\",\"message\":\"Assessment service is unavailable.\"}");
    }
  }
}
