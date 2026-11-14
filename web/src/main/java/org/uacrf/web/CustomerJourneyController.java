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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class CustomerJourneyController {
  private static final List<String> JOURNEY_READ_CAPABILITIES =
      List.of("catalog.journey.read");

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  CustomerJourneyController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/journeys")
  String journeys(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      HttpServletResponse response,
      @RequestParam(required = false) String cursor,
      Model model) {
    Object contextAttribute = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(contextAttribute instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }

    model.addAttribute("selectedContext", context);
    model.addAttribute("journeys", List.of());
    model.addAttribute("requestCursor", cursor);
    model.addAttribute("nextCursor", null);

    String delegationToken;
    try {
      IdentityAccessClient.Delegation delegation =
          identity.createDelegation(
              client.getAccessToken().getTokenValue(),
              "S02",
              null,
              JOURNEY_READ_CAPABILITIES,
              context.tenantId(),
              context.environmentId());
      delegationToken = delegation.accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; journey access was not authorized.");
      return "journeys";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize journey access (HTTP " + error.getStatusCode().value() + ").");
      return "journeys";
    }

    try {
      CatalogClient.JourneyPage page = catalog.listJourneys(delegationToken, cursor);
      model.addAttribute("journeys", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "S02 is unavailable; journeys were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S02 did not return journeys (HTTP " + error.getStatusCode().value() + ").");
    }
    return "journeys";
  }

  @GetMapping("/journeys/{id}")
  String journey(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String id,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    Object contextAttribute = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(contextAttribute instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }

    model.addAttribute("selectedContext", context);
    String delegationToken;
    try {
      IdentityAccessClient.Delegation delegation =
          identity.createDelegation(
              client.getAccessToken().getTokenValue(),
              "S02",
              null,
              JOURNEY_READ_CAPABILITIES,
              context.tenantId(),
              context.environmentId());
      delegationToken = delegation.accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; journey access was not authorized.");
      return "journey";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize journey access (HTTP " + error.getStatusCode().value() + ").");
      return "journey";
    }

    try {
      model.addAttribute("journey", catalog.getJourney(delegationToken, id));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "S02 is unavailable; journey details were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S02 did not return journey details (HTTP " + error.getStatusCode().value() + ").");
    }
    return "journey";
  }
}
