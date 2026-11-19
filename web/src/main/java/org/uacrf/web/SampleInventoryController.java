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
class SampleInventoryController {
  private final IdentityAccessClient identity;
  private final SampleInventoryImport importer;

  SampleInventoryController(IdentityAccessClient identity, SampleInventoryImport importer) {
    this.identity = identity;
    this.importer = importer;
  }

  @GetMapping("/connections/sample-import")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String cursor, Model model) {
    BrowserContext context = context(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    return render(client, context, cursor, null, response, model);
  }

  @PostMapping("/connections/sample-import")
  String submit(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String ownerId,
      @RequestParam(required = false) String cursor, Model model) {
    BrowserContext context = context(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    IdentityAccessClient.OwnerPage page = owners(client, context, cursor, response, model);
    if (page == null) {
      return page(context, cursor, ownerId, new IdentityAccessClient.OwnerPage(List.of(), null), model);
    }
    boolean eligible = ownerId != null && page.items().stream().anyMatch(owner ->
        owner != null && ownerId.equals(owner.id()) && owner.enabled()
            && owner.ownerEligible() && "HUMAN".equals(owner.kind()));
    if (!eligible) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      model.addAttribute("importError", "Select an eligible human owner from the current list.");
      return page(context, cursor, ownerId, page, model);
    }
    try {
      SampleInventoryImport.Result result = importer.run(
          client.getAccessToken().getTokenValue(), context, client.getPrincipalName(), ownerId);
      model.addAttribute("importResult", result);
    } catch (SampleInventoryImport.ImportFailure error) {
      response.setStatus(error.status());
      model.addAttribute("importError", error.getMessage());
      model.addAttribute("partialConnectionId", error.connectionId());
      model.addAttribute("completedCount", error.completed());
    }
    return page(context, cursor, ownerId, page, model);
  }

  private String render(OAuth2AuthorizedClient client, BrowserContext context, String cursor,
      String owner, HttpServletResponse response, Model model) {
    IdentityAccessClient.OwnerPage page = owners(client, context, cursor, response, model);
    return page(context, cursor, owner,
        page == null ? new IdentityAccessClient.OwnerPage(List.of(), null) : page, model);
  }

  private IdentityAccessClient.OwnerPage owners(OAuth2AuthorizedClient client, BrowserContext context,
      String cursor, HttpServletResponse response, Model model) {
    if (cursor != null && cursor.length() > 500) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("importError", "The owner page cursor is invalid.");
      return null;
    }
    try {
      IdentityAccessClient.OwnerPage page = identity.ownerCandidates(client.getAccessToken().getTokenValue(), context,
          "CONNECTION", cursor == null || cursor.isBlank() ? null : cursor);
      if (page == null || page.items() == null || page.items().stream().anyMatch(owner ->
          owner == null || owner.id() == null
              || !owner.id().matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
              || owner.displayName() == null || owner.displayName().isBlank()
              || !"HUMAN".equals(owner.kind()) || !owner.enabled() || !owner.ownerEligible())) {
        throw new IllegalStateException("Identity returned an invalid owner page.");
      }
      return page;
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("importError", "Identity service is unavailable; eligible owners were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("importError",
          "Owner selection was not authorized (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("importError", "Identity returned an invalid eligible-owner list.");
    }
    return null;
  }

  private String page(BrowserContext context, String cursor, String owner,
      IdentityAccessClient.OwnerPage page, Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("ownerCandidates", page.items());
    model.addAttribute("nextOwnerCursor", page.nextCursor());
    model.addAttribute("ownerCursor", cursor);
    model.addAttribute("selectedOwner", owner);
    return "sample-inventory-import";
  }

  private static BrowserContext context(HttpSession session) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    return selected instanceof BrowserContext context ? context : null;
  }
}
