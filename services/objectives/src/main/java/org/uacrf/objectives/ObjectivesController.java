package org.uacrf.objectives;

import static org.uacrf.objectives.ObjectivesRequests.*;
import static org.uacrf.objectives.ObjectivesResponses.*;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    return objectives.propose(DelegationPrincipal.from(jwt), request);
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

  @PostMapping("/v1/objectives/{id}:submit")
  Objective submit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Transition request) {
    return objectives.submit(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/v1/objectives/{id}:decide")
  Objective decide(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody DecideObjective request) {
    return objectives.decide(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/v1/objectives/{id}:activate")
  Objective activate(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Transition request) {
    return objectives.activate(DelegationPrincipal.from(jwt), id, request);
  }

  @PutMapping("/v1/organization-constraints/{metric}")
  Constraint setConstraint(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String metric, @Valid @RequestBody SetConstraint request) {
    return objectives.setConstraint(DelegationPrincipal.from(jwt), metric, request);
  }

  @PostMapping("/internal/v1/workloads/{id}/objectives:receipt")
  ObjectiveReceipt receipt(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody ReceiptRequest request) {
    return objectives.receipt(DelegationPrincipal.from(jwt), id, request);
  }
}
