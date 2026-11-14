package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@Controller
class JourneyServiceController {
  private static final List<String> JOURNEY_READ_CAPABILITIES =
      List.of("catalog.journey.read");
  private static final List<String> JOURNEY_WRITE_CAPABILITIES =
      List.of("catalog.journey.write");
  private static final List<String> SERVICE_WRITE_CAPABILITIES =
      List.of("catalog.service.write");
  private static final int MAX_OWNER_PAGES = 20;
  private static final int OWNER_ID_PREFIX_LENGTH = 150;
  private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  JourneyServiceController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/journeys/{journeyId}/services/new")
  String newService(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    ServiceForm form = new ServiceForm("", "", null, "", UUID.randomUUID().toString(), null);
    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model);
    IdentityAccessClient.OwnerPage owners =
        journey == null ? null : loadOwners(client, context, null, response, model);
    return renderServiceForm(context, journeyId, journey, form, List.of(""), owners, model);
  }

  @PostMapping("/journeys/{journeyId}/services/new/owners")
  String changeOwnerPage(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      @ModelAttribute("serviceForm") ServiceForm form,
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
      model.addAttribute("serviceError", "The owner page history is invalid; reload the form.");
      form = form.withOwnerPage(null);
      history = List.of("");
      replaceForm(model, form);
      CatalogClient.JourneyDetails journey =
          loadJourney(client, context, journeyId, response, model);
      IdentityAccessClient.OwnerPage owners =
          journey == null ? null : loadOwners(client, context, null, response, model);
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
    }

    IdentityAccessClient.OwnerPage currentPage =
        loadOwners(client, context, normalizedCursor(form.ownerCursor()), response, model);
    if (currentPage == null) {
      return renderServiceForm(
          context,
          journeyId,
          loadJourney(client, context, journeyId, response, model),
          form,
          history,
          null,
          model);
    }

    List<String> nextHistory = new ArrayList<>(history);
    String nextCursor = normalizedCursor(form.ownerCursor());
    if ("next".equals(ownerPageAction)) {
      if (currentPage.nextCursor() == null || history.size() >= MAX_OWNER_PAGES) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("serviceError", "There are no more eligible owners to display.");
        return renderServiceForm(
            context,
            journeyId,
            loadJourney(client, context, journeyId, response, model),
            form,
            history,
            currentPage,
            model);
      }
      nextCursor = currentPage.nextCursor();
      nextHistory.add(nextCursor);
    } else if ("previous".equals(ownerPageAction)) {
      if (history.size() <= 1) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("serviceError", "There is no previous owner page.");
        return renderServiceForm(
            context,
            journeyId,
            loadJourney(client, context, journeyId, response, model),
            form,
            history,
            currentPage,
            model);
      }
      nextHistory.removeLast();
      nextCursor = nextHistory.getLast();
      if (nextCursor.isEmpty()) {
        nextCursor = null;
      }
    } else {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("serviceError", "Choose a valid owner-list navigation action.");
      return renderServiceForm(
          context,
          journeyId,
          loadJourney(client, context, journeyId, response, model),
          form,
          history,
          currentPage,
          model);
    }

    form = form.withOwnerPage(nextCursor);
    replaceForm(model, form);
    IdentityAccessClient.OwnerPage nextPage =
        loadOwners(client, context, normalizedCursor(nextCursor), response, model);
    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model);
    return renderServiceForm(context, journeyId, journey, form, nextHistory, nextPage, model);
  }

  @PostMapping("/journeys/{journeyId}/services")
  String createService(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      @Valid @ModelAttribute("serviceForm") ServiceForm form,
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
      addServiceValidationErrors(bindingResult, model);
      if (history == null) {
        model.addAttribute("serviceError", "The owner page history is invalid; reload the form.");
        history = List.of("");
        form = form.withOwnerPage(null);
        replaceForm(model, form);
      }
      return renderServiceForm(
          client, context, journeyId, form, history, response, model);
    }

    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model);
    IdentityAccessClient.OwnerPage owners =
        loadOwners(client, context, normalizedCursor(form.ownerCursor()), response, model);
    if (journey == null || owners == null) {
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
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
          "serviceError",
          "Select an active, eligible human owner from the current S01 owner list.");
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
    }

    String delegationToken;
    try {
      delegationToken =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  null,
                  SERVICE_WRITE_CAPABILITIES,
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "serviceError", "UX01 is unavailable; service creation was not authorized.");
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "serviceError",
          "S01 did not authorize service creation (HTTP " + error.getStatusCode().value() + ").");
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
    }

    String serviceId = commandId(form.ownerId(), form.idempotencyKey());
    try {
      CatalogClient.MutationReceipt receipt =
          catalog.registerService(
              delegationToken,
              form.idempotencyKey(),
              new CatalogClient.ServiceRegistration(
                  serviceId,
                  form.name(),
                  form.ownerId(),
                  form.reason(),
                  0,
                  form.criticality(),
                  "DRAFT"));
      return "redirect:/journeys/" + journeyId + "/services/" + receipt.id() + "/link";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "serviceError",
          "S02 is unavailable; the service result is unconfirmed. Retry this same form to safely replay the request.");
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "serviceError",
          "S02 did not register the service (HTTP " + error.getStatusCode().value() + ").");
      return renderServiceForm(context, journeyId, journey, form, history, owners, model);
    }
  }

  @GetMapping("/journeys/{journeyId}/services/{serviceId}/link")
  String newServiceLink(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      @PathVariable String serviceId,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    LinkForm form = new LinkForm("", "UNKNOWN", UUID.randomUUID().toString());
    if (!isValidId(serviceId)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("serviceError", "The service identifier is malformed.");
    }
    CatalogClient.JourneyDetails journey =
        isValidId(serviceId) ? loadJourney(client, context, journeyId, response, model) : null;
    return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
  }

  @PostMapping("/journeys/{journeyId}/services/{serviceId}/link")
  String linkService(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      @PathVariable String serviceId,
      @Valid @ModelAttribute("linkForm") LinkForm form,
      BindingResult bindingResult,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (!isValidId(serviceId)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("serviceError", "The service identifier is malformed.");
      addLinkValidationErrors(bindingResult, model);
      return renderServiceLinkForm(context, journeyId, serviceId, null, form, model);
    }
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      addLinkValidationErrors(bindingResult, model);
      CatalogClient.JourneyDetails journey =
          loadJourney(client, context, journeyId, response, model);
      return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
    }

    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model);
    if (journey == null) {
      return renderServiceLinkForm(context, journeyId, serviceId, null, form, model);
    }
    String stepId = normalizedCursor(form.stepId());
    boolean knownStep =
        stepId == null || journey.steps().stream().anyMatch(step -> stepId.equals(step.id()));
    if (!knownStep) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("stepError", "Choose a step from this journey.");
      model.addAttribute("invalidStep", true);
      return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
    }

    String delegationToken;
    try {
      delegationToken =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  null,
                  JOURNEY_WRITE_CAPABILITIES,
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "serviceError", "UX01 is unavailable; linking the service was not authorized.");
      return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "serviceError",
          "S01 did not authorize linking the service (HTTP "
              + error.getStatusCode().value()
              + ").");
      return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
    }

    String linkId = "link_" + form.idempotencyKey();
    try {
      catalog.linkCapability(
          delegationToken,
          journeyId,
          form.idempotencyKey(),
          new CatalogClient.CapabilityLink(
              linkId, "SERVICE", serviceId, stepId, form.criticality()));
      return "redirect:/journeys/" + journeyId;
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "serviceError",
          "S02 is unavailable; the link result is unconfirmed. Retry this same form to safely replay the request.");
      return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "serviceError",
          "S02 did not link the service (HTTP " + error.getStatusCode().value() + ").");
      return renderServiceLinkForm(context, journeyId, serviceId, journey, form, model);
    }
  }

  @GetMapping("/journeys/{journeyId}/agents/link")
  String newAgentLink(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    AgentLinkForm form = new AgentLinkForm("", "", "UNKNOWN", UUID.randomUUID().toString());
    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model, "agentError");
    return renderAgentLinkForm(context, journeyId, journey, form, model);
  }

  @PostMapping("/journeys/{journeyId}/agents/link")
  String linkAgent(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String journeyId,
      @Valid @ModelAttribute("agentLinkForm") AgentLinkForm form,
      BindingResult bindingResult,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      addAgentLinkValidationErrors(bindingResult, model);
      CatalogClient.JourneyDetails journey =
          loadJourney(client, context, journeyId, response, model, "agentError");
      return renderAgentLinkForm(context, journeyId, journey, form, model);
    }

    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model, "agentError");
    if (journey == null) {
      return renderAgentLinkForm(context, journeyId, null, form, model);
    }
    String stepId = normalizedCursor(form.stepId());
    boolean knownStep =
        stepId == null || journey.steps().stream().anyMatch(step -> stepId.equals(step.id()));
    if (!knownStep) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("stepError", "Choose a step from this journey.");
      model.addAttribute("invalidStep", true);
      return renderAgentLinkForm(context, journeyId, journey, form, model);
    }

    String delegationToken;
    try {
      delegationToken =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  null,
                  JOURNEY_WRITE_CAPABILITIES,
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "agentError", "UX01 is unavailable; linking the agent was not authorized.");
      return renderAgentLinkForm(context, journeyId, journey, form, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S01 did not authorize linking the agent (HTTP "
              + error.getStatusCode().value()
              + ").");
      return renderAgentLinkForm(context, journeyId, journey, form, model);
    }

    try {
      String linkId = "link_" + form.idempotencyKey();
      catalog.linkCapability(
          delegationToken,
          journeyId,
          form.idempotencyKey(),
          new CatalogClient.CapabilityLink(
              linkId, "AGENT", form.agentId(), stepId, form.criticality()));
      return "redirect:/journeys/" + journeyId;
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "agentError",
          "S02 is unavailable; the link result is unconfirmed. Retry this same form to safely replay the request.");
      return renderAgentLinkForm(context, journeyId, journey, form, model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "agentError",
          "S02 did not link the agent (HTTP " + error.getStatusCode().value() + ").");
      return renderAgentLinkForm(context, journeyId, journey, form, model);
    }
  }

  private String renderServiceForm(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String journeyId,
      ServiceForm form,
      List<String> history,
      HttpServletResponse response,
      Model model) {
    CatalogClient.JourneyDetails journey =
        loadJourney(client, context, journeyId, response, model);
    IdentityAccessClient.OwnerPage owners =
        journey == null
            ? null
            : loadOwners(client, context, normalizedCursor(form.ownerCursor()), response, model);
    return renderServiceForm(context, journeyId, journey, form, history, owners, model);
  }

  private CatalogClient.JourneyDetails loadJourney(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String journeyId,
      HttpServletResponse response,
      Model model) {
    return loadJourney(client, context, journeyId, response, model, "serviceError");
  }

  private CatalogClient.JourneyDetails loadJourney(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String journeyId,
      HttpServletResponse response,
      Model model,
      String errorAttribute) {
    String token;
    try {
      token =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  null,
                  JOURNEY_READ_CAPABILITIES,
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(errorAttribute, "UX01 is unavailable; journey access was not authorized.");
      return null;
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          errorAttribute,
          "S01 did not authorize journey access (HTTP " + error.getStatusCode().value() + ").");
      return null;
    }
    try {
      return catalog.getJourney(token, journeyId);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(errorAttribute, "S02 is unavailable; the journey was not loaded.");
      return null;
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          errorAttribute,
          "S02 did not return this journey (HTTP " + error.getStatusCode().value() + ").");
      return null;
    }
  }

  private IdentityAccessClient.OwnerPage loadOwners(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String cursor,
      HttpServletResponse response,
      Model model) {
    try {
      return identity.ownerCandidates(
          client.getAccessToken().getTokenValue(), context, "SERVICE", cursor);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("serviceError", "UX01 is unavailable; eligible owners were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "serviceError",
          "S01 did not authorize service owner selection (HTTP "
              + error.getStatusCode().value()
              + ").");
    }
    return null;
  }

  private String renderServiceForm(
      BrowserContext context,
      String journeyId,
      CatalogClient.JourneyDetails journey,
      ServiceForm form,
      List<String> history,
      IdentityAccessClient.OwnerPage owners,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("journeyId", journeyId);
    model.addAttribute("journey", journey);
    if (!model.containsAttribute("serviceForm")) {
      model.addAttribute("serviceForm", form);
    }
    model.addAttribute("ownerCandidates", owners == null ? List.of() : owners.items());
    model.addAttribute("nextOwnerCursor", owners == null ? null : owners.nextCursor());
    model.addAttribute("ownerCursorHistory", history);
    model.addAttribute("hasPreviousOwners", history.size() > 1);
    return "journey-service-new";
  }

  private String renderServiceLinkForm(
      BrowserContext context,
      String journeyId,
      String serviceId,
      CatalogClient.JourneyDetails journey,
      LinkForm form,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("journeyId", journeyId);
    model.addAttribute("serviceId", serviceId);
    model.addAttribute("journey", journey);
    model.addAttribute("journeySteps", journey == null ? List.of() : journey.steps());
    if (!model.containsAttribute("linkForm")) {
      model.addAttribute("linkForm", form);
    }
    return "journey-service-link";
  }

  private String renderAgentLinkForm(
      BrowserContext context,
      String journeyId,
      CatalogClient.JourneyDetails journey,
      AgentLinkForm form,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("journeyId", journeyId);
    model.addAttribute("journey", journey);
    model.addAttribute("journeySteps", journey == null ? List.of() : journey.steps());
    if (!model.containsAttribute("agentLinkForm")) {
      model.addAttribute("agentLinkForm", form);
    }
    return "journey-agent-link";
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

  private static boolean isValidId(String value) {
    return value != null && value.matches(ID_PATTERN);
  }

  private static String normalizedCursor(String cursor) {
    return cursor == null || cursor.isBlank() ? null : cursor;
  }

  private static void addServiceValidationErrors(BindingResult bindingResult, Model model) {
    model.addAttribute(
        "validationErrors",
        bindingResult.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    model.addAttribute("nameError", fieldError(bindingResult, "name"));
    model.addAttribute("reasonError", fieldError(bindingResult, "reason"));
    model.addAttribute("ownerError", fieldError(bindingResult, "ownerId"));
    model.addAttribute("invalidName", bindingResult.hasFieldErrors("name"));
    model.addAttribute("invalidReason", bindingResult.hasFieldErrors("reason"));
    model.addAttribute("invalidOwner", bindingResult.hasFieldErrors("ownerId"));
    model.addAttribute("invalidCriticality", bindingResult.hasFieldErrors("criticality"));
  }

  private static void addLinkValidationErrors(BindingResult bindingResult, Model model) {
    model.addAttribute(
        "validationErrors",
        bindingResult.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    model.addAttribute("criticalityError", fieldError(bindingResult, "criticality"));
    model.addAttribute("invalidCriticality", bindingResult.hasFieldErrors("criticality"));
  }

  private static void addAgentLinkValidationErrors(BindingResult bindingResult, Model model) {
    addLinkValidationErrors(bindingResult, model);
    model.addAttribute("agentIdError", fieldError(bindingResult, "agentId"));
    model.addAttribute("invalidAgentId", bindingResult.hasFieldErrors("agentId"));
    model.addAttribute("stepError", fieldError(bindingResult, "stepId"));
    model.addAttribute("invalidStep", bindingResult.hasFieldErrors("stepId"));
  }

  private static String fieldError(BindingResult bindingResult, String field) {
    return bindingResult.hasFieldErrors(field)
        ? bindingResult.getFieldError(field).getDefaultMessage()
        : null;
  }

  private static void replaceForm(Model model, ServiceForm form) {
    model.asMap().remove("serviceForm");
    model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "serviceForm");
    model.addAttribute("serviceForm", form);
  }

  record ServiceForm(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 2000) String reason,
      @NotNull @Min(1) @Max(5) Integer criticality,
      @NotBlank @Pattern(regexp = ID_PATTERN) String ownerId,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
          String idempotencyKey,
      String ownerCursor) {
    ServiceForm withOwnerPage(String cursor) {
      return new ServiceForm(name, reason, criticality, "", idempotencyKey, cursor);
    }
  }

  record LinkForm(
      @Pattern(regexp = ID_PATTERN) String stepId,
      @NotBlank @Pattern(regexp = "^(REQUIRED|OPTIONAL|UNKNOWN)$") String criticality,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
          String idempotencyKey) {}

  record AgentLinkForm(
      @NotBlank @Pattern(regexp = ID_PATTERN) String agentId,
      @Pattern(regexp = ID_PATTERN) String stepId,
      @NotBlank @Pattern(regexp = "^(REQUIRED|OPTIONAL|UNKNOWN)$") String criticality,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
          String idempotencyKey) {}
}
