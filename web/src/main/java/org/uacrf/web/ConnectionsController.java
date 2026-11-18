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
class ConnectionsController {
  private static final List<String> READ = List.of("connections.connection.read");
  private final IdentityAccessClient identity;
  private final ConnectionsClient connections;

  ConnectionsController(IdentityAccessClient identity, ConnectionsClient connections) {
    this.identity = identity;
    this.connections = connections;
  }

  @GetMapping("/connections")
  String list(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String cursor, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("connections", List.of());
    try {
      String token = delegate(client, context);
      ConnectionsClient.ConnectionPage page = connections.list(token, cursor);
      model.addAttribute("connections", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("connectionError", "Identity or connections service is unavailable; connections were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("connectionError", "Connection access failed (HTTP " + error.getStatusCode().value() + ").");
    }
    return "connections";
  }

  @GetMapping("/connections/{id}/inventory")
  String inventory(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String id,
      @RequestParam(required = false) String cursor, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("inventory", List.of());
    String token;
    try {
      token = delegate(client, context);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("inventoryError", "Identity service is unavailable; inventory access was not authorized.");
      return "inventory";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("inventoryError", "Inventory access was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "inventory";
    }
    try {
      ConnectionsClient.Connection connection = connections.get(token, id);
      ConnectionsClient.InventoryPage page = connections.listInventory(token, id, cursor, connection.provider());
      model.addAttribute("connection", connection);
      model.addAttribute("inventory", page.items());
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("inventoryError", "Connections service is unavailable; inventory was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("inventoryError", "Inventory was not loaded (HTTP " + error.getStatusCode().value() + ").");
    }
    return "inventory";
  }

  private String delegate(OAuth2AuthorizedClient client, BrowserContext context) {
    return identity.createDelegation(
        client.getAccessToken().getTokenValue(), "S03", null, READ,
        context.tenantId(), context.environmentId()).accessToken();
  }
}
