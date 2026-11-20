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

  @PostMapping("/v1/ownership:transfer")
  OwnershipTransferReceipt transferOwnership(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody TransferOwnership request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.transferOwnership(DelegationPrincipal.from(jwt), request, idempotencyKey);
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

  @PostMapping("/v1/agent-workloads/{id}/versions")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt registerAgentVersion(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody RegisterAgentVersion request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.registerAgentVersion(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @PostMapping("/v1/agent-workloads/{id}:activate")
  Receipt activateAgentVersion(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody ActivateAgentVersion request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return catalog.activateAgentVersion(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @GetMapping("/internal/v1/workload-versions/{id}")
  WorkloadVersionReceipt getActiveWorkloadVersion(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(required = false, name = "profile_id") String profileId) {
    return catalog.getActiveWorkloadVersion(DelegationPrincipal.from(jwt), id, profileId);
  }

  @PostMapping("/internal/v1/workloads/{id}:receipt")
  OwnerReceipt getWorkloadOwnerReceipt(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody OwnerReceiptRequest request) {
    return catalog.issueOwnerReceipt(jwt, id, request);
  }

  @PostMapping("/internal/v1/workloads/{id}:current-receipt")
  OwnerReceipt getCurrentWorkloadOwnerReceipt(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody CurrentOwnerReceiptRequest request) {
    return catalog.issueCurrentWorkloadOwnerReceipt(jwt, id, request);
  }

  @PostMapping("/internal/v1/services/{id}:receipt")
  OwnerReceipt getServiceOwnerReceipt(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody OwnerReceiptRequest request) {
    return catalog.issueServiceOwnerReceipt(jwt, id, request);
  }

  @GetMapping("/internal/v1/services/{id}/revisions/{version}")
  ServiceRevisionRead getServiceRevision(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String version) {
    return catalog.getServiceRevision(jwt, id, version);
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
