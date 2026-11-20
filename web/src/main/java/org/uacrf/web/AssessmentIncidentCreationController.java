package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class AssessmentIncidentCreationController {
  private final IdentityAccessClient identity;
  private final AssessmentClient assessment;

  AssessmentIncidentCreationController(IdentityAccessClient identity, AssessmentClient assessment) {
    this.identity = identity;
    this.assessment = assessment;
  }

  @GetMapping("/assessment-cases/{caseId}/incidents/new")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.case.read"), context.tenantId(), context.environmentId()).accessToken();
      var item = assessment.getCase(token, caseId);
      if (!"SEALED".equals(item.state())) {
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("incidentError", "Only a sealed case can be used to create an incident.");
      } else {
        model.addAttribute("incidentForm", new IncidentForm(item.workloadId(), "", "", "", "",
            UUID.randomUUID().toString(), context.tenantId(), context.environmentId()));
        model.addAttribute("showForm", true);
      }
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("incidentError", "Identity or assessment service is unavailable; the case was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("incidentError", "Case access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("incidentError", "Identity or assessment service returned invalid case details.");
    }
    return "assessment-incident-new";
  }

  @PostMapping("/assessment-cases/{caseId}/incidents")
  String create(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId,
      @Valid @ModelAttribute("incidentForm") IncidentForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    model.addAttribute("showForm", true);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("incidentError", "The selected context changed. Return to the original context before retrying this form.");
      return "assessment-incident-new";
    }
    Instant observed = null;
    if (!errors.hasFieldErrors("observedFrom")) {
      try {
        observed = Instant.parse(form.observedFrom());
        if (observed.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
            || observed.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) {
          errors.rejectValue("observedFrom", "invalid", "Enter an ISO-8601 timestamp with UTC offset in years 0001-9999.");
        }
      } catch (DateTimeParseException error) {
        errors.rejectValue("observedFrom", "invalid", "Enter an ISO-8601 timestamp with UTC offset in years 0001-9999.");
      }
    }
    List<String> refs = form.evidenceRefs() == null ? List.of()
        : form.evidenceRefs().lines().map(String::strip).filter(value -> !value.isEmpty()).toList();
    if (refs.isEmpty() || refs.size() > 100 || refs.stream().anyMatch(value -> value.length() > 200)
        || refs.stream().distinct().count() != refs.size()) {
      errors.rejectValue("evidenceRefs", "invalid", "Enter 1 to 100 unique evidence IDs, one per line, up to 200 characters each.");
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "assessment-incident-new";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.incident.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("incidentError", "Identity service is unavailable; incident creation was not authorized.");
      return "assessment-incident-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("incidentError", "Incident creation was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "assessment-incident-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("incidentError", "Identity service returned an invalid response; incident creation was not authorized.");
      return "assessment-incident-new";
    }
    try {
      var request = new AssessmentClient.IncidentCreation(form.workloadId(), caseId, observed,
          form.description(), refs, form.reason());
      model.addAttribute("incident", assessment.createIncident(token, form.idempotencyKey(), request));
      return "assessment-incident-created";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("incidentError", "Incident creation is unconfirmed. Retry this same form without changing it.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("incidentError", "Incident creation failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; check the incident list before starting a new request.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("incidentError", "The incident response was invalid; creation is unconfirmed. Retry this same form unchanged.");
    }
    return "assessment-incident-new";
  }

  record IncidentForm(@NotBlank @Size(max=200) String workloadId,
      @NotBlank @Size(max=100) String observedFrom, @NotBlank @Size(max=4000) String description,
      @NotBlank @Size(max=20100) String evidenceRefs, @NotBlank @Size(max=2000) String reason,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId) {}
}
