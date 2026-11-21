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
class ObjectivesController {
  private final IdentityAccessClient identity;
  private final ObjectivesClient objectives;

  ObjectivesController(IdentityAccessClient identity, ObjectivesClient objectives) {
    this.identity = identity;
    this.objectives = objectives;
  }

  @GetMapping("/objectives")
  String list(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String workloadId, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) return "redirect:/contexts";
    String scope = workloadId == null || workloadId.isBlank() ? null : workloadId.strip();
    model.addAttribute("selectedContext", context);
    model.addAttribute("workloadId", scope);
    model.addAttribute("objectives", List.of());
    try {
      String token = identity.createDelegation(
          client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read"), context.tenantId(), context.environmentId()).accessToken();
      ObjectivesClient.ObjectivePage page = objectives.list(token, cursor, scope);
      model.addAttribute("objectives", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("objectivesError", "Identity or objectives service is unavailable; objectives were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("objectivesError", "Objective access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("objectivesError", "Identity or objectives service returned an invalid response.");
    }
    return "objectives";
  }

  @GetMapping("/objectives/history")
  String history(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String id, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("objectiveId", id);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("history", objectives.decisionHistory(token, id));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("historyError", "Identity or objectives service is unavailable; history was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("historyError", "Objective history access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("historyError", "Identity or objectives service returned an invalid response.");
    }
    return "objective-history";
  }
}
