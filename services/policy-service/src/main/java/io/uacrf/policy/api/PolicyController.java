package io.uacrf.policy.api;

import io.uacrf.policy.api.PolicyDtos.*;
import io.uacrf.policy.service.PolicyAuthorityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/policy")
public class PolicyController {

    private final PolicyAuthorityService service;

    public PolicyController(PolicyAuthorityService service) {
        this.service = service;
    }

    @GetMapping("/proposals")
    public List<ProposalDto> listProposals(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listProposals(tenantId, environmentId);
    }

    @PostMapping("/proposals")
    @ResponseStatus(HttpStatus.CREATED)
    public ProposalDto createProposal(@RequestHeader("X-UACRF-Actor-Id") UUID actorId,
                                      @Valid @RequestBody CreateProposalRequest request) {
        return service.createProposal(actorId, request);
    }

    @PostMapping("/proposals/{proposalId}/approve")
    public ProposalDto approveProposal(@PathVariable UUID proposalId,
                                       @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
                                       @Valid @RequestBody ApproveProposalRequest request) {
        return service.approveProposal(proposalId, actorId, request);
    }

    @GetMapping("/authorizations")
    public List<IssuedAuthorizationDto> listAuthorizations(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listAuthorizations(tenantId, environmentId);
    }

    @PostMapping("/proposals/{proposalId}/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedAuthorizationDto issueAuthorization(@PathVariable UUID proposalId,
                                                     @Valid @RequestBody IssueAuthorizationRequest request) {
        return service.issueBoundedAuthorization(proposalId, request);
    }

    @PostMapping("/authorizations/consume")
    public Map<String, Object> consumeNonce(@RequestParam String nonce) {
        service.consumeNonce(nonce);
        return Map.of("nonce", nonce, "consumed", true);
    }
}
