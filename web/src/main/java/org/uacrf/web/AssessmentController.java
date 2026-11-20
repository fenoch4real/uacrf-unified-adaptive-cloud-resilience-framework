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
}
