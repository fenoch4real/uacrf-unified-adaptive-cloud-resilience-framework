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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.uacrf.security.DelegationPrincipal;

@RestController
class EvidenceController {
  private final EvidenceService evidence;

  EvidenceController(EvidenceService evidence) {
    this.evidence = evidence;
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

  @PostMapping("/internal/v1/inbox/events")
  @ResponseStatus(HttpStatus.ACCEPTED)
  InboxResult inbox(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InboxEvent event) {
    return evidence.receiveEvent(DelegationPrincipal.from(jwt), event);
  }
}
