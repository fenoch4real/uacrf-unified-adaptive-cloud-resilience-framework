package io.uacrf.graph.api;

import io.uacrf.graph.api.GraphDtos.*;
import io.uacrf.graph.service.KnowledgeGraphService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/graph")
public class GraphController {

    private final KnowledgeGraphService service;

    public GraphController(KnowledgeGraphService service) {
        this.service = service;
    }

    @GetMapping("/revisions/current")
    public GraphRevisionDto currentRevision(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.currentRevision(tenantId, environmentId);
    }

    @GetMapping("/dependencies")
    public DependencyGraphResponse dependencies(@RequestParam UUID tenantId,
                                              @RequestParam UUID environmentId,
                                              @RequestParam(required = false) String focusCanonicalId,
                                              @RequestParam(defaultValue = "2") int depth,
                                              @RequestParam(defaultValue = "true") boolean includeUncertain) {
        return service.getBoundedDependencies(tenantId, environmentId, focusCanonicalId, depth, includeUncertain);
    }

    @GetMapping("/ai-lineage")
    public DependencyGraphResponse aiLineage(@RequestParam UUID tenantId,
                                           @RequestParam UUID environmentId,
                                           @RequestParam(required = false) String serviceCanonicalId) {
        return service.getAiLineage(tenantId, environmentId, serviceCanonicalId);
    }

    @PostMapping("/project-edges")
    public GraphRevisionDto projectEdges(@Valid @RequestBody ProjectEdgesRequest request) {
        return service.projectEdges(request);
    }

    @PostMapping("/edges/{edgeId}/confirm")
    public Map<String, Object> confirmEdge(@PathVariable UUID edgeId,
                                           @RequestParam UUID tenantId,
                                           @RequestParam UUID environmentId) {
        service.confirmEdge(tenantId, environmentId, edgeId);
        return Map.of("edgeId", edgeId, "ownerConfirmed", true, "edgeState", "CONFIRMED");
    }
}
