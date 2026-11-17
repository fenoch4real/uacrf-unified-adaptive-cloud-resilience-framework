package org.uacrf.edge;

import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

@RestController
@RequestMapping("/internal/decision-governance/v1/authorization-policy-bundles")
class DecisionGovernanceRouteController {
  private final DecisionGovernanceRouteClient decisionGovernance;

  DecisionGovernanceRouteController(DecisionGovernanceRouteClient decisionGovernance) {
    this.decisionGovernance = decisionGovernance;
  }

  @GetMapping
  ResponseEntity<String> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestParam MultiValueMap<String, String> query) {
    return forward(() -> decisionGovernance.list(authorization, idempotencyKey, query));
  }

  @PostMapping
  ResponseEntity<String> propose(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(
        () -> decisionGovernance.propose(authorization, idempotencyKey, requestBody));
  }

  @PostMapping("/{id}:decide")
  ResponseEntity<String> decide(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(
        () -> decisionGovernance.decide(authorization, idempotencyKey, id, requestBody));
  }

  @GetMapping("/{id}")
  ResponseEntity<String> get(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String id,
      @RequestParam MultiValueMap<String, String> query) {
    return forward(() -> decisionGovernance.get(authorization, idempotencyKey, id, query));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              "{\"code\":\"DECISION_GOVERNANCE_UNAVAILABLE\","
                  + "\"message\":\"Decision governance service is unavailable.\"}");
    }
  }
}
