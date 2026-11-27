package io.uacrf.knowledge.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.knowledge.api.KnowledgeDomainException;
import io.uacrf.knowledge.api.KnowledgeDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeGovernanceService {

    private final KnowledgeRepository repository;

    public KnowledgeGovernanceService(KnowledgeRepository repository) {
        this.repository = repository;
    }

    public List<ArticleDto> listArticles(UUID tenantId, UUID environmentId) {
        return repository.listArticles(tenantId, environmentId);
    }

    @Transactional
    public ArticleDto submitArticle(UUID actorId, CreateArticleRequest req) {
        String digest = CanonicalJson.digestObject(Map.of("docCode", req.docCode(), "title", req.title(), "content", req.contentSummary()));
        ArticleDto dto = new ArticleDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.docCode(), req.docKind(),
                req.title(), req.contentSummary(), digest, actorId, req.authorName(),
                null, null, "QUARANTINED", Instant.now()
        );
        return repository.insertArticle(dto);
    }

    @Transactional
    public ArticleDto reviewArticle(UUID articleId, UUID actorId, ReviewArticleRequest req) {
        ArticleDto article = repository.findArticle(req.tenantId(), req.environmentId(), articleId)
                .orElseThrow(() -> new KnowledgeDomainException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "Article not found"));

        if (actorId.equals(article.authorId())) {
            throw new KnowledgeDomainException(
                    HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "Knowledge authors cannot approve their own submission."
            );
        }
        String status = "APPROVE".equalsIgnoreCase(req.decision()) ? "APPROVED" : "WITHDRAWN";
        repository.updateArticleStatus(articleId, actorId, req.reviewerName(), status);
        return repository.findArticle(req.tenantId(), req.environmentId(), articleId).orElseThrow();
    }

    public List<LearningItemDto> listLearningItems(UUID tenantId, UUID environmentId) {
        return repository.listLearningItems(tenantId, environmentId);
    }

    @Transactional
    public List<LearningItemDto> closeLearningItem(UUID learningId, CloseLearningRequest req) {
        if (req.verificationDigest() == null || req.verificationDigest().isBlank()) {
            throw new KnowledgeDomainException(
                    HttpStatus.CONFLICT, "VERIFICATION_REQUIRED",
                    "FR-LRN-002: Learning item closure blocked until a verified rule, runbook, or test artifact digest is attached."
            );
        }
        repository.closeLearningItem(req.tenantId(), req.environmentId(), learningId, req.verificationDigest());
        repository.insertOutbox(req.tenantId(), req.environmentId(), "learning.item.closed",
                "LearningItem", learningId.toString(), UUID.randomUUID(), CanonicalJson.toJson(req));
        return repository.listLearningItems(req.tenantId(), req.environmentId());
    }

    public List<ExperimentDto> listExperiments(UUID tenantId, UUID environmentId) {
        return repository.listExperiments(tenantId, environmentId);
    }

    @Transactional
    public ExperimentDto runExperiment(RunExperimentRequest req) {
        boolean aborted = req.simulatedObservedSli() < req.abortSloThreshold();
        String status = aborted ? "ABORTED_BY_GUARDRAIL" : "COMPLETED";
        String summary = aborted
                ? "Auto-aborted: observed SLI " + req.simulatedObservedSli() + "% crossed floor " + req.abortSloThreshold() + "%."
                : "Completed safely within blast radius cap=" + req.maxBlastRadius() + ".";
        ExperimentDto dto = new ExperimentDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.title(), req.faultType(),
                req.targetCanonicalId(), req.maxBlastRadius(), req.abortSloThreshold(),
                status, req.simulatedObservedSli(), summary, Instant.now()
        );
        return repository.insertExperiment(dto);
    }
}
