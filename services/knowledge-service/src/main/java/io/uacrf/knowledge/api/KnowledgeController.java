package io.uacrf.knowledge.api;

import io.uacrf.knowledge.api.KnowledgeDtos.*;
import io.uacrf.knowledge.service.KnowledgeGovernanceService;
import io.uacrf.knowledge.service.MlPromotionService;
import io.uacrf.knowledge.service.QuantitativeShadowEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class KnowledgeController {

    private final KnowledgeGovernanceService service;
    private final QuantitativeShadowEngine quantitative;
    private final MlPromotionService mlPromotionService;

    public KnowledgeController(KnowledgeGovernanceService service,
                               QuantitativeShadowEngine quantitative,
                               MlPromotionService mlPromotionService) {
        this.service = service;
        this.quantitative = quantitative;
        this.mlPromotionService = mlPromotionService;
    }

    @GetMapping("/knowledge/articles")
    public List<ArticleDto> listArticles(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listArticles(tenantId, environmentId);
    }

    @PostMapping("/knowledge/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleDto submitArticle(@RequestHeader("X-UACRF-Actor-Id") UUID actorId,
                                    @Valid @RequestBody CreateArticleRequest request) {
        return service.submitArticle(actorId, request);
    }

    @PostMapping("/knowledge/articles/{articleId}/review")
    public ArticleDto reviewArticle(@PathVariable UUID articleId,
                                    @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
                                    @Valid @RequestBody ReviewArticleRequest request) {
        return service.reviewArticle(articleId, actorId, request);
    }

    @GetMapping("/learning/items")
    public List<LearningItemDto> listLearningItems(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listLearningItems(tenantId, environmentId);
    }

    @PostMapping("/learning/items/{learningId}/close")
    public List<LearningItemDto> closeLearningItem(@PathVariable UUID learningId,
                                                   @Valid @RequestBody CloseLearningRequest request) {
        return service.closeLearningItem(learningId, request);
    }

    @GetMapping("/experiments")
    public List<ExperimentDto> listExperiments(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listExperiments(tenantId, environmentId);
    }

    @PostMapping("/experiments")
    public ExperimentDto runExperiment(@Valid @RequestBody RunExperimentRequest request) {
        return service.runExperiment(request);
    }

    @GetMapping("/quantitative/benchmarks")
    public PaperBenchmarkReport runQuantitativeBenchmarks() {
        return quantitative.runSection105Benchmark();
    }

    @GetMapping("/models")
    public List<MlPromotionService.ModelCardDto> listModels(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return mlPromotionService.listModelCards(tenantId, environmentId);
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public MlPromotionService.ModelCardDto registerModel(
            @RequestHeader("X-UACRF-Actor-Id") UUID ownerId,
            @Valid @RequestBody MlPromotionService.RegisterModelCardRequest req) {
        return mlPromotionService.registerModelCard(ownerId, req);
    }

    @PostMapping("/models/{modelCardId}/promote")
    public MlPromotionService.ModelCardDto promoteModel(
            @PathVariable UUID modelCardId,
            @RequestHeader("X-UACRF-Actor-Id") UUID approverId,
            @RequestParam String targetStage) {
        return mlPromotionService.promoteModel(modelCardId, approverId, targetStage);
    }
}
