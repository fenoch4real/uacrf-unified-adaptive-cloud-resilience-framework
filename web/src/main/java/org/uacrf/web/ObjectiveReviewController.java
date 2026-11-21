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
class ObjectiveReviewController {
  private final IdentityAccessClient identity;
  private final ObjectivesClient objectives;

  ObjectiveReviewController(IdentityAccessClient identity, ObjectivesClient objectives) {
    this.identity = identity;
    this.objectives = objectives;
  }

  @GetMapping("/objectives/review")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String id, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    try {
      String accessToken = client.getAccessToken().getTokenValue();
      var access = identity.currentAccess(accessToken, context.tenantId(), context.environmentId());
      if (access.principalId() == null || access.principalId().isBlank()) {
        throw new IllegalStateException("Identity returned an invalid reviewer.");
      }
      String token = identity.createDelegation(accessToken, "S05", null, List.of("objectives.objective.read"),
          context.tenantId(), context.environmentId()).accessToken();
      var objective = objectives.get(token, id);
      model.addAttribute("objective", objective);
      if ("SUBMITTED".equals(objective.state()) && objective.revision() < Long.MAX_VALUE) {
        model.addAttribute("review", new ReviewForm(objective.id(), objective.revision(), objective.contentDigest(),
            access.principalId(), "", "", UUID.randomUUID().toString(),
            context.tenantId(), context.environmentId(), client.getPrincipalName()));
      }
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity or objectives service is unavailable; the objective was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Objective access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity or objectives service returned invalid details.");
    }
    return "objective-review";
  }

  @PostMapping("/objectives/review")
  String decide(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("review") ReviewForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("reviewError", "The signed-in user or selected context changed. Return to the original user and context before retrying.");
      return "objective-review";
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("reviewError", "Review details are invalid. Return to the objective and review it again.");
      return "objective-review";
    }
    model.addAttribute("mayRetry", true);
    String token;
    try {
      String accessToken = client.getAccessToken().getTokenValue();
      var access = identity.currentAccess(accessToken, context.tenantId(), context.environmentId());
      if (!form.reviewerId().equals(access.principalId())) {
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("reviewError", "The reviewer identity changed. Return to the original reviewer before retrying.");
        return "objective-review";
      }
      token = identity.createDelegation(accessToken, "S05", null, List.of("objectives.objective.review"),
          context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity service is unavailable; the decision was not submitted.");
      return "objective-review";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Review was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "objective-review";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity service returned invalid authorization; the decision was not submitted.");
      return "objective-review";
    }
    try {
      model.addAttribute("objective", objectives.decide(token, form.id(), form.idempotencyKey(),
          form.expectedRevision(), form.contentDigest(), form.decision(), form.reason(), form.reviewerId()));
      return "objective-reviewed";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "The decision is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "The decision failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; review current state before starting a new decision.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "The decision response was invalid; the outcome is unconfirmed. Retry this same form unchanged.");
    }
    return "objective-review";
  }

  record ReviewForm(
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotNull @Min(1) @Max(9223372036854775806L) Long expectedRevision,
      @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String contentDigest,
      @NotBlank String reviewerId, @NotBlank @Pattern(regexp="^(APPROVE|REJECT)$") String decision,
      @NotBlank @Size(max=1000) String reason,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}
}
