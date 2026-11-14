package org.uacrf.catalog;

import static org.uacrf.catalog.CatalogRequests.*;
import static org.uacrf.catalog.CatalogResponses.*;

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

@RestController
class CatalogController {
  private final CatalogService catalog;

  CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @PostMapping("/v1/services")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt registerService(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterService request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.registerService(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/services")
  Page<ServiceRecord> listServices(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "false") boolean includeRetired) {
    return catalog.listServices(
        DelegationPrincipal.from(jwt),
        CatalogService.validatePageSize(pageSize),
        cursor,
        includeRetired);
  }

  @PostMapping("/v1/agent-workloads")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt registerAgent(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterAgent request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.registerAgent(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/agent-workloads")
  Page<Agent> listAgents(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return catalog.listAgents(
        DelegationPrincipal.from(jwt), CatalogService.validatePageSize(pageSize), cursor);
  }

  @PostMapping("/v1/customer-journeys")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt registerJourney(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterJourney request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.registerJourney(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/customer-journeys")
  Page<Journey> listJourneys(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "false") boolean includeRetired) {
    return catalog.listJourneys(
        DelegationPrincipal.from(jwt),
        CatalogService.validatePageSize(pageSize),
        cursor,
        includeRetired);
  }

  @GetMapping("/v1/customer-journeys/{id}")
  Journey getJourney(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return catalog.getJourney(DelegationPrincipal.from(jwt), id);
  }

  @PostMapping("/v1/customer-journeys/{id}/steps")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt registerStep(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody RegisterStep request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.registerStep(
        DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @PostMapping("/v1/customer-journeys/{id}/capabilities")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt linkCapability(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody LinkCapability request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.linkCapability(
        DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }
}
