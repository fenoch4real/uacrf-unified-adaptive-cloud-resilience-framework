package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
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
class PolicyProposalController {
  private final IdentityAccessClient identity;
  private final PolicyClient policies;

  PolicyProposalController(IdentityAccessClient identity, PolicyClient policies) {
    this.identity = identity;
    this.policies = policies;
  }

  @GetMapping("/policies/new")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, @RequestParam(required = false) String serviceId,
      @RequestParam(required = false) String id,
      @RequestParam(required = false) Long expectedRevision, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("policyForm", new PolicyForm(serviceId == null ? "" : serviceId,
        id == null ? "" : id, expectedRevision == null ? 0L : expectedRevision,
        "", "", "", "", "", "", "", 1, "", null, "",
        UUID.randomUUID().toString(), context.tenantId(), context.environmentId(), client.getPrincipalName()));
    return "policy-new";
  }

  @PostMapping("/policies")
  String propose(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("policyForm") PolicyForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("policyError", "The signed-in user or selected context changed. Return to the original user and context before retrying.");
      return "policy-new";
    }

    List<String> actions = lines(form.actions());
    List<String> actors = lines(form.actors());
    List<String> resources = lines(form.resources());
    List<String> providers = lines(form.providers());
    List<String> regions = lines(form.regions());
    List<String> approvers = lines(form.approvers());
    validateList(errors, "actions", actions, value -> value.matches("[A-Z][A-Z0-9_]{0,79}"),
        "Enter 1 to 100 unique uppercase action kinds, one per line.");
    validateList(errors, "actors", actors, PolicyClient::identifier,
        "Enter 1 to 100 unique actor IDs, one per line.");
    validateList(errors, "resources", resources, PolicyProposalController::nonGlobal,
        "Enter 1 to 100 unique resource IDs, one per line; '*' is not permitted.");
    validateList(errors, "providers", providers, value -> List.of("AWS", "AZURE", "GCP", "AGENT_RUNTIME").contains(value),
        "Enter 1 to 4 unique providers (AWS, AZURE, GCP, AGENT_RUNTIME), one per line.");
    validateList(errors, "regions", regions, PolicyProposalController::nonGlobal,
        "Enter 1 to 100 unique regions, one per line; '*' is not permitted.");
    validateList(errors, "approvers", approvers, PolicyClient::identifier,
        "Enter 1 to 100 unique eligible approver IDs, one per line.");
    Instant effective = parseInstant(form.effectiveFrom(), "effectiveFrom", errors);
    Instant expiry = parseInstant(form.expiresAt(), "expiresAt", errors);
    if (effective != null && expiry != null && !expiry.isAfter(effective)) {
      errors.rejectValue("expiresAt", "invalid", "Expiry must be later than the effective time.");
    }
    if (form.expectedRevision() != null && form.expectedRevision() == Long.MAX_VALUE) {
      errors.rejectValue("expectedRevision", "invalid", "The revision limit has been reached.");
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return "policy-new";
    }

    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S10", form.serviceId(),
          List.of("authorization.policy.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "Identity service is unavailable; the proposal was not submitted.");
      return "policy-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "Policy proposal was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "policy-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "Identity service returned invalid authorization; the proposal was not submitted.");
      return "policy-new";
    }

    var definition = new PolicyClient.Definition(actions, actors, resources, providers, regions,
        form.requiredApprovers(), approvers, form.maximumCost());
    var request = new PolicyClient.Proposal(form.id(), form.serviceId(), form.expectedRevision(),
        expiry, definition, form.reason(), effective);
    try {
      model.addAttribute("policy", policies.propose(token, form.idempotencyKey(), request));
      return "policy-proposed";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("policyError", "The proposal is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("policyError", "The proposal failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; changed details require a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("policyError", "The proposal response was invalid; the outcome is unconfirmed. Retry this same form unchanged.");
    }
    return "policy-new";
  }

  private static List<String> lines(String value) {
    return value == null ? List.of() : value.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  private static boolean nonGlobal(String value) {
    return !value.isBlank() && value.length() <= 200 && !"*".equals(value);
  }

  private static void validateList(BindingResult errors, String field, List<String> values,
      Predicate<String> valid, String message) {
    if (values.isEmpty() || values.size() > 100 || values.stream().distinct().count() != values.size()
        || values.stream().anyMatch(valid.negate())) errors.rejectValue(field, "invalid", message);
  }

  private static Instant parseInstant(String value, String field, BindingResult errors) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException error) {
      errors.rejectValue(field, "invalid", "Enter an ISO-8601 UTC timestamp, for example 2030-01-01T00:00:00Z.");
      return null;
    }
  }

  record PolicyForm(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String serviceId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotNull @Min(0) Long expectedRevision, @NotBlank @Size(max=50) String effectiveFrom,
      @NotBlank @Size(max=50) String expiresAt,
      @NotBlank @Size(max=20100) String actions, @NotBlank @Size(max=20100) String actors,
      @NotBlank @Size(max=20100) String resources, @NotBlank @Size(max=20100) String providers,
      @NotBlank @Size(max=20100) String regions, @NotNull @Min(0) @Max(10) Integer requiredApprovers,
      @NotBlank @Size(max=20100) String approvers,
      @NotNull @DecimalMin("0") @DecimalMax("1000000000") @Digits(integer=10, fraction=6) BigDecimal maximumCost,
      @NotBlank @Size(max=2000) String reason,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}
}
