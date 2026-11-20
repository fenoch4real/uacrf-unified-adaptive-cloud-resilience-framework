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
class EvidenceController {
  private final IdentityAccessClient identity;
  private final EvidenceClient evidence;

  EvidenceController(IdentityAccessClient identity, EvidenceClient evidence) {
    this.identity = identity;
    this.evidence = evidence;
  }

  @GetMapping("/evidence")
  String list(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String serviceId, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("evidence", List.of());
    String scope = serviceId == null || serviceId.isBlank() ? null : serviceId.strip();
    model.addAttribute("serviceId", scope);
    try {
      String token = identity.createDelegation(
          client.getAccessToken().getTokenValue(), "S04", scope, List.of("evidence.event.read"),
          context.tenantId(), context.environmentId()).accessToken();
      EvidenceClient.EvidencePage page = evidence.list(token, cursor, scope);
      model.addAttribute("evidence", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("evidenceError", "Identity or evidence service is unavailable; evidence was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("evidenceError", "Evidence access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("evidenceError", "Identity or evidence service returned an invalid response.");
    }
    return "evidence";
  }
}
