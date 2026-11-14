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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Controller
class PrincipalDirectoryController {
  private final IdentityAccessClient identity;

  PrincipalDirectoryController(IdentityAccessClient identity) {
    this.identity = identity;
  }

  @GetMapping("/principals")
  String principals(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      HttpServletResponse response,
      @RequestParam(required = false) String cursor,
      Model model) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }

    model.addAttribute("selectedContext", context);
    model.addAttribute("requestCursor", cursor);
    model.addAttribute("principals", List.of());
    model.addAttribute("nextCursor", null);
    try {
      IdentityAccessClient.PrincipalPage page =
          identity.listPrincipals(client.getAccessToken().getTokenValue(), context, cursor);
      model.addAttribute("principals", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("principalError", "UX01 is unavailable; principals were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "principalError",
          "S01 did not return principals (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "S01 returned an invalid response.", error);
    }
    return "principals";
  }
}
