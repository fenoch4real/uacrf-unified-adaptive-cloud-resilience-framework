package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class AssessmentCaseCreationController {
  private final IdentityAccessClient identity;
  private final AssessmentClient assessment;

  AssessmentCaseCreationController(IdentityAccessClient identity, AssessmentClient assessment) {
    this.identity = identity;
    this.assessment = assessment;
  }

  @GetMapping("/assessment-cases/new")
  String form(HttpSession session, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseForm", new CaseForm("", "", "", "LTE", null, "", null,
        "", null, "", "", null, "", "", UUID.randomUUID().toString(),
        context.tenantId(), context.environmentId()));
    return "assessment-case-new";
  }

  @PostMapping("/assessment-cases")
  String create(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("caseForm") CaseForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("caseError", "The selected context changed. Do not retry this form in a different context; return to its original context first.");
      return "assessment-case-new";
    }
    List<String> kinds = form.sourceKinds() == null ? List.of()
        : form.sourceKinds().lines().map(String::strip).filter(value -> !value.isEmpty()).toList();
    if (kinds.isEmpty() || kinds.size() > 100 || kinds.stream().anyMatch(value -> value.length() > 200)
        || kinds.stream().distinct().count() != kinds.size()) {
      errors.rejectValue("sourceKinds", "invalid", "Enter 1 to 100 unique source kinds, one per line, up to 200 characters each.");
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "assessment-case-new";
    }

    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.case.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("caseError", "Identity service is unavailable; case creation was not authorized.");
      return "assessment-case-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("caseError", "Case creation was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "assessment-case-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("caseError", "Identity service returned an invalid response; case creation was not authorized.");
      return "assessment-case-new";
    }

    var request = new AssessmentClient.CaseCreation(form.workloadId(), form.purpose(), form.metric(),
        form.comparator(), form.threshold(), form.unit(), form.windowSeconds(),
        new AssessmentClient.ObjectiveRef(form.objectiveId(), form.objectiveRevision(), form.objectiveDigest()),
        new AssessmentClient.GraphRef(form.graphId(), form.graphRevision(), form.graphDigest()), kinds);
    try {
      model.addAttribute("createdCase", assessment.createCase(token, form.idempotencyKey(), request));
      return "assessment-case-created";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("caseError", "Case creation is unconfirmed. Retry this same form without changing it.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("caseError", "Case creation failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form without changing it; a changed request needs a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("caseError", "The case response was invalid; creation is unconfirmed. Retry this same form without changing it.");
    }
    return "assessment-case-new";
  }

  record CaseForm(
      @NotBlank @Size(max=200) String workloadId, @NotBlank @Size(max=200) String purpose,
      @NotBlank @Size(max=100) String metric, @NotBlank @Pattern(regexp="LTE|GTE") String comparator,
      @NotNull @Digits(integer=100, fraction=50) BigDecimal threshold,
      @NotBlank @Size(max=30) String unit, @NotNull @Min(1) @Max(31536000) Integer windowSeconds,
      @NotBlank @Size(max=200) String objectiveId, @NotNull @Min(1) Long objectiveRevision,
      @NotBlank @Size(max=200) String objectiveDigest, @NotBlank @Size(max=200) String graphId,
      @NotNull @Min(1) Long graphRevision, @NotBlank @Size(max=200) String graphDigest,
      @NotBlank @Size(max=20100) String sourceKinds,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId) {}
}
