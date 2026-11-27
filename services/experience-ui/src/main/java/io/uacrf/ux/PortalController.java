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

    @GetMapping({"/dependencies", "/assess", "/control", "/recover", "/evidence", "/knowledge", "/advisory"})
    public String upcomingMilestonePlaceholder(Model model, jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI().replace("/", "");
        model.addAttribute("activeNav", path);
        model.addAttribute("moduleTitle", path.substring(0, 1).toUpperCase() + path.substring(1));
        return "milestone-placeholder";
    }
}
