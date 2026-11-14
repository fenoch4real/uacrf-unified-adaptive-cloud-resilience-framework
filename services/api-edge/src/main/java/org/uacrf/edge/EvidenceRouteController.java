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

// Only the public evidence reads and review are routed; /internal/v1 S04 endpoints stay unrouted.
@RestController
@RequestMapping("/internal/evidence")
class EvidenceRouteController {
  private final EvidenceRouteClient evidence;

  EvidenceRouteController(EvidenceRouteClient evidence) {
    this.evidence = evidence;
  }

  @GetMapping("/v1/evidence")
  ResponseEntity<String> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> evidence.listEvidence(authorization, pageSize, cursor));
  }

  @GetMapping("/v1/evidence/{id}")
  ResponseEntity<String> get(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> evidence.getEvidence(authorization, id));
  }

  @PostMapping("/v1/evidence/{id}/reviews")
  ResponseEntity<String> review(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> evidence.reviewEvidence(authorization, id, requestBody));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"EVIDENCE_UNAVAILABLE\",\"message\":\"Evidence service is unavailable.\"}");
    }
  }
}
