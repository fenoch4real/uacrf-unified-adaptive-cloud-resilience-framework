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
@RequestMapping("/internal/catalog")
class CatalogRouteController {
  private final CatalogRouteClient catalog;

  CatalogRouteController(CatalogRouteClient catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/v1/services")
  ResponseEntity<String> listServices(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Boolean includeRetired) {
    return forward(() -> catalog.listServices(authorization, pageSize, cursor, includeRetired));
  }

  @PostMapping("/v1/services")
  ResponseEntity<String> registerService(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> catalog.registerService(authorization, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/agent-workloads")
  ResponseEntity<String> listAgents(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> catalog.listAgents(authorization, pageSize, cursor));
  }

  @PostMapping("/v1/agent-workloads")
  ResponseEntity<String> registerAgent(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> catalog.registerAgent(authorization, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/customer-journeys")
  ResponseEntity<String> listJourneys(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Boolean includeRetired) {
    return forward(() -> catalog.listJourneys(authorization, pageSize, cursor, includeRetired));
  }

  @GetMapping("/v1/customer-journeys/{id}")
  ResponseEntity<String> getJourney(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> catalog.getJourney(authorization, id));
  }

  @PostMapping("/v1/customer-journeys")
  ResponseEntity<String> registerJourney(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> catalog.registerJourney(authorization, idempotencyKey, requestBody));
  }

  @PostMapping("/v1/customer-journeys/{id}/steps")
  ResponseEntity<String> registerStep(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> catalog.registerStep(authorization, id, idempotencyKey, requestBody));
  }

  @PostMapping("/v1/customer-journeys/{id}/capabilities")
  ResponseEntity<String> linkCapability(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> catalog.linkCapability(authorization, id, idempotencyKey, requestBody));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              "{\"code\":\"CATALOG_UNAVAILABLE\","
                  + "\"message\":\"Catalog service is unavailable.\"}");
    }
  }
}
