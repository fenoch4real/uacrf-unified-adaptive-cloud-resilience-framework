package org.uacrf.topology;

import static org.uacrf.topology.TopologyRequests.*;
import static org.uacrf.topology.TopologyResponses.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.uacrf.security.DelegationPrincipal;

@RestController
class TopologyController {
  private final TopologyService service;
  TopologyController(TopologyService service) { this.service = service; }
  @PostMapping("/v1/dependency-proposals") @ResponseStatus(HttpStatus.CREATED)
  Proposal propose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Propose request) { return service.propose(DelegationPrincipal.from(jwt), request); }
  @PostMapping("/v1/dependency-proposals:evidence-backed") @ResponseStatus(HttpStatus.CREATED)
  Proposal proposeEvidence(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EvidenceBackedPropose request) { return service.proposeEvidence(jwt, request); }
  @GetMapping("/v1/dependency-proposals")
  Page<Proposal> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="25") int pageSize, @RequestParam(required=false) String cursor) { return service.list(DelegationPrincipal.from(jwt), pageSize, cursor); }
  @GetMapping("/v1/dependency-proposals/{id}")
  Proposal get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.proposal(DelegationPrincipal.from(jwt), id); }
  @PostMapping("/v1/dependency-proposals/{id}:review")
  Proposal review(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Review request) { return service.review(jwt, id, request); }
  @PostMapping("/v1/graphs:accept")
  Graph accept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Accept request) { return service.accept(DelegationPrincipal.from(jwt), request); }
  @GetMapping("/v1/graphs/{id}")
  Graph graph(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.graph(DelegationPrincipal.from(jwt), id); }
  @GetMapping("/v1/graphs/{id}/reconciliation")
  Reconciliation reconciliation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.reconciliation(DelegationPrincipal.from(jwt), id); }
  @GetMapping("/v1/graphs/{id}/subgraph")
  Subgraph subgraph(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam("root_type") String type, @RequestParam("root_id") String rootId, @RequestParam int depth) { return service.subgraph(DelegationPrincipal.from(jwt), id, type, rootId, depth); }
  @GetMapping("/v1/graphs/{id}/impact")
  Impact impact(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam("entity_type") String type, @RequestParam("entity_id") String entityId) { return service.impact(DelegationPrincipal.from(jwt), id, type, entityId); }
  @PostMapping("/internal/v1/graphs/{id}:receipt")
  Receipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.receipt(jwt, id); }
}
