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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class AgentRegistrationController {
  private static final List<String> AGENT_WRITE_CAPABILITIES =
      List.of("catalog.agent.write");
  private static final int MAX_OWNER_PAGES = 20;
  private static final int OWNER_ID_PREFIX_LENGTH = 150;
  private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  AgentRegistrationController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/agents/new")
  String newStandaloneAgent(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    return renderNewAgent(client, null, session, response, model);
  }

  @GetMapping("/services/{serviceId}/agents/new")
  String newServiceAgent(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String serviceId,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    return renderNewAgent(client, serviceId, session, response, model);
  }

  @PostMapping("/agents/new/owners")
  String changeStandaloneOwnerPage(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @ModelAttribute("agentForm") AgentForm form,
      @RequestParam(required = false) String ownerPageAction,
      @RequestParam(name = "ownerCursorHistory", required = false) List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    return changeOwnerPage(
        client, null, form, ownerPageAction, submittedHistory, session, response, model);
  }

  @PostMapping("/services/{serviceId}/agents/new/owners")
  String changeServiceOwnerPage(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String serviceId,
      @ModelAttribute("agentForm") AgentForm form,
      @RequestParam(required = false) String ownerPageAction,
      @RequestParam(name = "ownerCursorHistory", required = false) List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    return changeOwnerPage(
        client, serviceId, form, ownerPageAction, submittedHistory, session, response, model);
  }

  @PostMapping("/agents")
  String registerStandaloneAgent(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @Valid @ModelAttribute("agentForm") AgentForm form,
      BindingResult bindingResult,
      @RequestParam(name = "ownerCursorHistory", required = false) List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model,
      RedirectAttributes redirectAttributes) {
    return registerAgent(
        client,
        null,
        form,
        bindingResult,
        submittedHistory,
        session,
        response,
        model,
        redirectAttributes);
  }

  @PostMapping("/services/{serviceId}/agents")
  String registerServiceAgent(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String serviceId,
      @Valid @ModelAttribute("agentForm") AgentForm form,
      BindingResult bindingResult,
      @RequestParam(name = "ownerCursorHistory", required = false) List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model,
      RedirectAttributes redirectAttributes) {
    return registerAgent(
        client,
        serviceId,
        form,
        bindingResult,
        submittedHistory,
        session,
        response,
        model,
        redirectAttributes);
  }

  private String renderNewAgent(
      OAuth2AuthorizedClient client,
      String serviceId,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (!validServiceId(serviceId)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("agentError", "The service identifier is malformed.");
    }
    AgentForm form =
        new AgentForm("", "SINGLE_AGENT", "", "", "", UUID.randomUUID().toString(), null);
    return renderForm(
        client, context, serviceId, form, List.of(""), response, model);
  }

  private String changeOwnerPage(
      OAuth2AuthorizedClient client,
      String serviceId,
      AgentForm form,
      String ownerPageAction,
      List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (!validServiceId(serviceId)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("agentError", "The service identifier is malformed.");
      return renderForm(
          context,
          serviceId,
          form,
          List.of(""),
          new IdentityAccessClient.OwnerPage(List.of(), null),
          model);
    }

    List<String> history = validHistory(submittedHistory, form.ownerCursor());
    if (history == null) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("agentError", "The owner page history is invalid; reload the form.");
      form = form.withOwnerPage(null);
      history = List.of("");
      replaceForm(model, form);
    }

    IdentityAccessClient.OwnerPage currentPage =
        loadOwners(
            client,
            context,
            serviceId,
            normalizedCursor(form.ownerCursor()),
            response,
            model);
    if (currentPage == null) {
      return renderForm(context, serviceId, form, history, null, model);
    }

    List<String> nextHistory = new ArrayList<>(history);
    String nextCursor = normalizedCursor(form.ownerCursor());
    if ("next".equals(ownerPageAction)) {
      if (currentPage.nextCursor() == null || history.size() >= MAX_OWNER_PAGES) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("agentError", "There are no more eligible owners to display.");
        return renderForm(context, serviceId, form, history, currentPage, model);
      }
      nextCursor = currentPage.nextCursor();
      nextHistory.add(nextCursor);
    } else if ("previous".equals(ownerPageAction)) {
      if (history.size() <= 1) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("agentError", "There is no previous owner page.");
        return renderForm(context, serviceId, form, history, currentPage, model);
      }
      nextHistory.removeLast();
      nextCursor = nextHistory.getLast();
      if (nextCursor.isEmpty()) {
        nextCursor = null;
      }
    } else {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("agentError", "Choose a valid owner-list navigation action.");
      return renderForm(context, serviceId, form, history, currentPage, model);
    }

    form = form.withOwnerPage(nextCursor);
    replaceForm(model, form);
    IdentityAccessClient.OwnerPage nextPage =
        loadOwners(client, context, serviceId, normalizedCursor(nextCursor), response, model);
    return renderForm(context, serviceId, form, nextHistory, nextPage, model);
  }

  private String registerAgent(
      OAuth2AuthorizedClient client,
      String serviceId,
      AgentForm form,
      BindingResult bindingResult,
      List<String> submittedHistory,
      HttpSession session,
      HttpServletResponse response,
      Model model,
      RedirectAttributes redirectAttributes) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (!validServiceId(serviceId)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("agentError", "The service identifier is malformed.");
      addValidationErrors(bindingResult, model);
      return renderForm(
          client, context, serviceId, form, List.of(""), response, model);
    }

    List<String> history = validHistory(submittedHistory, form.ownerCursor());
    if (bindingResult.hasErrors() || history == null) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      addValidationErrors(bindingResult, model);
      if (history == null) {
        model.addAttribute("agentError", "The owner page history is invalid; reload the form.");
        history = List.of("");
        form = form.withOwnerPage(null);
        replaceForm(model, form);
      }
      return renderForm(client, context, serviceId, form, history, response, model);
    }

    IdentityAccessClient.OwnerPage owners =
        loadOwners(
            client,
            context,
            serviceId,
            normalizedCursor(form.ownerCursor()),
            response,
            model);
    if (owners == null) {
      return renderForm(context, serviceId, form, history, null, model);
    }

    String submittedOwnerId = form.ownerId();
    boolean eligibleOwner =
        owners.items().stream()
            .anyMatch(
                candidate ->
                    submittedOwnerId.equals(candidate.id())
                        && candidate.enabled()
                        && candidate.ownerEligible()
                        && "HUMAN".equals(candidate.kind()));
    if (!eligibleOwner) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      model.addAttribute(
          "agentError",
          "Select an active, eligible human owner from the current S01 owner list.");
      return renderForm(context, serviceId, form, history, owners, model);
    }

    String delegationToken;
    try {
      delegationToken =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  serviceId,
                  AGENT_WRITE_CAPABILITIES,
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "agentError", "UX01 is unavailable; agent registration was not authorized.");
      return renderForm(context, serviceId, form, history, owners, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S01 did not authorize agent registration (HTTP "
              + error.getStatusCode().value()
              + ").");
      return renderForm(context, serviceId, form, history, owners, model);
    }

    String agentId = commandId(form.ownerId(), form.idempotencyKey());
    try {
      CatalogClient.MutationReceipt receipt =
          catalog.registerAgent(
              delegationToken,
              form.idempotencyKey(),
              new CatalogClient.AgentRegistration(
                  agentId,
                  serviceId,
                  form.name(),
                  form.kind(),
                  form.ownerId(),
                  form.runtimePrincipalId(),
                  form.purpose()));
      redirectAttributes.addFlashAttribute(
          "agentSuccess",
          "Agent workload "
              + receipt.id()
              + " was "
              + receipt.result().toLowerCase()
              + ". No runtime action was performed.");
      return serviceId == null
          ? "redirect:/agents/new"
          : "redirect:/services/" + serviceId + "/agents/new";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "agentError",
          "S02 is unavailable; the result is unconfirmed. Retry this same form to safely replay the request.");
      return renderForm(context, serviceId, form, history, owners, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S02 did not register the agent workload (HTTP "
              + error.getStatusCode().value()
              + ").");
      return renderForm(context, serviceId, form, history, owners, model);
    }
  }

  private IdentityAccessClient.OwnerPage loadOwners(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String serviceId,
      String cursor,
      HttpServletResponse response,
      Model model) {
    try {
      return identity.ownerCandidates(
          client.getAccessToken().getTokenValue(), context, "AGENT", serviceId, cursor);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("agentError", "UX01 is unavailable; eligible owners were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S01 did not authorize agent owner selection (HTTP "
              + error.getStatusCode().value()
              + ").");
    }
    return null;
  }

  private String renderForm(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String serviceId,
      AgentForm form,
      List<String> history,
      HttpServletResponse response,
      Model model) {
    IdentityAccessClient.OwnerPage owners =
        validServiceId(serviceId)
            ? loadOwners(
                client,
                context,
                serviceId,
                normalizedCursor(form.ownerCursor()),
                response,
                model)
            : null;
    return renderForm(context, serviceId, form, history, owners, model);
  }

  private String renderForm(
      BrowserContext context,
      String serviceId,
      AgentForm form,
      List<String> history,
      IdentityAccessClient.OwnerPage owners,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("serviceId", serviceId);
    model.addAttribute(
        "registrationAction",
        serviceId == null ? "/agents" : "/services/" + serviceId + "/agents");
    model.addAttribute(
        "ownerPageActionUrl",
        serviceId == null
            ? "/agents/new/owners"
            : "/services/" + serviceId + "/agents/new/owners");
    model.addAttribute("ownerCandidates", owners == null ? List.of() : owners.items());
    model.addAttribute("nextOwnerCursor", owners == null ? null : owners.nextCursor());
    model.addAttribute("ownerCursorHistory", history);
    model.addAttribute("hasPreviousOwners", history.size() > 1);
    if (!model.containsAttribute("agentForm")) {
      model.addAttribute("agentForm", form);
    }
    return "agent-new";
  }

  private static BrowserContext selectedContext(HttpSession session) {
    Object contextAttribute = session.getAttribute(BrowserContext.SESSION_KEY);
    return contextAttribute instanceof BrowserContext context ? context : null;
  }

  private static boolean validServiceId(String serviceId) {
    return serviceId == null || serviceId.matches(ID_PATTERN);
  }

  private static String commandId(String ownerId, String idempotencyKey) {
    String ownerPrefix = ownerId.substring(0, Math.min(ownerId.length(), OWNER_ID_PREFIX_LENGTH));
    return "cmd_" + ownerPrefix + "_" + idempotencyKey;
  }

  private static String normalizedCursor(String cursor) {
    return cursor == null || cursor.isBlank() ? null : cursor;
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

  private static void replaceForm(Model model, AgentForm form) {
    model.asMap().remove("agentForm");
    model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "agentForm");
    model.addAttribute("agentForm", form);
  }

  private static void addValidationErrors(BindingResult bindingResult, Model model) {
    model.addAttribute(
        "validationErrors",
        bindingResult.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    model.addAttribute("invalidName", bindingResult.hasFieldErrors("name"));
    model.addAttribute("invalidKind", bindingResult.hasFieldErrors("kind"));
    model.addAttribute("invalidOwner", bindingResult.hasFieldErrors("ownerId"));
    model.addAttribute(
        "invalidRuntimePrincipal", bindingResult.hasFieldErrors("runtimePrincipalId"));
    model.addAttribute("invalidPurpose", bindingResult.hasFieldErrors("purpose"));
  }

  record AgentForm(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "^(SINGLE_AGENT|MULTI_AGENT)$") String kind,
      @NotBlank @Pattern(regexp = ID_PATTERN) String ownerId,
      @NotBlank @Pattern(regexp = ID_PATTERN) String runtimePrincipalId,
      @NotBlank @Size(max = 2048) String purpose,
      @NotBlank @Pattern(regexp = UUID_PATTERN) String idempotencyKey,
      String ownerCursor) {
    AgentForm withOwnerPage(String cursor) {
      return new AgentForm(
          name, kind, "", runtimePrincipalId, purpose, idempotencyKey, cursor);
    }
  }
}
