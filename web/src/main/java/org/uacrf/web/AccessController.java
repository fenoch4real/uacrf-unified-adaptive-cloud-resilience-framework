package org.uacrf.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class AccessController {
  private final IdentityAccessClient identity;

  AccessController(IdentityAccessClient identity) {
    this.identity = identity;
  }

  @GetMapping("/access")
  String currentAccess(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      Model model) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    try {
      model.addAttribute(
          "access",
          identity.currentAccess(
              client.getAccessToken().getTokenValue(),
              context.tenantId(),
              context.environmentId()));
    } catch (ResourceAccessException error) {
      model.addAttribute("accessError", "UX01 is unavailable; current permissions were not loaded.");
    } catch (RestClientResponseException error) {
      String message;
      if (error.getStatusCode() == HttpStatus.FORBIDDEN) {
        message = "S01 denied access in the active local context.";
      } else if (error.getStatusCode() == HttpStatus.BAD_GATEWAY) {
        message = "S01 is unavailable; current permissions were not loaded.";
      } else {
        message =
            "S01 did not return current permissions (HTTP "
                + error.getStatusCode().value()
                + ").";
      }
      model.addAttribute("accessError", message);
    }
    return "access";
  }
}
