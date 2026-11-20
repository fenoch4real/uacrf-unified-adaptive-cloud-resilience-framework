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
class KnowledgeController {
  private static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private final IdentityAccessClient identity;
  private final KnowledgeClient knowledge;

  KnowledgeController(IdentityAccessClient identity, KnowledgeClient knowledge) {
    this.identity = identity;
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge")
  String list(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String classification,
      @RequestParam(required = false) String itemId,
      @RequestParam(required = false) String purpose, Model model) {
    Object selected = session.getAttribute(BrowserContext.SESSION_KEY);
    if (!(selected instanceof BrowserContext context)) return "redirect:/contexts";
    if (classification == null) classification = "PUBLIC";
    String itemFilter = optional(itemId);
    String purposeFilter = optional(purpose);
    model.addAttribute("selectedContext", context);
    model.addAttribute("classification", classification);
    model.addAttribute("itemId", itemFilter);
    model.addAttribute("purpose", purposeFilter);
    model.addAttribute("items", List.of());
    KnowledgeClient.Classification ceiling;
    try {
      ceiling = KnowledgeClient.Classification.valueOf(classification);
    } catch (IllegalArgumentException error) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("knowledgeError", "Select a valid classification.");
      return "knowledge";
    }
    if ((itemFilter != null && !itemFilter.matches(IDENTIFIER))
        || (purposeFilter != null && !purposeFilter.matches(IDENTIFIER))) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("knowledgeError", "Item ID and purpose must start with a letter or digit and use at most 200 letters, digits, dots, underscores, colons, or hyphens.");
      return "knowledge";
    }
    List<String> capabilities = switch (ceiling) {
      case PUBLIC -> List.of("knowledge.item.read");
      case INTERNAL -> List.of("knowledge.item.read", "knowledge.classification.internal.read");
      case RESTRICTED -> List.of("knowledge.item.read", "knowledge.classification.restricted.read");
    };
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          capabilities, context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("items", knowledge.list(token, itemFilter, purposeFilter, ceiling));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("knowledgeError", "Identity or knowledge service is unavailable; metadata was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("knowledgeError", "Knowledge access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("knowledgeError", "Identity or knowledge service returned an invalid response.");
    }
    return "knowledge";
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
