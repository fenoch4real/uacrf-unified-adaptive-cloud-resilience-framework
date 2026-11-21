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
class PolicyApprovalController {
  private final IdentityAccessClient identity;
  private final PolicyClient policies;

  PolicyApprovalController(IdentityAccessClient identity, PolicyClient policies) {
    this.identity = identity;
    this.policies = policies;
  }

  @GetMapping("/policies/approve")
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
      return "policy-approve";
    }
    try {
      String accessToken = client.getAccessToken().getTokenValue();
      var access = identity.currentAccess(accessToken, context.tenantId(), context.environmentId());
      if (!PolicyClient.identifier(access.principalId()) || !"HUMAN".equals(access.kind())) {
        throw new IllegalStateException("Identity returned an invalid human reviewer.");
      }
      String token = identity.createDelegation(accessToken, "S10",
          serviceId, List.of("authorization.policy.read"), context.tenantId(), context.environmentId()).accessToken();
      PolicyClient.Policy policy = policies.read(token, serviceId, id, version);
      model.addAttribute("policy", policy);
      model.addAttribute("approvalForm", new ApprovalForm(serviceId, id, version, policy.digest(),
          access.principalId(), "", "", UUID.randomUUID().toString(),
          context.tenantId(), context.environmentId(), client.getPrincipalName()));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity or policy service is unavailable; the revision was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity or policy service returned invalid reviewer or policy details.");
    }
    return "policy-approve";
  }

  @PostMapping("/policies/approve")
  String approve(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("approvalForm") ApprovalForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("policyError", "The signed-in user or selected context changed. Return to the original user and context before retrying.");
      return "policy-approve";
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return "policy-approve";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S10", form.serviceId(),
          List.of("authorization.policy.review"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity service is unavailable; the approval was not submitted.");
      return "policy-approve";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy review was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "policy-approve";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity service returned invalid authorization; the approval was not submitted.");
      return "policy-approve";
    }
    try {
      model.addAttribute("policy", policies.approve(token, form.idempotencyKey(), form.serviceId(),
          form.id(), form.revision(), form.digest(), form.reason(), form.assertion()));
      return "policy-approved";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "The approval is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "The approval failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; changed details require a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "The approval response was invalid; the outcome is unconfirmed. Retry this same form unchanged.");
    }
    return "policy-approve";
  }

  record ApprovalForm(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotNull @Min(1) Long revision, @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String digest,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String reviewerId,
      @NotBlank @Size(max=2000) String reason,
      @NotBlank @Size(max=16000) @Pattern(regexp="^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$") String assertion,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}
}
