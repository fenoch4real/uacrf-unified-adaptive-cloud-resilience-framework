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

@Controller
class AgentWorkloadController {
  private static final List<String> AGENT_READ_CAPABILITIES =
      List.of("catalog.agent.read");

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  AgentWorkloadController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/agents")
  String agents(
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
    model.addAttribute("agents", List.of());
    model.addAttribute("nextCursor", null);

    String delegationToken;
    try {
      IdentityAccessClient.Delegation delegation =
          identity.createDelegation(
              client.getAccessToken().getTokenValue(),
              "S02",
              null,
              AGENT_READ_CAPABILITIES,
              context.tenantId(),
              context.environmentId());
      delegationToken = delegation.accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "agentError", "UX01 is unavailable; agent workload access was not authorized.");
      return "agents";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S01 did not authorize agent workload access (HTTP "
              + error.getStatusCode().value()
              + ").");
      return "agents";
    }

    try {
      CatalogClient.AgentPage page = catalog.listAgents(delegationToken, cursor);
      model.addAttribute("agents", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("agentError", "S02 is unavailable; agent workloads were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S02 did not return agent workloads (HTTP " + error.getStatusCode().value() + ").");
    }
    return "agents";
  }
}
