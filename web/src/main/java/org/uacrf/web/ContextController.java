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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class ContextController {
  private final IdentityAccessClient identity;

  ContextController(IdentityAccessClient identity) {
    this.identity = identity;
  }

  @GetMapping("/contexts")
  String contexts(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @RequestParam(required = false) String cursor,
      Model model) {
    addContexts(client, cursor, model);
    return "contexts";
  }

  @PostMapping("/contexts")
  String selectContext(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @RequestParam String tenantId,
      @RequestParam String environmentId,
      @RequestParam(required = false) String cursor,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    try {
        IdentityAccessClient.CurrentContextList page =
            identity.currentContexts(client.getAccessToken().getTokenValue(), cursor);
      List<IdentityAccessClient.CurrentContext> contexts =
            page.items();
      model.addAttribute("contexts", contexts);
      model.addAttribute("requestCursor", cursor);
        model.addAttribute("nextCursor", page.nextCursor());
      IdentityAccessClient.CurrentContext selected =
          contexts.stream()
              .filter(
                  context ->
                      context.tenantId().equals(tenantId)
                          && context.environmentId().equals(environmentId))
              .findFirst()
              .orElse(null);
      if (selected == null) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        model.addAttribute("contextError", "That context is not currently available to this identity.");
        return "contexts";
      }
      session.setAttribute(
          BrowserContext.SESSION_KEY,
          new BrowserContext(
              selected.tenantId(),
              selected.tenantName(),
              selected.environmentId(),
              selected.environmentName()));
      return "redirect:/access";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("contextError", "UX01 is unavailable; no context was selected.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "contextError",
          "S01 did not return available contexts (HTTP " + error.getStatusCode().value() + ").");
    }
    model.addAttribute("contexts", List.of());
    model.addAttribute("nextCursor", null);
    return "contexts";
  }

  private void addContexts(OAuth2AuthorizedClient client, String cursor, Model model) {
    model.addAttribute("requestCursor", cursor);
    try {
      IdentityAccessClient.CurrentContextList result =
          identity.currentContexts(client.getAccessToken().getTokenValue(), cursor);
      model.addAttribute("contexts", result.items());
      model.addAttribute("nextCursor", result.nextCursor());
    } catch (ResourceAccessException error) {
      model.addAttribute("contextError", "UX01 is unavailable; contexts were not loaded.");
      model.addAttribute("contexts", List.of());
      model.addAttribute("nextCursor", null);
      model.addAttribute("requestCursor", cursor);
    } catch (RestClientResponseException error) {
      model.addAttribute(
          "contextError",
          "S01 did not return available contexts (HTTP " + error.getStatusCode().value() + ").");
      model.addAttribute("contexts", List.of());
      model.addAttribute("nextCursor", null);
      model.addAttribute("requestCursor", cursor);
    }
  }
}
