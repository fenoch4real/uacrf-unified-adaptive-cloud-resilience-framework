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

// Only public topology operations are routed; the internal graph receipt stays unrouted.
@RestController
@RequestMapping("/internal/topology")
class TopologyRouteController {
  private final TopologyRouteClient topology;

  TopologyRouteController(TopologyRouteClient topology) {
    this.topology = topology;
  }

  @GetMapping("/v1/dependency-proposals")
  ResponseEntity<String> listProposals(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int pageSize,
      @RequestParam(required = false) String cursor) {
    return forward(() -> topology.listProposals(authorization, pageSize, cursor));
  }

  @GetMapping("/v1/dependency-proposals/{id}")
  ResponseEntity<String> getProposal(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> topology.getProposal(authorization, id));
  }

  @PostMapping("/v1/dependency-proposals")
  ResponseEntity<String> propose(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> topology.propose(authorization, requestBody));
  }

  @PostMapping("/v1/dependency-proposals:evidence-backed")
  ResponseEntity<String> proposeEvidence(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> topology.proposeEvidence(authorization, requestBody));
  }

  @PostMapping("/v1/dependency-proposals/{id}:review")
  ResponseEntity<String> review(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestBody String requestBody) {
    return forward(() -> topology.review(authorization, id, requestBody));
  }

  @PostMapping("/v1/graphs:accept")
  ResponseEntity<String> accept(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(() -> topology.accept(authorization, requestBody));
  }

  @PostMapping("/v1/graphs/differences:classify")
  ResponseEntity<String> classifyDifferences(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> topology.classifyDifferences(authorization, idempotencyKey, requestBody));
  }

  @PostMapping("/v1/graphs/subgraphs:select")
  ResponseEntity<String> selectSubgraph(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(() -> topology.selectSubgraph(authorization, idempotencyKey, requestBody));
  }

  @GetMapping("/v1/commands/{id}")
  ResponseEntity<String> getCommand(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> topology.getCommand(authorization, id));
  }

  @GetMapping("/v1/graphs/{id}")
  ResponseEntity<String> getGraph(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> topology.getGraph(authorization, id));
  }

  @GetMapping("/v1/graphs/{id}/reconciliation")
  ResponseEntity<String> reconciliation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id) {
    return forward(() -> topology.reconciliation(authorization, id));
  }

  @GetMapping("/v1/graphs/{id}/subgraph")
  ResponseEntity<String> subgraph(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam("root_type") String rootType,
      @RequestParam("root_id") String rootId,
      @RequestParam int depth) {
    return forward(() -> topology.subgraph(authorization, id, rootType, rootId, depth));
  }

  @GetMapping("/v1/graphs/{id}/impact")
  ResponseEntity<String> impact(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam("entity_type") String entityType,
      @RequestParam("entity_id") String entityId) {
    return forward(() -> topology.impact(authorization, id, entityType, entityId));
  }

  private static ResponseEntity<String> forward(Supplier<ResponseEntity<String>> request) {
    try {
      return request.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"TOPOLOGY_UNAVAILABLE\",\"message\":\"Topology service is unavailable.\"}");
    }
  }
}
