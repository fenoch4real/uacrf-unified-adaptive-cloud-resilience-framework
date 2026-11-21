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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

// Only public objective, constraint, and import operations are routed; /internal/v1 S05 endpoints stay unrouted.
@RestController
@RequestMapping("/internal/objectives")
class ObjectivesRouteController {
  private final ObjectivesRouteClient objectives;

  ObjectivesRouteController(ObjectivesRouteClient objectives) {
    this.objectives = objectives;
  }

  @GetMapping("/v1/objectives")
  ResponseEntity<String> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String workloadId,
      @RequestParam(required = false) String cursor) {
    return forward(() -> objectives.listObjectives(authorization, pageSize, workloadId, cursor));
  }

  @GetMapping("/v1/objectives/{id}")
  ResponseEntity<String> get(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> objectives.getObjective(authorization, id));
  }

  @GetMapping("/v1/objectives/{id}/decision-history")
  ResponseEntity<String> decisionHistory(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> objectives.decisionHistory(authorization, id));
  }

  @GetMapping("/v1/objectives/{id}/decision-records")
  ResponseEntity<String> decisionRecords(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> objectives.decisionRecords(authorization, id, pageSize, cursor));
  }

  @PostMapping("/v1/objectives")
  ResponseEntity<String> propose(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> objectives.proposeObjective(authorization, requestBody));
  }

  @PostMapping("/v1/objectives:local-idempotent-create")
  ResponseEntity<String> createLocalIdempotent(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> objectives.createLocalIdempotent(authorization, idempotencyKey, requestBody));
  }

  @PostMapping("/v1/objective-imports:preview")
  ResponseEntity<String> previewImport(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> objectives.previewImport(authorization, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/objective-imports/{id}")
  ResponseEntity<String> getImportPreview(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> objectives.getImportPreview(authorization, id));
  }

  @GetMapping("/v1/objective-imports/{id}/sample-source")
  ResponseEntity<String> inspectImportSample(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> objectives.inspectImportSample(authorization, id));
  }

  @PostMapping("/v1/objective-imports/{id}/sample-captures")
  ResponseEntity<String> captureImportSample(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String id) {
    return forward(() -> objectives.captureImportSample(authorization, id, idempotencyKey));
  }

  @GetMapping("/v1/objective-imports/{id}/sample-captures/{captureId}")
  ResponseEntity<String> getImportSampleCapture(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @PathVariable String captureId) {
    return forward(() -> objectives.getImportSampleCapture(authorization, id, captureId));
  }

  @GetMapping("/v1/objective-imports/{id}/sample-captures/{captureId}/normalization-rehearsal")
  ResponseEntity<String> rehearseImportNormalization(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @PathVariable String captureId, @RequestParam String targetUnit,
      @RequestParam(defaultValue = "false") boolean allowFractionToPercent) {
    return forward(() -> objectives.rehearseImportNormalization(
        authorization, id, captureId, targetUnit, allowFractionToPercent));
  }

  @PostMapping("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals")
  ResponseEntity<String> proposeSyntheticMapping(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String id, @PathVariable String captureId, @RequestBody String requestBody) {
    return forward(() -> objectives.proposeSyntheticMapping(authorization, id, captureId, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}")
  ResponseEntity<String> getSyntheticMappingProposal(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @PathVariable String captureId, @PathVariable String proposalId) {
    return forward(() -> objectives.getSyntheticMappingProposal(authorization, id, captureId, proposalId));
  }

  @PostMapping("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews")
  ResponseEntity<String> reviewSyntheticMapping(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String id, @PathVariable String captureId, @PathVariable String proposalId,
      @RequestBody String requestBody) {
    return forward(() -> objectives.reviewSyntheticMapping(
        authorization, id, captureId, proposalId, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews/{reviewId}")
  ResponseEntity<String> getSyntheticMappingReview(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @PathVariable String captureId, @PathVariable String proposalId,
      @PathVariable String reviewId) {
    return forward(() -> objectives.getSyntheticMappingReview(authorization, id, captureId, proposalId, reviewId));
  }

  @GetMapping("/v1/objective-imports/{id}/sample-captures")
  ResponseEntity<String> listImportSampleCaptures(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> objectives.listImportSampleCaptures(authorization, id, pageSize, cursor));
  }

  @GetMapping("/v1/objective-imports")
  ResponseEntity<String> listImportPreviews(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> objectives.listImportPreviews(authorization, pageSize, cursor));
  }

  @PostMapping("/v1/objectives/{id}:submit")
  ResponseEntity<String> submit(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> objectives.transition(authorization, id, "submit", requestBody));
  }

  @PostMapping("/v1/objectives/{id}:idempotent-submit")
  ResponseEntity<String> submitIdempotent(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody String requestBody) {
    return forward(() -> objectives.submitIdempotent(authorization, id, key, requestBody));
  }

  @PostMapping("/v1/objectives/{id}:decide")
  ResponseEntity<String> decide(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> objectives.transition(authorization, id, "decide", requestBody));
  }

  @PostMapping("/v1/objectives/{id}:idempotent-decide")
  ResponseEntity<String> decideIdempotent(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody String requestBody) {
    return forward(() -> objectives.decideIdempotent(authorization, id, key, requestBody));
  }

  @PostMapping("/v1/objectives/{id}:activate")
  ResponseEntity<String> activate(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> objectives.transition(authorization, id, "activate", requestBody));
  }

  @PostMapping("/v1/objectives/{id}:idempotent-activate")
  ResponseEntity<String> activateIdempotent(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String id, @RequestBody String requestBody) {
    return forward(() -> objectives.activateIdempotent(authorization, id, key, requestBody));
  }

  @PutMapping("/v1/organization-constraints/{metric}")
  ResponseEntity<String> constrain(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String metric,
      @RequestBody String requestBody) {
    return forward(() -> objectives.setConstraint(authorization, metric, requestBody));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"OBJECTIVES_UNAVAILABLE\",\"message\":\"Objectives service is unavailable.\"}");
    }
  }
}
