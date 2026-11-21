package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
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
class ObjectiveCreationController {
  private final IdentityAccessClient identity;
  private final ObjectivesClient objectives;

  ObjectiveCreationController(IdentityAccessClient identity, ObjectivesClient objectives) {
    this.identity = identity;
    this.objectives = objectives;
  }

  @GetMapping("/objectives/new")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("objectiveForm", new ObjectiveForm("", "", "", "", "LTE", null, "", null,
        UUID.randomUUID().toString(), context.tenantId(), context.environmentId(), client.getPrincipalName()));
    return "objective-new";
  }

  @PostMapping("/objectives")
  String create(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("objectiveForm") ObjectiveForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("objectiveError", "The signed-in user or selected context changed. Return to the original user and context before retrying.");
      return "objective-new";
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return "objective-new";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("objectiveError", "Identity service is unavailable; the draft was not submitted.");
      return "objective-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("objectiveError", "Objective creation was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "objective-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("objectiveError", "Identity service returned invalid authorization; the draft was not submitted.");
      return "objective-new";
    }
    var request = new ObjectivesClient.LocalObjective(form.id(), form.workloadId(), form.title(), form.metric(),
        form.comparator(), form.target(), form.unit(), form.windowSeconds(), "LOCAL");
    try {
      model.addAttribute("objective", objectives.createLocal(token, form.idempotencyKey(), request));
      return "objective-created";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("objectiveError", "The draft outcome is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("objectiveError", "The draft failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; changed details require a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("objectiveError", "The draft response was invalid; the outcome is unconfirmed. Retry this same form unchanged.");
    }
    return "objective-new";
  }

  record ObjectiveForm(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String workloadId,
      @NotBlank @Size(max=200) String title,
      @NotBlank @Pattern(regexp="^[a-z][a-z0-9_.-]{0,99}$") String metric,
      @NotBlank @Pattern(regexp="^(LTE|GTE)$") String comparator,
      @NotNull @Digits(integer=18, fraction=6) BigDecimal target,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9_./%\\-]{1,30}$") String unit,
      @NotNull @Min(1) Integer windowSeconds,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}
}
