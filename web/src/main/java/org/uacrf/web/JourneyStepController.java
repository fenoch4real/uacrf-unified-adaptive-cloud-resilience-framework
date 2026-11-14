package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class JourneyStepController {
  private static final List<String> JOURNEY_READ_CAPABILITIES =
      List.of("catalog.journey.read");
  private static final List<String> JOURNEY_WRITE_CAPABILITIES =
      List.of("catalog.journey.write");
  private static final int OWNER_ID_PREFIX_LENGTH = 150;

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  JourneyStepController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/journeys/{id}/steps/new")
  String newStep(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String id,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("journeyId", id);

    String readToken = readDelegation(client, context, response, model);
    if (readToken == null) {
      model.addAttribute("stepForm", new StepForm(1, "", "", UUID.randomUUID().toString()));
      return "journey-step-new";
    }
    try {
      CatalogClient.JourneyDetails journey = catalog.getJourney(readToken, id);
      model.addAttribute("journey", journey);
      model.addAttribute(
          "stepForm", new StepForm(nextPosition(journey), "", "", UUID.randomUUID().toString()));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "S02 is unavailable; the journey was not loaded.");
      model.addAttribute("stepForm", new StepForm(1, "", "", UUID.randomUUID().toString()));
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S02 did not return this journey (HTTP " + error.getStatusCode().value() + ").");
      model.addAttribute("stepForm", new StepForm(1, "", "", UUID.randomUUID().toString()));
    }
    return "journey-step-new";
  }

  @PostMapping("/journeys/{id}/steps")
  String createStep(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String id,
      @Valid @ModelAttribute("stepForm") StepForm form,
      BindingResult bindingResult,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("journeyId", id);
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      addValidationErrors(bindingResult, model);
      return "journey-step-new";
    }

    String readToken = readDelegation(client, context, response, model);
    if (readToken == null) {
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    }

    CatalogClient.JourneyDetails journey;
    try {
      journey = catalog.getJourney(readToken, id);
      model.addAttribute("journey", journey);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "S02 is unavailable; the journey was not loaded.");
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S02 did not return this journey (HTTP " + error.getStatusCode().value() + ").");
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    }

    IdentityAccessClient.Delegation writeDelegation;
    try {
      writeDelegation =
          identity.createDelegation(
              client.getAccessToken().getTokenValue(),
              "S02",
              null,
              JOURNEY_WRITE_CAPABILITIES,
              context.tenantId(),
              context.environmentId());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("journeyError", "UX01 is unavailable; step creation was not authorized.");
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize step creation (HTTP " + error.getStatusCode().value() + ").");
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    }

    String stepId = commandId(journey.ownerId(), form.idempotencyKey());
    try {
      catalog.registerStep(
          writeDelegation.accessToken(),
          id,
          form.idempotencyKey(),
          new CatalogClient.StepRegistration(
              stepId, form.position(), form.name(), form.intendedOutcome()));
      return "redirect:/journeys/" + id;
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "journeyError",
          "S02 is unavailable; the step result is unconfirmed. Retry this same form to safely replay the request.");
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S02 did not register the step (HTTP " + error.getStatusCode().value() + ").");
      model.addAttribute("stepForm", form);
      return "journey-step-new";
    }
  }

  private String readDelegation(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      HttpServletResponse response,
      Model model) {
    try {
      return identity
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
      model.addAttribute("journeyError", "UX01 is unavailable; journey access was not authorized.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "journeyError",
          "S01 did not authorize journey access (HTTP " + error.getStatusCode().value() + ").");
    }
    return null;
  }

  private static BrowserContext selectedContext(HttpSession session) {
    Object contextAttribute = session.getAttribute(BrowserContext.SESSION_KEY);
    return contextAttribute instanceof BrowserContext context ? context : null;
  }

  private static int nextPosition(CatalogClient.JourneyDetails journey) {
    return journey.steps().stream().mapToInt(CatalogClient.Step::position).max().orElse(0) + 1;
  }

  private static String commandId(String ownerId, String idempotencyKey) {
    String ownerPrefix =
        ownerId.substring(0, Math.min(ownerId.length(), OWNER_ID_PREFIX_LENGTH));
    return "cmd_" + ownerPrefix + "_" + idempotencyKey;
  }

  private static void addValidationErrors(BindingResult bindingResult, Model model) {
    model.addAttribute(
        "validationErrors",
        bindingResult.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    model.addAttribute("nameError", fieldError(bindingResult, "name"));
    model.addAttribute("outcomeError", fieldError(bindingResult, "intendedOutcome"));
    model.addAttribute("invalidPosition", bindingResult.hasFieldErrors("position"));
    model.addAttribute("invalidName", bindingResult.hasFieldErrors("name"));
    model.addAttribute("invalidOutcome", bindingResult.hasFieldErrors("intendedOutcome"));
  }

  private static String fieldError(BindingResult bindingResult, String field) {
    return bindingResult.hasFieldErrors(field)
        ? bindingResult.getFieldError(field).getDefaultMessage()
        : null;
  }

  record StepForm(
      @Min(1) int position,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 2000) String intendedOutcome,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
          String idempotencyKey) {}
}
