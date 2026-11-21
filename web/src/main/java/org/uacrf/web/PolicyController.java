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
class PolicyController {
  private final IdentityAccessClient identity;
  private final PolicyClient policies;

  PolicyController(IdentityAccessClient identity, PolicyClient policies) {
    this.identity = identity;
    this.policies = policies;
  }

  @GetMapping("/policies")
  String list(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam(required = false) String serviceId,
      @RequestParam(required = false) String cursor, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("serviceId", serviceId);
    if (serviceId == null && cursor == null) return "policies";
    if (!PolicyClient.identifier(serviceId) || (cursor != null && !PolicyClient.validCursor(cursor))) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("policyError", "Invalid service ID or page cursor.");
      return "policies";
    }
    try {
      String token = delegation(client, context, serviceId);
      model.addAttribute("page", policies.list(token, serviceId, cursor));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity or policy service is unavailable; policies were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity or policy service returned an invalid response.");
    }
    return "policies";
  }

  @GetMapping("/policies/detail")
  String detail(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String serviceId,
      @RequestParam String id, @RequestParam String revision, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("serviceId", serviceId);
    Long version = null;
    try {
      if (!PolicyClient.identifier(serviceId) || !PolicyClient.identifier(id)
          || (!"current".equals(revision) && !revision.matches("[1-9][0-9]{0,18}"))) throw new NumberFormatException();
      if (!"current".equals(revision)) version = Long.parseLong(revision);
    } catch (NumberFormatException error) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("policyError", "Invalid service ID, policy ID, or revision.");
      return "policy-detail";
    }
    try {
      String token = delegation(client, context, serviceId);
      model.addAttribute("policy", version == null ? policies.readCurrent(token, serviceId, id)
          : policies.read(token, serviceId, id, version));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity or policy service is unavailable; policy was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity or policy service returned invalid policy details.");
    }
    return "policy-detail";
  }

  private String delegation(OAuth2AuthorizedClient client, BrowserContext context, String serviceId) {
    return identity.createDelegation(client.getAccessToken().getTokenValue(), "S10", serviceId,
        List.of("authorization.policy.read"), context.tenantId(), context.environmentId()).accessToken();
  }
}
