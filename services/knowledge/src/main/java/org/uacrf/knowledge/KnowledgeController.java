package org.uacrf.knowledge;

import static org.uacrf.knowledge.KnowledgeRequests.*;
import static org.uacrf.knowledge.KnowledgeResponses.*;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.uacrf.security.DelegationPrincipal;

@RestController
class KnowledgeController {
  private final KnowledgeService knowledge;

  KnowledgeController(KnowledgeService knowledge) {
    this.knowledge = knowledge;
  }

  @PostMapping("/v1/knowledge-items")
  @ResponseStatus(HttpStatus.CREATED)
  ItemVersion stage(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StageItem request) {
    return knowledge.stage(DelegationPrincipal.from(jwt), request);
  }

  @GetMapping("/v1/knowledge-items")
  List<ItemVersion> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false, name = "item_id") String itemId,
      @RequestParam(required = false) String purpose) {
    return knowledge.list(DelegationPrincipal.from(jwt), itemId, purpose);
  }

  @PostMapping("/v1/knowledge-items/{id}:review")
  ItemVersion review(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody ReviewItem request) {
    return knowledge.review(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/v1/knowledge-items/{id}:withdraw")
  ItemVersion withdraw(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody WithdrawItem request) {
    return knowledge.withdraw(DelegationPrincipal.from(jwt), id, request);
  }

  @PostMapping("/v1/retrievals")
  @ResponseStatus(HttpStatus.CREATED)
  Retrieval run(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RunRetrieval request) {
    return knowledge.run(DelegationPrincipal.from(jwt), request);
  }

  @GetMapping("/v1/retrievals/{id}")
  Retrieval getRetrieval(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return knowledge.getRetrieval(DelegationPrincipal.from(jwt), id);
  }

  @PostMapping("/internal/v1/retrievals/{id}:receipt")
  RetrievalReceipt receipt(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody ReceiptRequest request) {
    return knowledge.receipt(DelegationPrincipal.from(jwt), id, request);
  }
}
