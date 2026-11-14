package org.uacrf.connections;

import static org.uacrf.connections.ConnectionsRequests.*;
import static org.uacrf.connections.ConnectionsResponses.*;

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
class ConnectionsController {
  private final ConnectionsService connections;

  ConnectionsController(ConnectionsService connections) {
    this.connections = connections;
  }

  @PostMapping("/v1/connections")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt register(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterConnection request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return connections.register(DelegationPrincipal.from(jwt), request, idempotencyKey);
  }

  @GetMapping("/v1/connections")
  Page<Connection> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return connections.list(
        DelegationPrincipal.from(jwt), ConnectionsService.validatePageSize(pageSize), cursor);
  }

  @GetMapping("/v1/connections/{id}")
  Connection get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return connections.get(DelegationPrincipal.from(jwt), id);
  }

  @PostMapping("/v1/connections/{id}/source-bindings")
  @ResponseStatus(HttpStatus.CREATED)
  Receipt bind(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody BindSource request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return connections.bind(DelegationPrincipal.from(jwt), id, request, idempotencyKey);
  }

  @GetMapping("/v1/connections/{id}/source-bindings")
  Page<Binding> listBindings(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return connections.listBindings(
        DelegationPrincipal.from(jwt), id, ConnectionsService.validatePageSize(pageSize), cursor);
  }

  @PostMapping("/v1/connections/{id}/observations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  IngestResult submitObservation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody SubmitObservation request) {
    return connections.ingest(DelegationPrincipal.from(jwt), id, request);
  }

  @GetMapping("/v1/connections/{id}/observations")
  Page<Observation> listObservations(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return connections.listObservations(
        DelegationPrincipal.from(jwt), id, ConnectionsService.validatePageSize(pageSize), cursor);
  }

  @GetMapping("/v1/connections/{id}/quarantine")
  Page<Quarantined> listQuarantine(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return connections.listQuarantine(
        DelegationPrincipal.from(jwt), id, ConnectionsService.validatePageSize(pageSize), cursor);
  }
}
