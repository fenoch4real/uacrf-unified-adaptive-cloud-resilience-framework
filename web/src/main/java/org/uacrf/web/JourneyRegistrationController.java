package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class JourneyRegistrationController {
  private static final List<String> JOURNEY_WRITE_CAPABILITIES =
      List.of("catalog.journey.write");
  private static final int MAX_OWNER_PAGES = 20;
  private static final int OWNER_ID_PREFIX_LENGTH = 150;

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  JourneyRegistrationController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/journeys/new")
  String newJourney(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    JourneyForm form = new JourneyForm("", "", "", UUID.randomUUID().toString(), null);
    return renderForm(client, context, form, List.of(""), response, model);
  }

  @PostMapping("/journeys/new/owners")
  String changeOwnerPage(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @ModelAttribute("journeyForm") JourneyForm form,
      @RequestParam(required = false) String ownerPageAction,
      @RequestParam(name = "ownerCursorHistory", required = false) List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }

    List<String> history = validHistory(submittedHistory, form.ownerCursor());
    if (history == null) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("journeyError", "The owner page history is invalid; reload the form.");
      form = form.withOwnerPage(null);
      replaceForm(model, form);
      return renderForm(client, context, form, List.of(""), response, model);
    }

    IdentityAccessClient.OwnerPage currentPage;
    try {
      currentPage =
          identity.ownerCandidates(
              client.getAccessToken().getTokenValue(),
              context,
              "JOURNEY",
              normalizedCursor(form.ownerCursor()));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; eligible owners were not loaded.");
      return renderForm(
          context, form, history, new IdentityAccessClient.OwnerPage(List.of(), null), model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize owner selection (HTTP " + error.getStatusCode().value() + ").");
      return renderForm(
          context, form, history, new IdentityAccessClient.OwnerPage(List.of(), null), model);
    }

    List<String> nextHistory = new ArrayList<>(history);
    String nextCursor = normalizedCursor(form.ownerCursor());
    if ("next".equals(ownerPageAction)) {
      if (currentPage.nextCursor() == null || history.size() >= MAX_OWNER_PAGES) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("journeyError", "There are no more eligible owners to display.");
        return renderForm(context, form, history, currentPage, model);
      }
      nextCursor = currentPage.nextCursor();
      nextHistory.add(nextCursor);
    } else if ("previous".equals(ownerPageAction)) {
      if (history.size() <= 1) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("journeyError", "There is no previous owner page.");
        return renderForm(context, form, history, currentPage, model);
      }
      nextHistory.removeLast();
      nextCursor = nextHistory.getLast();
      if (nextCursor.isEmpty()) {
        nextCursor = null;
      }
    } else {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("journeyError", "Choose a valid owner-list navigation action.");
      return renderForm(context, form, history, currentPage, model);
    }

    form = form.withOwnerPage(nextCursor);
    replaceForm(model, form);
    return renderForm(
        client, context, form, nextHistory, response, model);
  }

  @PostMapping("/journeys")
  String createJourney(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @Valid @ModelAttribute("journeyForm") JourneyForm form,
      BindingResult bindingResult,
      @RequestParam(name = "ownerCursorHistory", required = false) List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }

    List<String> history = validHistory(submittedHistory, form.ownerCursor());
    if (bindingResult.hasErrors() || history == null) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      addValidationErrors(bindingResult, model);
      if (history == null) {
        model.addAttribute("journeyError", "The owner page history is invalid; reload the form.");
        history = List.of("");
        form = form.withOwnerPage(null);
        replaceForm(model, form);
      }
      return renderForm(client, context, form, history, response, model);
    }

    IdentityAccessClient.OwnerPage ownerPage;
    try {
      ownerPage =
          identity.ownerCandidates(
              client.getAccessToken().getTokenValue(),
              context,
              "JOURNEY",
              normalizedCursor(form.ownerCursor()));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; the owner was not verified.");
      return renderForm(
          context, form, history, new IdentityAccessClient.OwnerPage(List.of(), null), model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize owner selection (HTTP " + error.getStatusCode().value() + ").");
      return renderForm(
          context, form, history, new IdentityAccessClient.OwnerPage(List.of(), null), model);
    }

    String submittedOwnerId = form.ownerId();
    boolean eligibleOwner =
        ownerPage.items().stream()
            .anyMatch(
                candidate ->
                    submittedOwnerId.equals(candidate.id())
                        && candidate.enabled()
                        && candidate.ownerEligible()
                        && "HUMAN".equals(candidate.kind()));
    if (!eligibleOwner) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      model.addAttribute(
          "journeyError",
          "Select an active, eligible human owner from the current S01 owner list.");
      return renderForm(context, form, history, ownerPage, model);
    }

    String delegationToken;
    try {
      IdentityAccessClient.Delegation delegation =
          identity.createDelegation(
              client.getAccessToken().getTokenValue(),
              "S02",
              null,
              JOURNEY_WRITE_CAPABILITIES,
              context.tenantId(),
              context.environmentId());
      delegationToken = delegation.accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; journey creation was not authorized.");
      return renderForm(context, form, history, ownerPage, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize journey creation (HTTP " + error.getStatusCode().value() + ").");
      return renderForm(context, form, history, ownerPage, model);
    }

    String journeyId = commandId(form.ownerId(), form.idempotencyKey());
    try {
      CatalogClient.MutationReceipt receipt =
          catalog.registerJourney(
              delegationToken,
              form.idempotencyKey(),
              new CatalogClient.JourneyRegistration(
                  journeyId, form.name(), form.ownerId(), form.purpose(), "DRAFT"));
      return "redirect:/journeys/" + receipt.id();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "journeyError",
          "S02 is unavailable; the journey result is unconfirmed. Retry this same form to safely replay the request.");
      return renderForm(context, form, history, ownerPage, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S02 did not register the journey (HTTP " + error.getStatusCode().value() + ").");
      return renderForm(context, form, history, ownerPage, model);
    }
  }

  private String renderForm(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      JourneyForm form,
      List<String> history,
      HttpServletResponse response,
      Model model) {
    try {
      IdentityAccessClient.OwnerPage page =
          identity.ownerCandidates(
              client.getAccessToken().getTokenValue(),
              context,
              "JOURNEY",
              normalizedCursor(form.ownerCursor()));
      return renderForm(context, form, history, page, model);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; eligible owners were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize owner selection (HTTP " + error.getStatusCode().value() + ").");
    }
    return renderForm(
        context, form, history, new IdentityAccessClient.OwnerPage(List.of(), null), model);
  }

  private String renderForm(
      BrowserContext context,
      JourneyForm form,
      List<String> history,
      IdentityAccessClient.OwnerPage ownerPage,
      Model model) {
    model.addAttribute("selectedContext", context);
    if (!model.containsAttribute("journeyForm")) {
      model.addAttribute("journeyForm", form);
    }
    model.addAttribute("ownerCandidates", ownerPage.items());
    model.addAttribute("nextOwnerCursor", ownerPage.nextCursor());
    model.addAttribute("ownerCursorHistory", history);
    model.addAttribute("hasPreviousOwners", history.size() > 1);
    return "journey-new";
  }

  private static BrowserContext selectedContext(HttpSession session) {
    Object contextAttribute = session.getAttribute(BrowserContext.SESSION_KEY);
    return contextAttribute instanceof BrowserContext context ? context : null;
  }

  private static List<String> validHistory(List<String> submitted, String cursor) {
    List<String> history =
        submitted == null || submitted.isEmpty()
            ? List.of(cursor == null || cursor.isBlank() ? "" : cursor)
            : submitted;
    String normalizedCursor = cursor == null || cursor.isBlank() ? "" : cursor;
    if (history.size() > MAX_OWNER_PAGES
        || history.stream().anyMatch(value -> value == null || value.length() > 500)
        || !normalizedCursor.equals(history.getLast())) {
      return null;
    }
    return new ArrayList<>(history);
  }

  private static String commandId(String ownerId, String idempotencyKey) {
    String ownerPrefix =
        ownerId.substring(0, Math.min(ownerId.length(), OWNER_ID_PREFIX_LENGTH));
    return "cmd_" + ownerPrefix + "_" + idempotencyKey;
  }

  private static String normalizedCursor(String cursor) {
    return cursor == null || cursor.isBlank() ? null : cursor;
  }

  private static void addValidationErrors(BindingResult bindingResult, Model model) {
    model.addAttribute(
        "validationErrors",
        bindingResult.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    model.addAttribute("nameError", fieldError(bindingResult, "name"));
    model.addAttribute("purposeError", fieldError(bindingResult, "purpose"));
    model.addAttribute("ownerError", fieldError(bindingResult, "ownerId"));
    model.addAttribute("invalidName", bindingResult.hasFieldErrors("name"));
    model.addAttribute("invalidPurpose", bindingResult.hasFieldErrors("purpose"));
    model.addAttribute("invalidOwner", bindingResult.hasFieldErrors("ownerId"));
  }

  private static String fieldError(BindingResult bindingResult, String field) {
    return bindingResult.hasFieldErrors(field)
        ? bindingResult.getFieldError(field).getDefaultMessage()
        : null;
  }

  private static void replaceForm(Model model, JourneyForm form) {
    model.asMap().remove("journeyForm");
    model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "journeyForm");
    model.addAttribute("journeyForm", form);
  }

  record JourneyForm(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 2000) String purpose,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String ownerId,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
          String idempotencyKey,
      String ownerCursor) {
    JourneyForm withOwnerPage(String cursor) {
      return new JourneyForm(name, purpose, "", idempotencyKey, cursor);
    }
  }
}
