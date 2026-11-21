package org.uacrf.objectives;

import static org.uacrf.objectives.ObjectivesRequests.*;
import static org.uacrf.objectives.ObjectivesResponses.*;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.uacrf.security.DelegationPrincipal;

@RestController
class ObjectivesController {
  private final ObjectivesService objectives;

  ObjectivesController(ObjectivesService objectives) {
    this.objectives = objectives;
  }

  @PostMapping("/v1/objectives")
  @ResponseStatus(HttpStatus.CREATED)
  Objective propose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProposeObjective request) {
    return objectives.propose(jwt, request);
  }

  @PostMapping("/v1/objectives:local-idempotent-create")
  ResponseEntity<Objective> createLocalIdempotent(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody ProposeObjective request) {
    ObjectiveCreation result = objectives.createLocalIdempotent(jwt, request, idempotencyKey);
    return result.newlyCreated()
        ? ResponseEntity.status(HttpStatus.CREATED).body(result.objective())
        : ResponseEntity.ok(result.objective());
  }

  @PostMapping("/v1/objective-imports:preview")
  ResponseEntity<CommandReceipt> previewImport(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody ImportPreviewRequest request) {
    ImportPreviewAdmission admission =
        objectives.previewImport(DelegationPrincipal.from(jwt), idempotencyKey, request);
    return admission.newlyAccepted()
        ? ResponseEntity.accepted().body(admission.receipt())
        : ResponseEntity.ok().body(admission.receipt());
  }

  @GetMapping("/v1/objective-imports/{id}")
  ResponseEntity<PendingImportReceipt> getImportPreview(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return ResponseEntity.accepted()
        .body(objectives.getImportPreview(DelegationPrincipal.from(jwt), id));
  }

  @GetMapping("/v1/objectives")
  Page<Objective> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String workloadId,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return objectives.list(
        DelegationPrincipal.from(jwt), workloadId, ObjectivesService.validatePageSize(pageSize), cursor);
  }

  @GetMapping("/v1/objectives/{id}")
  Objective get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return objectives.get(DelegationPrincipal.from(jwt), id);
  }

  @GetMapping("/v1/objectives/{id}/decision-history")
  DecisionHistory decisionHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return objectives.decisionHistory(DelegationPrincipal.from(jwt), id);
  }

  @GetMapping("/v1/objectives/{id}/decision-records")
  DecisionRecordPage decisionRecords(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize, @RequestParam(required = false) String cursor) {
    return objectives.decisionRecords(
        DelegationPrincipal.from(jwt), id, ObjectivesService.validatePageSize(pageSize), cursor);
  }

  @PostMapping("/v1/objectives/{id}:submit")
  Objective submit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Transition request) {
    return objectives.submit(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/v1/objectives/{id}:idempotent-submit")
  Objective submitIdempotent(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody IdempotentSubmission request) {
    return objectives.submitIdempotent(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @PostMapping("/v1/objectives/{id}:decide")
  Objective decide(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody DecideObjective request) {
    return objectives.decide(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/v1/objectives/{id}:idempotent-decide")
  Objective decideIdempotent(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody IdempotentReview request) {
    return objectives.decideIdempotent(DelegationPrincipal.from(jwt), id, request, key);
  }

  @PostMapping("/v1/objectives/{id}:activate")
  Objective activate(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Transition request) {
    return objectives.activate(jwt, id, request);
  }

  @PostMapping("/v1/objectives/{id}:idempotent-activate")
  Objective activateIdempotent(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody IdempotentSubmission request) {
    return objectives.activateIdempotent(jwt, id, request, key);
  }

  @PutMapping("/v1/organization-constraints/{metric}")
  Constraint setConstraint(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String metric, @Valid @RequestBody SetConstraint request) {
    return objectives.setConstraint(DelegationPrincipal.from(jwt), metric, request);
  }

  @PostMapping("/internal/v1/workloads/{id}/objectives:receipt")
  ObjectiveReceipt receipt(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody ReceiptRequest request) {
    return objectives.receipt(jwt, id, request);
  }
}
