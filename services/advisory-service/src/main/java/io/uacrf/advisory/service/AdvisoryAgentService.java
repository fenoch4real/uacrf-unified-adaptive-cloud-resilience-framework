package io.uacrf.advisory.service;

import io.uacrf.advisory.api.AdvisoryDomainException;
import io.uacrf.advisory.api.AdvisoryDtos.*;
import io.uacrf.crypto.CanonicalJson;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdvisoryAgentService {

    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore previous instructions", "bypass opa", "execute rollback directly", "override kill switch"
    );

    private final AdvisoryRepository repository;
    private final DeterministicArbiter arbiter;

    public AdvisoryAgentService(AdvisoryRepository repository, DeterministicArbiter arbiter) {
        this.repository = repository;
        this.arbiter = arbiter;
    }

    public AdvisoryConfigDto getConfig(UUID tenantId, UUID environmentId) {
        return repository.getConfig(tenantId, environmentId);
    }

    @Transactional
    public AdvisoryConfigDto setAdvisoryEnabled(UUID tenantId, UUID environmentId, boolean enabled) {
        repository.setEnabled(tenantId, environmentId, enabled);
        return repository.getConfig(tenantId, environmentId);
    }

    public List<AdvisoryFindingDto> listFindings(UUID tenantId, UUID environmentId) {
        return repository.listFindings(tenantId, environmentId);
    }

    @Transactional
    public AdvisoryFindingDto runAdvisory(RunAdvisoryRequest req) {
        AdvisoryConfigDto cfg = repository.getConfig(req.tenantId(), req.environmentId());
        if (!cfg.advisoryEnabled()) {
            throw new AdvisoryDomainException(HttpStatus.CONFLICT, "ADVISORY_DISABLED",
                    "Advisory kill switch active; humans continue using Core deterministic controls.");
        }

        String lowerPrompt = req.queryPrompt().toLowerCase(Locale.ROOT);
        for (String pattern : INJECTION_PATTERNS) {
            if (lowerPrompt.contains(pattern)) {
                throw new AdvisoryDomainException(HttpStatus.UNPROCESSABLE_ENTITY, "PROMPT_INJECTION_BLOCKED",
                        "Prompt injection attempt blocked at sealed tool gateway: matched [" + pattern + "].");
            }
        }

        int estimatedTokens = 350;
        if (cfg.tokensUsed() + estimatedTokens > cfg.maxTokenBudget()) {
            throw new AdvisoryDomainException(HttpStatus.TOO_MANY_REQUESTS, "BUDGET_EXHAUSTED",
                    "Advisory token budget exhausted (" + cfg.tokensUsed() + "/" + cfg.maxTokenBudget() + ").");
        }
        repository.addTokensUsed(req.tenantId(), req.environmentId(), estimatedTokens);

        AdvisoryFindingDto dto = new AdvisoryFindingDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.caseCode(), req.contextRef(),
                "Evidence assistant", "EA-1.3", "PA-0.7",
                "Source-backed draft for: " + req.queryPrompt(),
                "Advisory draft synthesized against approved runbooks and graph context " + req.contextRef() + ". Empirical probes remain required.",
                List.of(new CitationDto("OPS-2210", "Runbook", "Rollback procedure for service v43")),
                "Empirical verification still required",
                "Advisory text cannot qualify missing operational probes or suppress blocking critic dissent.",
                "BLOCKING_CONCERN", "UNRESOLVED", req.humanReviewer(), "PENDING_REVIEW", Instant.now()
        );
        repository.insertFinding(dto);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "advisory.finding.drafted",
                "AdvisoryFinding", dto.findingId().toString(), UUID.randomUUID(), CanonicalJson.toJson(dto));
        return dto;
    }

    @Transactional
    public List<AdvisoryFindingDto> reviewFinding(UUID findingId, ReviewFindingRequest req) {
        int rows = repository.updateReviewDecision(req.tenantId(), req.environmentId(), findingId,
                req.decision(), req.editedBody());
        if (rows == 0) {
            throw new AdvisoryDomainException(HttpStatus.NOT_FOUND, "FINDING_NOT_FOUND", "Advisory finding not found");
        }
        // Notice: critic_status stays UNRESOLVED on EDIT_DRAFT / KEEP_FOR_REVIEW (never silently suppressed)
        return repository.listFindings(req.tenantId(), req.environmentId());
    }

    public ArbitratedOutcome arbitrate(List<AgentProposalInput> proposals) {
        return arbiter.arbitrate(proposals);
    }
}
