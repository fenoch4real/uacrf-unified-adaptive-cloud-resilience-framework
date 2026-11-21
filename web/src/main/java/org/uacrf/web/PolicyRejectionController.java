package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class PolicyRejectionController {
  private final IdentityAccessClient identity;
  private final PolicyClient policies;

  PolicyRejectionController(IdentityAccessClient identity, PolicyClient policies) {
    this.identity = identity;
    this.policies = policies;
  }

  @GetMapping("/policies/reject")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String serviceId,
      @RequestParam String id, @RequestParam String revision, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    long version;
    try {
      if (!PolicyClient.identifier(serviceId) || !PolicyClient.identifier(id)
          || !revision.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
      version = Long.parseLong(revision);
    } catch (NumberFormatException error) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("policyError", "Invalid service ID, policy ID, or revision.");
      return "policy-reject";
    }
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S10",
          serviceId, List.of("authorization.policy.read"), context.tenantId(), context.environmentId()).accessToken();
      PolicyClient.Policy policy = policies.read(token, serviceId, id, version);
      model.addAttribute("policy", policy);
      model.addAttribute("rejectionForm", new RejectionForm(serviceId, id, version, policy.digest(),
          "", UUID.randomUUID().toString(), context.tenantId(), context.environmentId(), client.getPrincipalName()));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity or policy service is unavailable; the revision was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity or policy service returned invalid policy details.");
    }
    return "policy-reject";
  }

  @PostMapping("/policies/reject")
  String reject(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("rejectionForm") RejectionForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("policyError", "The signed-in user or selected context changed. Return to the original user and context before retrying.");
      return "policy-reject";
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return "policy-reject";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S10", form.serviceId(),
          List.of("authorization.policy.review"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity service is unavailable; the rejection was not submitted.");
      return "policy-reject";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy review was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "policy-reject";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity service returned invalid authorization; the rejection was not submitted.");
      return "policy-reject";
    }
    try {
      model.addAttribute("policy", policies.reject(token, form.idempotencyKey(), form.serviceId(),
          form.id(), form.revision(), form.digest(), form.reason()));
      return "policy-rejected";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "The rejection is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "The rejection failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; changed details require a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "The rejection response was invalid; the outcome is unconfirmed. Retry this same form unchanged.");
    }
    return "policy-reject";
  }

  record RejectionForm(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotNull @Min(1) Long revision, @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String digest,
      @NotBlank @Size(max=2000) String reason,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}
}
