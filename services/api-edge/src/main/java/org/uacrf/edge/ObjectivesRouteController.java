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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

// Only the public objective and constraint operations are routed; /internal/v1 S05 endpoints stay unrouted.
@RestController
@RequestMapping("/internal/objectives")
class ObjectivesRouteController {
  private final ObjectivesRouteClient objectives;

  ObjectivesRouteController(ObjectivesRouteClient objectives) {
    this.objectives = objectives;
  }

  @GetMapping("/v1/objectives")
  ResponseEntity<String> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String workloadId,
      @RequestParam(required = false) String cursor) {
    return forward(() -> objectives.listObjectives(authorization, pageSize, workloadId, cursor));
  }

  @GetMapping("/v1/objectives/{id}")
  ResponseEntity<String> get(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> objectives.getObjective(authorization, id));
  }

  @PostMapping("/v1/objectives")
  ResponseEntity<String> propose(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> objectives.proposeObjective(authorization, requestBody));
  }

  @PostMapping("/v1/objectives/{id}:submit")
  ResponseEntity<String> submit(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> objectives.transition(authorization, id, "submit", requestBody));
  }

  @PostMapping("/v1/objectives/{id}:decide")
  ResponseEntity<String> decide(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> objectives.transition(authorization, id, "decide", requestBody));
  }

  @PostMapping("/v1/objectives/{id}:activate")
  ResponseEntity<String> activate(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> objectives.transition(authorization, id, "activate", requestBody));
  }

  @PutMapping("/v1/organization-constraints/{metric}")
  ResponseEntity<String> constrain(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String metric,
      @RequestBody String requestBody) {
    return forward(() -> objectives.setConstraint(authorization, metric, requestBody));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"OBJECTIVES_UNAVAILABLE\",\"message\":\"Objectives service is unavailable.\"}");
    }
  }
}
