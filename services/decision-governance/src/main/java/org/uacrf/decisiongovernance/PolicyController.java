package org.uacrf.decisiongovernance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.decisiongovernance.PolicyRequests.ProposePolicy;
import org.uacrf.decisiongovernance.PolicyResponses.Policy;
import org.uacrf.decisiongovernance.PolicyResponses.PolicyPage;
import org.uacrf.decisiongovernance.PolicyResponses.ProposalResult;

@RestController
class PolicyController {
  private final PolicyService policies;

  PolicyController(PolicyService policies) {
    this.policies = policies;
  }

  @PostMapping("/v1/authorization-policy-bundles")
  @ResponseStatus(HttpStatus.OK)
  ProposalResult propose(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ProposePolicy request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return policies.propose(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/authorization-policy-bundles")
  PolicyPage list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "service_id") String serviceId,
      @RequestParam(name = "page_size", defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return policies.list(DelegationPrincipal.from(jwt), serviceId, pageSize, cursor);
  }

  @GetMapping("/v1/authorization-policy-bundles/{id}")
  ProposalResult get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(required = false) Long revision) {
    return new ProposalResult(policies.get(DelegationPrincipal.from(jwt), id, revision), null);
  }
}
