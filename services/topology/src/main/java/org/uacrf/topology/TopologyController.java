package org.uacrf.topology;

import static org.uacrf.topology.TopologyRequests.*;
import static org.uacrf.topology.TopologyResponses.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.uacrf.security.DelegationPrincipal;
import java.net.URI;

@RestController
class TopologyController {
  private final TopologyService service;
  private final TopologyGraphCommandService commands;
  TopologyController(TopologyService service, TopologyGraphCommandService commands) { this.service = service; this.commands=commands; }
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
  @PostMapping("/v1/graphs/differences:classify")
  ResponseEntity<CommandReceipt> classifyDifferences(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ClassifyDifferences request,
      @RequestHeader("Idempotency-Key") String key) {
    CommandReceipt receipt=commands.classify(jwt,request,key);
    return ResponseEntity.accepted().location(URI.create(receipt.statusResource())).body(receipt);
  }
  @PostMapping("/v1/graphs/subgraphs:select")
  ResponseEntity<CommandReceipt> selectSubgraph(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SelectSubgraph request,
      @RequestHeader("Idempotency-Key") String key) {
    CommandReceipt receipt=commands.select(jwt,request,key);
    return ResponseEntity.accepted().location(URI.create(receipt.statusResource())).body(receipt);
  }
  @GetMapping("/v1/commands/{id}")
  java.util.Map<String,Object> commandStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return commands.status(DelegationPrincipal.from(jwt),id);
  }
}
