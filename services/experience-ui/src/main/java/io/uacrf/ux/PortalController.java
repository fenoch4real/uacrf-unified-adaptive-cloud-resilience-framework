package io.uacrf.ux;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class PortalController {

    private final UacrfEdgeClient edge;
    private final UUID defaultTenantId;
    private final UUID defaultEnvId;

    private static final UUID DEFAULT_ACTOR_ID = UUID.fromString("33333333-3333-7333-8333-333333333303");

    public PortalController(
            UacrfEdgeClient edge,
            @Value("${uacrf.ux.default-tenant-id}") UUID defaultTenantId,
            @Value("${uacrf.ux.default-environment-id}") UUID defaultEnvId) {
        this.edge = edge;
        this.defaultTenantId = defaultTenantId;
        this.defaultEnvId = defaultEnvId;
    }

    @ModelAttribute
    public void populateContext(Model model, HttpSession session) {
        UUID tenantId = (UUID) session.getAttribute("tenantId");
        UUID envId = (UUID) session.getAttribute("environmentId");
        UUID actorId = (UUID) session.getAttribute("actorId");
        String actorName = (String) session.getAttribute("actorName");
        if (tenantId == null) tenantId = defaultTenantId;
        if (envId == null) envId = defaultEnvId;
        if (actorId == null) {
            actorId = DEFAULT_ACTOR_ID;
            actorName = "Maya Chen (Owner)";
        }
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("environmentId", envId);
        model.addAttribute("actorId", actorId);
        model.addAttribute("actorName", actorName);
        model.addAttribute("principals", edge.getList(
                "/v1/principals?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
    }

    @PostMapping("/context/actor")
    public String switchActor(@RequestParam UUID actorId, @RequestParam String actorName,
                              @RequestHeader(value = "Referer", required = false) String referer,
                              HttpSession session) {
        session.setAttribute("actorId", actorId);
        session.setAttribute("actorName", actorName);
        return "redirect:" + (referer != null ? referer : "/");
    }

    @GetMapping("/")
    public String overview(Model model, @ModelAttribute("tenantId") UUID tenantId,
                           @ModelAttribute("environmentId") UUID envId) {
        var journeys = edge.getList("/v1/customer-journeys?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        var services = edge.getList("/v1/services?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        var agents = edge.getList("/v1/agent-workloads?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        var resources = edge.getList("/v1/shared-resources?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        model.addAttribute("activeNav", "overview");
        model.addAttribute("journeys", journeys);
        model.addAttribute("services", services);
        model.addAttribute("agents", agents);
        model.addAttribute("resources", resources);
        return "overview";
    }

    @GetMapping("/journeys")
    public String journeys(Model model, @ModelAttribute("tenantId") UUID tenantId,
                           @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "journeys");
        model.addAttribute("journeys", edge.getList("/v1/customer-journeys?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("owners", edge.getList("/v1/owner-candidates?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "journeys";
    }

    @PostMapping("/journeys")
    public String createJourney(@ModelAttribute("tenantId") UUID tenantId,
                                @ModelAttribute("environmentId") UUID envId,
                                @ModelAttribute("actorId") UUID actorId,
                                @RequestParam String name,
                                @RequestParam String purpose,
                                @RequestParam UUID ownerId,
                                @RequestParam(defaultValue = "24x7") String operatingWindow,
                                RedirectAttributes ra) {
        try {
            edge.postJson("/v1/customer-journeys", actorId, UUID.randomUUID().toString(), Map.of(
                    "tenantId", tenantId,
                    "environmentId", envId,
                    "name", name,
                    "purpose", purpose,
                    "ownerId", ownerId,
                    "operatingWindow", operatingWindow
            ));
            ra.addFlashAttribute("flashSuccess", "Customer journey registered.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", "Failed to register journey: " + ex.getResponseBodyAsString());
        }
        return "redirect:/journeys";
    }

    @GetMapping("/journeys/{journeyId}")
    public String journeyDetail(@PathVariable UUID journeyId, Model model,
                                @ModelAttribute("tenantId") UUID tenantId,
                                @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "journeys");
        try {
            model.addAttribute("detail", edge.getObject(
                    "/v1/customer-journeys/" + journeyId + "?tenantId=" + tenantId + "&environmentId=" + envId));
        } catch (Exception ex) {
            model.addAttribute("loadError", "Journey could not be loaded from Catalog API.");
        }
        model.addAttribute("services", edge.getList("/v1/services?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("agents", edge.getList("/v1/agent-workloads?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "journey-detail";
    }

    @PostMapping("/journeys/{journeyId}/steps")
    public String addStep(@PathVariable UUID journeyId,
                          @ModelAttribute("tenantId") UUID tenantId,
                          @ModelAttribute("environmentId") UUID envId,
                          @ModelAttribute("actorId") UUID actorId,
                          @RequestParam int sequenceNum,
                          @RequestParam String name,
                          @RequestParam String successCondition,
                          @RequestParam(defaultValue = "30") int timeoutSeconds,
                          RedirectAttributes ra) {
        try {
            edge.postJson("/v1/customer-journeys/" + journeyId + "/steps", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId, "sequenceNum", sequenceNum,
                    "name", name, "successCondition", successCondition,
                    "timeoutSeconds", timeoutSeconds, "failureBehavior", "DEGRADE"
            ));
            ra.addFlashAttribute("flashSuccess", "Ordered step added.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/journeys/" + journeyId;
    }

    @PostMapping("/journeys/{journeyId}/capabilities")
    public String linkCapability(@PathVariable UUID journeyId,
                                 @ModelAttribute("tenantId") UUID tenantId,
                                 @ModelAttribute("environmentId") UUID envId,
                                 @ModelAttribute("actorId") UUID actorId,
                                 @RequestParam(required = false) UUID stepId,
                                 @RequestParam String capabilityKind,
                                 @RequestParam UUID targetId,
                                 @RequestParam String participationCriticality,
                                 @RequestParam String rationale,
                                 RedirectAttributes ra) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("environmentId", envId);
            if (stepId != null) body.put("stepId", stepId);
            body.put("capabilityKind", capabilityKind);
            body.put("targetId", targetId);
            body.put("participationCriticality", participationCriticality);
            body.put("rationale", rationale);
            edge.postJson("/v1/customer-journeys/" + journeyId + "/capabilities", actorId, UUID.randomUUID().toString(), body);
            ra.addFlashAttribute("flashSuccess", "Capability linked with owner rationale.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/journeys/" + journeyId;
    }

    @PostMapping("/journeys/{journeyId}/impact-profile")
    public String saveImpactDraft(@PathVariable UUID journeyId,
                                  @ModelAttribute("tenantId") UUID tenantId,
                                  @ModelAttribute("environmentId") UUID envId,
                                  @ModelAttribute("actorId") UUID actorId,
                                  @RequestParam String impactIfUnavailable,
                                  @RequestParam String affectedPopulation,
                                  @RequestParam String criticalityAssessment,
                                  @RequestParam(required = false) BigDecimal businessToleranceValue,
                                  @RequestParam(required = false) String businessToleranceUnit,
                                  @RequestParam(required = false) String businessToleranceBasis,
                                  @RequestParam String rationale,
                                  RedirectAttributes ra) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("environmentId", envId);
            body.put("impactIfUnavailable", impactIfUnavailable);
            body.put("affectedPopulation", affectedPopulation);
            body.put("criticalityAssessment", criticalityAssessment);
            body.put("businessToleranceValue", businessToleranceValue);
            body.put("businessToleranceUnit", businessToleranceUnit);
            body.put("businessToleranceBasis", businessToleranceBasis);
            body.put("rationale", rationale);
            body.put("dimensions", List.of(Map.of(
                    "dimensionType", "CUSTOMER",
                    "description", impactIfUnavailable,
                    "severity", "P0".equals(criticalityAssessment) ? "CRITICAL" : "HIGH"
            )));
            edge.putJson("/v1/customer-journeys/" + journeyId + "/impact-profile", actorId, body);
            ra.addFlashAttribute("flashSuccess", "Impact profile draft saved.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/journeys/" + journeyId;
    }

    @PostMapping("/journeys/{journeyId}/impact-profiles/{revision}/submit")
    public String submitImpactProfile(@PathVariable UUID journeyId, @PathVariable long revision,
                                      @ModelAttribute("tenantId") UUID tenantId,
                                      @ModelAttribute("environmentId") UUID envId,
                                      @ModelAttribute("actorId") UUID actorId,
                                      RedirectAttributes ra) {
        try {
            edge.postJson("/v1/customer-journeys/" + journeyId + "/impact-profiles/" + revision
                    + ":submit?tenantId=" + tenantId + "&environmentId=" + envId, actorId, null, Map.of());
            ra.addFlashAttribute("flashSuccess", "Impact profile revision " + revision + " submitted for independent review.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/journeys/" + journeyId;
    }

    @PostMapping("/journeys/{journeyId}/impact-profiles/{revision}/review")
    public String reviewImpactProfile(@PathVariable UUID journeyId, @PathVariable long revision,
                                      @ModelAttribute("tenantId") UUID tenantId,
                                      @ModelAttribute("environmentId") UUID envId,
                                      @ModelAttribute("actorId") UUID actorId,
                                      @RequestParam String decision,
                                      @RequestParam String reason,
                                      RedirectAttributes ra) {
        try {
            edge.postJson("/v1/customer-journeys/" + journeyId + "/impact-profiles/" + revision + ":review",
                    actorId, null, Map.of("tenantId", tenantId, "environmentId", envId, "decision", decision, "reason", reason));
            ra.addFlashAttribute("flashSuccess", "Impact profile review recorded: " + decision);
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/journeys/" + journeyId;
    }

    @GetMapping("/services")
    public String services(Model model, @ModelAttribute("tenantId") UUID tenantId,
                           @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "services");
        model.addAttribute("services", edge.getList("/v1/services?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("owners", edge.getList("/v1/owner-candidates?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "services";
    }

    @PostMapping("/services")
    public String registerService(@ModelAttribute("tenantId") UUID tenantId,
                                  @ModelAttribute("environmentId") UUID envId,
                                  @ModelAttribute("actorId") UUID actorId,
                                  @RequestParam String name,
                                  @RequestParam String domain,
                                  @RequestParam String criticality,
                                  @RequestParam UUID ownerId,
                                  @RequestParam(required = false) String description,
                                  RedirectAttributes ra) {
        try {
            edge.postJson("/v1/services", actorId, UUID.randomUUID().toString(), Map.of(
                    "tenantId", tenantId, "environmentId", envId, "name", name,
                    "domain", domain, "criticality", criticality, "ownerId", ownerId,
                    "description", description == null ? "" : description
            ));
            ra.addFlashAttribute("flashSuccess", "Service registered.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/services";
    }

    @GetMapping("/agents")
    public String agents(Model model, @ModelAttribute("tenantId") UUID tenantId,
                         @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "agents");
        model.addAttribute("agents", edge.getList("/v1/agent-workloads?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("services", edge.getList("/v1/services?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("owners", edge.getList("/v1/owner-candidates?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "agents";
    }

    @PostMapping("/agents")
    public String registerAgent(@ModelAttribute("tenantId") UUID tenantId,
                                @ModelAttribute("environmentId") UUID envId,
                                @ModelAttribute("actorId") UUID actorId,
                                @RequestParam String name,
                                @RequestParam String workloadKind,
                                @RequestParam String intendedPurpose,
                                @RequestParam UUID ownerId,
                                @RequestParam UUID runtimePrincipalId,
                                @RequestParam String criticality,
                                @RequestParam(required = false) UUID parentServiceId,
                                RedirectAttributes ra) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("environmentId", envId);
            if (parentServiceId != null) body.put("parentServiceId", parentServiceId);
            body.put("name", name);
            body.put("workloadKind", workloadKind);
            body.put("intendedPurpose", intendedPurpose);
            body.put("ownerId", ownerId);
            body.put("runtimePrincipalId", runtimePrincipalId);
            body.put("criticality", criticality);
            edge.postJson("/v1/agent-workloads", actorId, UUID.randomUUID().toString(), body);
            ra.addFlashAttribute("flashSuccess", "Protected agent workload registered.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/agents";
    }

    @GetMapping("/resources")
    public String resources(Model model, @ModelAttribute("tenantId") UUID tenantId,
                            @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "resources");
        model.addAttribute("resources", edge.getList("/v1/shared-resources?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("owners", edge.getList("/v1/owner-candidates?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "resources";
    }

    @PostMapping("/resources")
    public String createResource(@ModelAttribute("tenantId") UUID tenantId,
                                 @ModelAttribute("environmentId") UUID envId,
                                 @ModelAttribute("actorId") UUID actorId,
                                 @RequestParam String name,
                                 @RequestParam String assetKind,
                                 @RequestParam String classification,
                                 @RequestParam UUID ownerId,
                                 RedirectAttributes ra) {
        try {
            edge.postJson("/v1/shared-resources", actorId, UUID.randomUUID().toString(), Map.of(
                    "tenantId", tenantId, "environmentId", envId, "name", name,
                    "assetKind", assetKind, "classification", classification, "ownerId", ownerId
            ));
            ra.addFlashAttribute("flashSuccess", "Shared resource registered.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/resources";
    }

    @GetMapping("/settings/access")
    public String accessSettings(Model model, @ModelAttribute("tenantId") UUID tenantId,
                                 @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "settings");
        model.addAttribute("memberships", edge.getList("/v1/memberships?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "access";
    }

    @PostMapping("/settings/access/decisions")
    public String createGrantDecision(@ModelAttribute("tenantId") UUID tenantId,
                                      @ModelAttribute("environmentId") UUID envId,
                                      @ModelAttribute("actorId") UUID actorId,
                                      @RequestParam UUID targetPrincipalId,
                                      @RequestParam String capability,
                                      @RequestParam(defaultValue = "GLOBAL") String serviceScope,
                                      @RequestParam String rationale,
                                      RedirectAttributes ra) {
        try {
            var res = edge.postJson("/v1/grants/decisions", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "targetPrincipalId", targetPrincipalId, "capability", capability,
                    "serviceScope", serviceScope, "decision", "APPROVE",
                    "rationale", rationale,
                    "expiresAt", Instant.now().plus(15, ChronoUnit.MINUTES).toString()
            ));
            ra.addFlashAttribute("flashSuccess", "Approval decision recorded. Grant Decision ID: " + res.get("grantDecisionId"));
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/settings/access";
    }

    @PostMapping("/settings/access/memberships")
    public String createMembership(@ModelAttribute("tenantId") UUID tenantId,
                                   @ModelAttribute("environmentId") UUID envId,
                                   @ModelAttribute("actorId") UUID actorId,
                                   @RequestParam UUID principalId,
                                   @RequestParam String capability,
                                   @RequestParam(defaultValue = "GLOBAL") String serviceScope,
                                   @RequestParam UUID grantDecisionId,
                                   RedirectAttributes ra) {
        try {
            edge.postJson("/v1/memberships", actorId, UUID.randomUUID().toString(), Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "principalId", principalId, "capability", capability,
                    "serviceScope", serviceScope, "grantDecisionId", grantDecisionId,
                    "expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).toString()
            ));
            ra.addFlashAttribute("flashSuccess", "Membership granted from independent approval.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/settings/access";
    }

    @GetMapping("/dependencies")
    public String dependencies(Model model,
                               @ModelAttribute("tenantId") UUID tenantId,
                               @ModelAttribute("environmentId") UUID envId,
                               @RequestParam(defaultValue = "urn:uacrf:svc:checkout") String focus,
                               @RequestParam(defaultValue = "2") int depth,
                               @RequestParam(defaultValue = "true") boolean includeUncertain) {
        model.addAttribute("activeNav", "dependencies");
        model.addAttribute("focus", focus);
        model.addAttribute("depth", depth);
        model.addAttribute("includeUncertain", includeUncertain);
        var graph = edge.getObjectOrEmpty(
                "/v1/graph/dependencies?tenantId=" + tenantId + "&environmentId=" + envId
                        + "&focusCanonicalId=" + focus + "&depth=" + depth + "&includeUncertain=" + includeUncertain);
        model.addAttribute("graph", graph);
        return "dependencies";
    }

    @PostMapping("/dependencies/edges/{edgeId}/confirm")
    public String confirmEdge(@PathVariable UUID edgeId,
                              @ModelAttribute("tenantId") UUID tenantId,
                              @ModelAttribute("environmentId") UUID envId,
                              @ModelAttribute("actorId") UUID actorId,
                              RedirectAttributes ra) {
        try {
            edge.postJson("/v1/graph/edges/" + edgeId + "/confirm?tenantId=" + tenantId + "&environmentId=" + envId,
                    actorId, null, Map.of());
            ra.addFlashAttribute("flashSuccess", "Dependency edge owner-confirmed and promoted to CONFIRMED.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/dependencies";
    }

    @GetMapping("/ai-dependencies")
    public String aiDependencies(Model model,
                                 @ModelAttribute("tenantId") UUID tenantId,
                                 @ModelAttribute("environmentId") UUID envId,
                                 @RequestParam(defaultValue = "urn:uacrf:svc:payment") String serviceCanonicalId) {
        model.addAttribute("activeNav", "ai-dependencies");
        model.addAttribute("serviceCanonicalId", serviceCanonicalId);
        model.addAttribute("graph", edge.getObjectOrEmpty(
                "/v1/graph/ai-lineage?tenantId=" + tenantId + "&environmentId=" + envId + "&serviceCanonicalId=" + serviceCanonicalId));
        model.addAttribute("aspects", edge.getList(
                "/v1/evidence/ai-dependencies?tenantId=" + tenantId + "&environmentId=" + envId + "&serviceCanonicalId=" + serviceCanonicalId,
                Map.class));
        return "ai-dependencies";
    }

    @PostMapping("/ai-dependencies/aspects")
    public String qualifyAiAspect(@ModelAttribute("tenantId") UUID tenantId,
                                  @ModelAttribute("environmentId") UUID envId,
                                  @ModelAttribute("actorId") UUID actorId,
                                  @RequestParam String serviceCanonicalId,
                                  @RequestParam String aspectKey,
                                  @RequestParam String currentValue,
                                  @RequestParam(defaultValue = "EMPIRICAL_TEST") String sourceKind,
                                  RedirectAttributes ra) {
        try {
            edge.postJson("/v1/evidence/ai-dependencies", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "serviceCanonicalId", serviceCanonicalId,
                    "aspectKey", aspectKey, "currentValue", currentValue, "sourceKind", sourceKind
            ));
            ra.addFlashAttribute("flashSuccess", "AI dependency evidence updated for " + aspectKey + ".");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/ai-dependencies?serviceCanonicalId=" + serviceCanonicalId;
    }

    @GetMapping("/evidence")
    public String evidence(Model model,
                           @ModelAttribute("tenantId") UUID tenantId,
                           @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "evidence");
        var packages = edge.getList("/v1/evidence/packages?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        model.addAttribute("packages", packages);
        model.addAttribute("activePackage", packages.isEmpty() ? Map.of() : packages.getFirst());
        return "evidence";
    }

    @PostMapping("/evidence/packages/{packageId}/items")
    public String submitEvidenceItem(@PathVariable UUID packageId,
                                     @ModelAttribute("tenantId") UUID tenantId,
                                     @ModelAttribute("environmentId") UUID envId,
                                     @ModelAttribute("actorId") UUID actorId,
                                     @RequestParam String itemKey,
                                     @RequestParam String sourceKind,
                                     @RequestParam String detailNote,
                                     @RequestParam String graphRevision,
                                     RedirectAttributes ra) {
        try {
            edge.postJson("/v1/evidence/packages/" + packageId + "/items", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "itemKey", itemKey, "sourceKind", sourceKind,
                    "detailNote", detailNote, "graphRevision", graphRevision,
                    "ttlSeconds", 3600,
                    "rawPayload", Map.of("submittedBy", actorId.toString(), "detail", detailNote)
            ));
            ra.addFlashAttribute("flashSuccess", "Evidence qualified and stored cleanly with WORM lock.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/evidence";
    }

    @GetMapping("/settings/connections")
    public String connections(Model model,
                              @ModelAttribute("tenantId") UUID tenantId,
                              @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "connections");
        model.addAttribute("connections", edge.getList("/v1/connections?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("observations", edge.getList("/v1/telemetry/events?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("inventoryProfiles", edge.getList("/v1/inventory/profiles", Map.class));
        return "connections";
    }

    @PostMapping("/settings/connections/sample-scenario")
    public String loadSampleScenario(@ModelAttribute("tenantId") UUID tenantId,
                                     @ModelAttribute("environmentId") UUID envId,
                                     @ModelAttribute("actorId") UUID actorId,
                                     RedirectAttributes ra) {
        try {
            var res = edge.postJson("/v1/telemetry/sample-scenario", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId, "scenarioKey", "checkout-defective-deployment-v43"
            ));
            ra.addFlashAttribute("flashSuccess",
                    "Loaded SAMPLE_LOCAL fixture (" + res.get("observationsCreated") + " observations, "
                            + res.get("edgesDiscovered") + " graph edges).");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/settings/connections";
    }

    @PostMapping("/settings/connections/poll-cloud")
    public String pollLiveCloud(@ModelAttribute("tenantId") UUID tenantId,
                                @ModelAttribute("environmentId") UUID envId,
                                @ModelAttribute("actorId") UUID actorId,
                                @RequestParam(defaultValue = "GCP") String provider,
                                @RequestParam(defaultValue = "*") String scopeFilter,
                                @RequestParam(defaultValue = "true") boolean includeRuntimeTelemetry,
                                RedirectAttributes ra) {
        try {
            var invRes = edge.postJson("/v1/inventory/poll", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId, "provider", provider, "scopeFilter", scopeFilter
            ));
            StringBuilder msg = new StringBuilder("Polled " + provider + " live inventory ("
                    + invRes.getOrDefault("assetsNormalized", 0) + " assets, "
                    + (invRes.get("edges") instanceof List<?> l ? l.size() : 0) + " graph edges)");
            if ("GCP".equalsIgnoreCase(provider) && includeRuntimeTelemetry) {
                var rtRes = edge.postJson("/v1/telemetry/poll/gcp", actorId, null, Map.of(
                        "tenantId", tenantId, "environmentId", envId,
                        "includeMetrics", true, "includeLogs", true, "windowMinutes", 60, "persist", true
                ));
                msg.append("; GCP Runtime Telemetry: ")
                        .append(rtRes.getOrDefault("metricsCount", 0)).append(" metrics, ")
                        .append(rtRes.getOrDefault("logsCount", 0)).append(" audit/log events.");
            }
            ra.addFlashAttribute("flashSuccess", msg.toString());
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/settings/connections";
    }

    @GetMapping("/assess")
    public String assess(Model model,
                         @ModelAttribute("tenantId") UUID tenantId,
                         @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "assess");
        var list = edge.getList("/v1/assessments?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        model.addAttribute("assessment", list.isEmpty() ? Map.of() : list.getFirst());
        return "assess";
    }

    @GetMapping("/assess/{assessmentId}/report")
    public String assessReport(@PathVariable UUID assessmentId,
                               Model model,
                               @ModelAttribute("tenantId") UUID tenantId,
                               @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "assess");
        model.addAttribute("assessment", edge.getObjectOrEmpty(
                "/v1/assessments/" + assessmentId + "?tenantId=" + tenantId + "&environmentId=" + envId));
        return "assess-report";
    }

    @PostMapping("/assess/{assessmentId}/reviews")
    public String submitAssessmentReview(@PathVariable UUID assessmentId,
                                         @ModelAttribute("tenantId") UUID tenantId,
                                         @ModelAttribute("environmentId") UUID envId,
                                         @ModelAttribute("actorId") UUID actorId,
                                         @ModelAttribute("actorName") String actorName,
                                         @RequestParam String reviewType,
                                         @RequestParam String outcome,
                                         RedirectAttributes ra) {
        try {
            edge.postJson("/v1/assessments/" + assessmentId + "/reviews", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "reviewType", reviewType, "reviewerName", actorName, "outcome", outcome
            ));
            ra.addFlashAttribute("flashSuccess", reviewType + " review recorded as " + outcome + ".");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/assess/" + assessmentId + "/report";
    }

    @PostMapping("/assess/{assessmentId}/envelopes")
    public String sealAssessmentEnvelope(@PathVariable UUID assessmentId,
                                         @ModelAttribute("tenantId") UUID tenantId,
                                         @ModelAttribute("environmentId") UUID envId,
                                         @ModelAttribute("actorId") UUID actorId,
                                         RedirectAttributes ra) {
        try {
            var res = edge.postJson(
                    "/v1/assessments/" + assessmentId + "/envelopes?tenantId=" + tenantId + "&environmentId=" + envId,
                    actorId, null, Map.of());
            ra.addFlashAttribute("flashSuccess", "RS256 DecisionEnvelope sealed: " + res.get("envelopeId"));
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/assess/" + assessmentId + "/report";
    }

    @PostMapping("/assess/verify-replay")
    public String verifyAssessmentEnvelope(@RequestParam UUID assessmentId,
                                           @RequestParam UUID envelopeId,
                                           @ModelAttribute("tenantId") UUID tenantId,
                                           @ModelAttribute("environmentId") UUID envId,
                                           @ModelAttribute("actorId") UUID actorId,
                                           RedirectAttributes ra) {
        try {
            var res = edge.postJson("/v1/decision-replays", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId, "envelopeId", envelopeId
            ));
            ra.addFlashAttribute("flashSuccess",
                    "Replay verification: " + res.get("status") + " — " + res.get("message"));
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/assess/" + assessmentId + "/report";
    }

    @GetMapping("/control")
    public String control(Model model,
                          @ModelAttribute("tenantId") UUID tenantId,
                          @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "control");
        var evals = edge.getList("/v1/control/evaluations?tenantId=" + tenantId + "&environmentId=" + envId, Map.class);
        model.addAttribute("evaluation", evals.isEmpty() ? Map.of() : evals.getFirst());
        model.addAttribute("proposals", edge.getList("/v1/policy/proposals?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("authorizations", edge.getList("/v1/policy/authorizations?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "control";
    }

    @PostMapping("/control/evaluate")
    public String evaluateControl(@ModelAttribute("tenantId") UUID tenantId,
                                  @ModelAttribute("environmentId") UUID envId,
                                  @ModelAttribute("actorId") UUID actorId,
                                  @RequestParam Double orderSuccessRatePct,
                                  RedirectAttributes ra) {
        try {
            var res = edge.postJson("/v1/control/evaluate", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "serviceCanonicalId", "urn:uacrf:svc:checkout",
                    "serviceName", "Checkout service", "journeyName", "Place Order",
                    "ownerName", "Maya Chen", "orderSuccessRatePct", orderSuccessRatePct
            ));
            ra.addFlashAttribute("flashSuccess", "Rule evaluation completed -> RiskState: " + res.get("riskState"));
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/control";
    }

    @PostMapping("/control/proposals/{proposalId}/approve")
    public String approveProposal(@PathVariable UUID proposalId,
                                  @ModelAttribute("tenantId") UUID tenantId,
                                  @ModelAttribute("environmentId") UUID envId,
                                  @ModelAttribute("actorId") UUID actorId,
                                  @ModelAttribute("actorName") String actorName,
                                  @RequestParam String decision,
                                  RedirectAttributes ra) {
        try {
            edge.postJson("/v1/policy/proposals/" + proposalId + "/approve", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "approverName", actorName, "decision", decision
            ));
            ra.addFlashAttribute("flashSuccess", "Proposal approval decision recorded.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/control";
    }

    @PostMapping("/control/proposals/{proposalId}/authorize")
    public String authorizeProposal(@PathVariable UUID proposalId,
                                    @ModelAttribute("tenantId") UUID tenantId,
                                    @ModelAttribute("environmentId") UUID envId,
                                    @ModelAttribute("actorId") UUID actorId,
                                    @RequestParam String graphRevision,
                                    @RequestParam String subgraphDigest,
                                    RedirectAttributes ra) {
        try {
            var res = edge.postJson("/v1/policy/proposals/" + proposalId + "/authorizations", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "currentGraphRevision", graphRevision, "currentSubgraphDigest", subgraphDigest,
                    "currentBlastRadiusCount", 1, "fallbackChanged", false, "audience", "k8s-rollback-adapter"
            ));
            ra.addFlashAttribute("flashSuccess",
                    "Bounded RS256 Authorization issued (" + res.get("deltaClassification") + ", nonce=" + res.get("nonce") + ").");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/control";
    }

    @GetMapping("/recover")
    public String recover(Model model,
                          @ModelAttribute("tenantId") UUID tenantId,
                          @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "recover");
        model.addAttribute("killSwitch", edge.getObjectOrEmpty("/v1/recovery/kill-switch?tenantId=" + tenantId + "&environmentId=" + envId));
        model.addAttribute("executions", edge.getList("/v1/recovery/executions?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "recover";
    }

    @PostMapping("/recover/kill-switch")
    public String toggleKillSwitch(@ModelAttribute("tenantId") UUID tenantId,
                                   @ModelAttribute("environmentId") UUID envId,
                                   @ModelAttribute("actorId") UUID actorId,
                                   @ModelAttribute("actorName") String actorName,
                                   @RequestParam boolean active,
                                   RedirectAttributes ra) {
        try {
            edge.postJson("/v1/recovery/kill-switch", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "active", active,
                    "reason", active ? "Operator activated kill switch from UI" : "Operator resumed execution readiness",
                    "updatedBy", actorName
            ));
            ra.addFlashAttribute("flashSuccess", "Recovery kill switch state updated: active=" + active);
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/recover";
    }

    @GetMapping("/knowledge")
    public String knowledge(Model model,
                            @ModelAttribute("tenantId") UUID tenantId,
                            @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "knowledge");
        model.addAttribute("articles", edge.getList("/v1/knowledge/articles?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("learningItems", edge.getList("/v1/learning/items?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("benchmarks", edge.getObjectOrEmpty("/v1/quantitative/benchmarks"));
        return "knowledge";
    }

    @PostMapping("/knowledge/learning/{learningId}/close")
    public String closeLearning(@PathVariable UUID learningId,
                                @ModelAttribute("tenantId") UUID tenantId,
                                @ModelAttribute("environmentId") UUID envId,
                                @ModelAttribute("actorId") UUID actorId,
                                @RequestParam(required = false) String verificationDigest,
                                RedirectAttributes ra) {
        try {
            edge.postJson("/v1/learning/items/" + learningId + "/close", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "verificationDigest", verificationDigest != null ? verificationDigest : ""
            ));
            ra.addFlashAttribute("flashSuccess", "Learning item verified and closed.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/knowledge";
    }

    @GetMapping("/advisory")
    public String advisory(Model model,
                           @ModelAttribute("tenantId") UUID tenantId,
                           @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "advisory");
        model.addAttribute("config", edge.getObjectOrEmpty("/v1/advisory/config?tenantId=" + tenantId + "&environmentId=" + envId));
        model.addAttribute("findings", edge.getList("/v1/advisory/findings?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "advisory";
    }

    @PostMapping("/advisory/toggle")
    public String toggleAdvisory(@ModelAttribute("tenantId") UUID tenantId,
                                 @ModelAttribute("environmentId") UUID envId,
                                 @ModelAttribute("actorId") UUID actorId,
                                 @RequestParam boolean enabled,
                                 RedirectAttributes ra) {
        try {
            edge.postJson("/v1/advisory/config/toggle?tenantId=" + tenantId + "&environmentId=" + envId + "&enabled=" + enabled,
                    actorId, null, Map.of());
            ra.addFlashAttribute("flashSuccess", "Solution-specific agent advisors enabled=" + enabled);
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/advisory";
    }

    @PostMapping("/advisory/findings/{findingId}/review")
    public String reviewAdvisoryFinding(@PathVariable UUID findingId,
                                        @ModelAttribute("tenantId") UUID tenantId,
                                        @ModelAttribute("environmentId") UUID envId,
                                        @ModelAttribute("actorId") UUID actorId,
                                        @RequestParam String decision,
                                        RedirectAttributes ra) {
        try {
            edge.postJson("/v1/advisory/findings/" + findingId + "/review", actorId, null, Map.of(
                    "tenantId", tenantId, "environmentId", envId, "decision", decision
            ));
            ra.addFlashAttribute("flashSuccess", "Advisory review decision recorded (" + decision + "); blocking dissent preserved.");
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/advisory";
    }

    @GetMapping("/assurance")
    public String assurance(Model model,
                            @ModelAttribute("tenantId") UUID tenantId,
                            @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "assurance");
        model.addAttribute("crosswalk", edge.getList("/v1/reporting/crosswalk", Map.class));
        model.addAttribute("rooms", edge.getList("/v1/assurance-rooms?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("metering", edge.getList("/v1/reporting/metering?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "assurance";
    }

    @PostMapping("/assurance/rooms")
    public String createAssuranceRoom(@ModelAttribute("tenantId") UUID tenantId,
                                      @ModelAttribute("environmentId") UUID envId,
                                      @ModelAttribute("actorId") UUID actorId,
                                      @ModelAttribute("actorName") String actorName,
                                      @RequestParam String auditorOrganization,
                                      @RequestParam String recipientEmail,
                                      @RequestParam String frameworks,
                                      @RequestParam int ttlHours,
                                      RedirectAttributes ra) {
        try {
            var fwList = java.util.Arrays.stream(frameworks.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            var created = edge.postJson("/v1/assurance-rooms", actorId, UUID.randomUUID().toString(), Map.of(
                    "tenantId", tenantId, "environmentId", envId,
                    "auditorOrganization", auditorOrganization, "recipientEmail", recipientEmail,
                    "frameworks", fwList, "ttlHours", ttlHours, "createdBy", actorName
            ));
            ra.addFlashAttribute("flashSuccess", "Watermarked Assurance Room issued. Bundle SHA-256: "
                    + created.getOrDefault("bundleSha256", "ok"));
        } catch (RestClientResponseException ex) {
            ra.addFlashAttribute("flashError", ex.getResponseBodyAsString());
        }
        return "redirect:/assurance";
    }

    @GetMapping("/compute")
    public String compute(Model model,
                          @ModelAttribute("tenantId") UUID tenantId,
                          @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "compute");
        model.addAttribute("placements", edge.getList("/v1/compute-placements?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("aliases", edge.getList("/v1/provider-aliases?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "compute";
    }

    @GetMapping("/models")
    public String models(Model model,
                         @ModelAttribute("tenantId") UUID tenantId,
                         @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "models");
        model.addAttribute("models", edge.getList("/v1/models?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "models";
    }

    @GetMapping("/orchestration")
    public String orchestration(Model model,
                                @ModelAttribute("tenantId") UUID tenantId,
                                @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "orchestration");
        model.addAttribute("llmProviders", edge.getList("/v1/advisory/llm-providers", Map.class));
        model.addAttribute("runs", edge.getList("/v1/orchestration-runs?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("breakGlassGrants", edge.getList("/v1/policy/break-glass?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "orchestration";
    }

    @GetMapping("/settings/admin-suite")
    public String adminSuite(Model model,
                             @ModelAttribute("tenantId") UUID tenantId,
                             @ModelAttribute("environmentId") UUID envId) {
        model.addAttribute("activeNav", "admin-suite");
        model.addAttribute("finops", edge.getList("/v1/reporting/finops?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("pilots", edge.getList("/v1/reporting/pilots?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        model.addAttribute("conflicts", edge.getList("/v1/objectives/import-conflicts?tenantId=" + tenantId + "&environmentId=" + envId, Map.class));
        return "admin-suite";
    }
}
