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
class AssessmentLearningReviewController {
  private final IdentityAccessClient identity;
  private final AssessmentClient assessment;

  AssessmentLearningReviewController(IdentityAccessClient identity, AssessmentClient assessment) {
    this.identity = identity;
    this.assessment = assessment;
  }

  @GetMapping("/assessment-learning-records/{id}/review")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String id, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("recordId", id);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.learning.read"), context.tenantId(), context.environmentId()).accessToken();
      var record = assessment.getLearningRecord(token, id);
      model.addAttribute("record", record);
      model.addAttribute("reviewForm", new ReviewForm(record.revision(), "", "", context.tenantId(), context.environmentId()));
      model.addAttribute("showForm", true);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity or assessment service is unavailable; the proposal was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Proposal access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity or assessment service returned invalid proposal details.");
    }
    return "assessment-learning-review";
  }

  @PostMapping("/assessment-learning-records/{id}/review")
  String review(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String id,
      @Valid @ModelAttribute("reviewForm") ReviewForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("recordId", id);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("reviewError", "The selected context changed. Return to the original context and review the proposal again.");
      return "assessment-learning-review";
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("showForm", true);
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "assessment-learning-review";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.learning.review"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity service is unavailable; review was not authorized.");
      return "assessment-learning-review";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Learning review was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "assessment-learning-review";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity service returned an invalid response; review was not authorized.");
      return "assessment-learning-review";
    }
    try {
      var request = new AssessmentClient.LearningReviewRequest(form.expectedRevision(), form.decision(), form.reason());
      model.addAttribute("review", assessment.reviewLearningRecord(token, id, request));
      return "assessment-learning-review-decision";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Review is unconfirmed. Read the retained decision before attempting another review.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Review failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Read the retained decision before attempting another review.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "The review response was invalid; review is unconfirmed. Read the retained decision before attempting another review.");
    }
    return "assessment-learning-review";
  }

  @GetMapping("/assessment-learning-records/{id}/review/decision")
  String decision(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String id, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("recordId", id);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.learning.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("review", assessment.getLearningReview(token, id));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity or assessment service is unavailable; the decision was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Decision access failed (HTTP " + error.getStatusCode().value()
          + "). A missing response does not confirm whether review occurred.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity or assessment service returned an invalid decision.");
    }
    return "assessment-learning-review-decision";
  }

  record ReviewForm(@NotNull @Min(1) Long expectedRevision,
      @NotBlank @Pattern(regexp="APPROVE|REJECT") String decision,
      @NotBlank @Size(max=2000) String reason, @NotBlank String tenantId, @NotBlank String environmentId) {}
}
