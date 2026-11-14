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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

@RestController
@RequestMapping("/internal/connections")
class ConnectionsRouteController {
  private final ConnectionsRouteClient connections;

  ConnectionsRouteController(ConnectionsRouteClient connections) {
    this.connections = connections;
  }

  @GetMapping("/v1/connections")
  ResponseEntity<String> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> connections.listConnections(authorization, pageSize, cursor));
  }

  @GetMapping("/v1/connections/{id}")
  ResponseEntity<String> get(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> connections.getConnection(authorization, id));
  }

  @PostMapping("/v1/connections")
  ResponseEntity<String> register(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> connections.registerConnection(authorization, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/connections/{id}/source-bindings")
  ResponseEntity<String> listBindings(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> connections.listBindings(authorization, id, pageSize, cursor));
  }

  @PostMapping("/v1/connections/{id}/source-bindings")
  ResponseEntity<String> bindSource(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> connections.bindSource(authorization, id, idempotencyKey, requestBody));
  }

  @PostMapping("/v1/connections/{id}/observations")
  ResponseEntity<String> submitObservation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> connections.submitObservation(authorization, id, requestBody));
  }

  @GetMapping("/v1/connections/{id}/observations")
  ResponseEntity<String> listObservations(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> connections.listObservations(authorization, id, pageSize, cursor));
  }

  @GetMapping("/v1/connections/{id}/quarantine")
  ResponseEntity<String> listQuarantine(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> connections.listQuarantine(authorization, id, pageSize, cursor));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              "{\"code\":\"CONNECTIONS_UNAVAILABLE\","
                  + "\"message\":\"Connections service is unavailable.\"}");
    }
  }
}
