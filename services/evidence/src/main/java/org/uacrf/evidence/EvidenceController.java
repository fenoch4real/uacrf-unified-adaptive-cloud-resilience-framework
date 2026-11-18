package org.uacrf.evidence;

import static org.uacrf.evidence.EvidenceRequests.*;
import static org.uacrf.evidence.EvidenceResponses.*;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.databind.JsonNode;

@RestController
class EvidenceController {
  private final EvidenceService evidence;
  private final QualificationPolicyService policies;
  private final QualificationWaiverService waivers;

  EvidenceController(
      EvidenceService evidence,
      QualificationPolicyService policies,
      QualificationWaiverService waivers) {
    this.evidence = evidence;
    this.policies = policies;
    this.waivers = waivers;
  }

  @PostMapping("/internal/v1/observations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  SubmitResult submit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubmitEvidence request) {
    return evidence.submit(DelegationPrincipal.from(jwt), request);
  }

  @GetMapping("/v1/evidence")
  Page<Evidence> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return evidence.list(DelegationPrincipal.from(jwt), EvidenceService.validatePageSize(pageSize), cursor);
  }

  @GetMapping("/v1/evidence/{id}")
  Evidence get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return evidence.get(DelegationPrincipal.from(jwt), id);
  }

  @PostMapping("/v1/evidence/{id}/reviews")
  Evidence review(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody ReviewEvidence request) {
    return evidence.review(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/internal/v1/qualifications/{id}:receipt")
  QualificationReceipt receipt(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody ReceiptRequest request) {
    return evidence.receipt(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/internal/v1/qualifications/{id}:measurement")
  MeasurementReceipt measurement(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody MeasurementRequest request) {
    return evidence.measurement(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/internal/v1/qualifications/{id}:topology-summary")
  TopologySummaryResult topologySummary(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody TopologySummaryRequest request) {
    return evidence.topologySummary(jwt, id, request);
  }

  @PostMapping("/internal/v1/inbox/events")
  @ResponseStatus(HttpStatus.ACCEPTED)
  InboxResult inbox(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InboxEvent event) {
    return evidence.receiveEvent(DelegationPrincipal.from(jwt), event);
  }

  @PostMapping("/v1/qualification-policies")
  JsonNode proposePolicy(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody JsonNode request) {
    return policies.propose(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/qualification-policies")
  JsonNode listPolicies(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam("service_id") String serviceId,
      @RequestParam(defaultValue = "25", name = "page_size") int pageSize,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String purpose) {
    return policies.list(DelegationPrincipal.from(jwt), serviceId, pageSize, cursor, purpose);
  }

  @GetMapping("/v1/qualification-policies/{id}")
  JsonNode getPolicy(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(required = false) Long revision) {
    return policies.get(DelegationPrincipal.from(jwt), id, revision);
  }

  @PostMapping("/v1/qualification-policies/{id}:decide")
  JsonNode decidePolicy(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody JsonNode request) {
    return policies.decide(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @GetMapping("/v1/qualification-policies/{id}/history")
  JsonNode policyHistory(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(defaultValue = "25", name = "page_size") int pageSize,
      @RequestParam(required = false) String cursor) {
    return policies.history(DelegationPrincipal.from(jwt), id, pageSize, cursor);
  }

  @PostMapping("/v1/qualification-waivers")
  JsonNode proposeWaiver(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody JsonNode request) {
    return waivers.propose(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/qualification-waivers")
  JsonNode listWaivers(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam("service_id") String serviceId,
      @RequestParam(defaultValue = "25", name = "page_size") int pageSize,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, name = "policy_id") String policyId) {
    return waivers.list(DelegationPrincipal.from(jwt), serviceId, pageSize, cursor, policyId);
  }

  @GetMapping("/v1/qualification-waivers/{id}")
  JsonNode getWaiver(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return waivers.get(DelegationPrincipal.from(jwt), id);
  }

  @PostMapping("/v1/qualification-waivers/{id}:decide")
  JsonNode decideWaiver(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody JsonNode request) {
    return waivers.decide(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @PostMapping("/v1/qualification-waivers/{id}:revoke")
  JsonNode revokeWaiver(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody JsonNode request) {
    return waivers.revoke(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }
}
