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

// Only public S08 operations are routed; its internal case-receipt endpoint remains private.
@RestController
@RequestMapping("/internal/knowledge")
class KnowledgeRouteController {
  private final KnowledgeRouteClient knowledge;

  KnowledgeRouteController(KnowledgeRouteClient knowledge) {
    this.knowledge = knowledge;
  }

  @GetMapping("/v1/knowledge-items")
  ResponseEntity<String> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(required = false, name = "item_id") String itemId,
      @RequestParam(required = false) String purpose) {
    return forward(() -> knowledge.listItems(authorization, itemId, purpose));
  }

  @PostMapping("/v1/knowledge-items")
  ResponseEntity<String> stage(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> knowledge.stageItem(authorization, requestBody));
  }

  @PostMapping("/v1/knowledge-items:idempotent")
  ResponseEntity<String> stageIdempotent(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody String requestBody) {
    return forward(() -> knowledge.stageItemIdempotent(authorization, key, requestBody));
  }

  @PostMapping("/v1/knowledge-items/{id}:review")
  ResponseEntity<String> review(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> knowledge.reviewItem(authorization, id, requestBody));
  }

  @GetMapping("/v1/knowledge-items/{id}/versions/{version}:candidate-content")
  ResponseEntity<String> candidateContent(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @PathVariable String version) {
    return forward(() -> knowledge.candidateContent(authorization, id, version));
  }

  @PostMapping("/v1/knowledge-items/{id}:review-bound")
  ResponseEntity<String> reviewBound(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @RequestBody String requestBody) {
    return forward(() -> knowledge.reviewBound(authorization, id, requestBody));
  }

  @PostMapping("/v1/knowledge-items/{id}:withdraw")
  ResponseEntity<String> withdraw(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> knowledge.withdrawItem(authorization, id, requestBody));
  }

  @PostMapping("/v1/knowledge-items/{id}:withdraw-bound")
  ResponseEntity<String> withdrawBound(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id, @RequestBody String requestBody) {
    return forward(() -> knowledge.withdrawBound(authorization, id, requestBody));
  }

  @PostMapping("/v1/retrievals")
  ResponseEntity<String> run(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> knowledge.runRetrieval(authorization, requestBody));
  }

  @GetMapping("/v1/retrievals/{id}")
  ResponseEntity<String> retrieval(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> knowledge.getRetrieval(authorization, id));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"KNOWLEDGE_UNAVAILABLE\",\"message\":\"Knowledge service is unavailable.\"}");
    }
  }
}
