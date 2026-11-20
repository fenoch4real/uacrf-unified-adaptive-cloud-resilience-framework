package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class AssessmentController {
  private final IdentityAccessClient identity;
  private final AssessmentClient assessment;

  AssessmentController(IdentityAccessClient identity, AssessmentClient assessment) {
    this.identity = identity;
    this.assessment = assessment;
  }

  @GetMapping("/assessment-cases")
  String list(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String workloadId, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) return "redirect:/contexts";
    String filter = workloadId == null || workloadId.isBlank() ? null : workloadId.strip();
    model.addAttribute("selectedContext", context);
    model.addAttribute("workloadId", filter);
    model.addAttribute("cases", List.of());
    try {
      String token = identity.createDelegation(
          client.getAccessToken().getTokenValue(), "S07", null, List.of("assessment.case.read"),
          context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("cases", assessment.listCases(token, filter));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("assessmentError", "Identity or assessment service is unavailable; cases were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("assessmentError", "Assessment access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("assessmentError", "Identity or assessment service returned an invalid response.");
    }
    return "assessment-cases";
  }

  @GetMapping("/assessment-cases/{caseId}/seal")
  String sealForm(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.case.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("caseItem", assessment.getCase(token, caseId));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("caseError", "Identity or assessment service is unavailable; the case was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("caseError", "Case access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("caseError", "Identity or assessment service returned invalid case details.");
    }
    return "assessment-case-seal";
  }

  @PostMapping("/assessment-cases/{caseId}/seal")
  String seal(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId,
      @RequestParam long expectedRevision, @RequestParam String tenantId,
      @RequestParam String environmentId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    if (!context.tenantId().equals(tenantId) || !context.environmentId().equals(environmentId)) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("caseError", "The selected context changed. Return to the original context and review the case before sealing.");
      return "assessment-case-seal-result";
    }
    if (expectedRevision < 1 || expectedRevision == Long.MAX_VALUE) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("caseError", "Invalid case revision. Reload the case before sealing.");
      return "assessment-case-seal-result";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.case.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("caseError", "Identity service is unavailable; sealing was not authorized.");
      return "assessment-case-seal-result";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("caseError", "Sealing was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "assessment-case-seal-result";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("caseError", "Identity service returned an invalid response; sealing was not authorized.");
      return "assessment-case-seal-result";
    }
    try {
      model.addAttribute("caseItem", assessment.sealCase(token, caseId, expectedRevision));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("caseError", "Sealing is unconfirmed. Review the current case state before retrying.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("caseError", "Sealing failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Review the current case state before retrying.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("caseError", "The seal response was invalid; sealing is unconfirmed. Review the current case state before retrying.");
    }
    return "assessment-case-seal-result";
  }

  @GetMapping("/assessment-cases/{caseId}/evaluations")
  String evaluations(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    model.addAttribute("evaluations", List.of());
    try {
      String token = identity.createDelegation(
          client.getAccessToken().getTokenValue(), "S07", null, List.of("assessment.evaluation.read"),
          context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("evaluations", assessment.listEvaluations(token, caseId));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("assessmentError", "Identity or assessment service is unavailable; evaluations were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("assessmentError", "Evaluation access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("assessmentError", "Identity or assessment service returned an invalid response.");
    }
    return "assessment-evaluations";
  }

  @GetMapping("/assessment-recommendations")
  String recommendations(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String workloadId, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("workloadId", workloadId);
    model.addAttribute("recommendations", List.of());
    if (workloadId.isBlank() || workloadId.length() > 200) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("assessmentError", "A valid workload ID is required.");
      return "assessment-recommendations";
    }
    try {
      String token = identity.createDelegation(
          client.getAccessToken().getTokenValue(), "S07", null, List.of("assessment.recommendation.read"),
          context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("recommendations", assessment.listRecommendations(token, workloadId));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("assessmentError", "Identity or assessment service is unavailable; recommendations were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("assessmentError", "Recommendation access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("assessmentError", "Identity or assessment service returned an invalid response.");
    }
    return "assessment-recommendations";
  }

  @GetMapping("/assessment-cases/{caseId}/incidents")
  String incidents(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId,
      @RequestParam(required = false) String after, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    model.addAttribute("items", List.of());
    if (after != null && !AssessmentClient.validCursor(after)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("assessmentError", "Invalid incident-page cursor.");
      return "assessment-incidents";
    }
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.case.read", "assessment.incident.read"), context.tenantId(), context.environmentId()).accessToken();
      var page = assessment.listIncidents(token, caseId, after);
      model.addAttribute("items", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("assessmentError", "Identity or assessment service is unavailable; incidents were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("assessmentError", "Incident access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("assessmentError", "Identity or assessment service returned an invalid incident page.");
    }
    return "assessment-incidents";
  }

  @GetMapping("/assessment-incidents/{incidentId}/learning-records")
  String learningRecords(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String incidentId,
      @RequestParam(required = false) String after, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("incidentId", incidentId);
    model.addAttribute("items", List.of());
    if (after != null && !AssessmentClient.validCursor(after)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("assessmentError", "Invalid learning-record page cursor.");
      return "assessment-learning-records";
    }
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.incident.read", "assessment.learning.read"), context.tenantId(), context.environmentId()).accessToken();
      var page = assessment.listLearningRecords(token, incidentId, after);
      model.addAttribute("items", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("assessmentError", "Identity or assessment service is unavailable; learning records were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("assessmentError", "Learning-record access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("assessmentError", "Identity or assessment service returned an invalid learning-record page.");
    }
    return "assessment-learning-records";
  }
}
