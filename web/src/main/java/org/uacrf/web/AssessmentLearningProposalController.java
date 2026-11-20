package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Controller
class AssessmentLearningProposalController {
  private static final Set<String> REQUIRED_ACTION_FIELDS = Set.of("description", "owner_id", "due_at", "closure_test");
  private static final Set<String> ACTION_FIELDS = Set.of("description", "owner_id", "due_at", "closure_test", "proposed_version_ref");
  private final IdentityAccessClient identity;
  private final AssessmentClient assessment;
  private final ObjectMapper json;
  private final Validator validator;

  AssessmentLearningProposalController(IdentityAccessClient identity, AssessmentClient assessment,
      ObjectMapper json, Validator validator) {
    this.identity = identity;
    this.assessment = assessment;
    this.json = json;
    this.validator = validator;
  }

  @GetMapping("/assessment-incidents/{incidentId}/learning-records/new")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String incidentId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("incidentId", incidentId);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.incident.read"), context.tenantId(), context.environmentId()).accessToken();
      var incident = assessment.getIncident(token, incidentId);
      model.addAttribute("proposalForm", new ProposalForm("", "", "[]",
          String.join("\n", incident.evidenceRefs()), incident.revision(), "", UUID.randomUUID().toString(),
          context.tenantId(), context.environmentId()));
      model.addAttribute("showForm", true);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("proposalError", "Identity or assessment service is unavailable; the incident was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("proposalError", "Incident access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("proposalError", "Identity or assessment service returned invalid incident details.");
    }
    return "assessment-learning-proposal-new";
  }

  @PostMapping("/assessment-incidents/{incidentId}/learning-records")
  String propose(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String incidentId,
      @Valid @ModelAttribute("proposalForm") ProposalForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("incidentId", incidentId);
    model.addAttribute("showForm", true);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("proposalError", "The selected context changed. Return to the original context before retrying this form.");
      return "assessment-learning-proposal-new";
    }
    List<String> hypotheses = lines(form.hypotheses());
    List<String> refs = lines(form.evidenceRefs());
    if (hypotheses.isEmpty() || hypotheses.size() > 100 || hypotheses.stream().anyMatch(s -> s.length() > 2000)) {
      errors.rejectValue("hypotheses", "invalid", "Enter 1 to 100 hypotheses, one per line, up to 2,000 characters each.");
    }
    if (refs.isEmpty() || refs.size() > 100 || refs.stream().anyMatch(s -> s.length() > 200)
        || refs.stream().distinct().count() != refs.size()) {
      errors.rejectValue("evidenceRefs", "invalid", "Enter 1 to 100 unique incident evidence IDs, one per line, up to 200 characters each.");
    }
    List<AssessmentClient.LearningAction> actions = List.of();
    if (!errors.hasFieldErrors("actionsJson")) {
      try {
        actions = parseActions(form.actionsJson());
      } catch (IllegalArgumentException | JacksonException error) {
        errors.rejectValue("actionsJson", "invalid", "Enter 1 to 100 valid action items as a JSON array, as described below.");
      }
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "assessment-learning-proposal-new";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.learning.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("proposalError", "Identity service is unavailable; the proposal was not authorized.");
      return "assessment-learning-proposal-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("proposalError", "Learning proposal was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "assessment-learning-proposal-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("proposalError", "Identity service returned an invalid response; the proposal was not authorized.");
      return "assessment-learning-proposal-new";
    }
    try {
      String experiment = form.experimentId() == null || form.experimentId().isBlank() ? null : form.experimentId();
      var request = new AssessmentClient.LearningProposal(incidentId, experiment, hypotheses, actions,
          refs, form.expectedRevision(), form.reason());
      model.addAttribute("proposal", assessment.proposeLearningRecord(token, form.idempotencyKey(), request));
      return "assessment-learning-proposal-created";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("proposalError", "The proposal is unconfirmed. Retry this same form without changing it.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("proposalError", "The proposal failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; check the learning records before starting a new request.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("proposalError", "The proposal response was invalid; submission is unconfirmed. Retry this same form unchanged.");
    }
    return "assessment-learning-proposal-new";
  }

  private static List<String> lines(String value) {
    return value == null ? List.of() : value.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  private List<AssessmentClient.LearningAction> parseActions(String input) {
    JsonNode root = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
    if (root == null || !root.isArray() || root.isEmpty() || root.size() > 100) throw new IllegalArgumentException("Invalid array");
    List<AssessmentClient.LearningAction> actions = new ArrayList<>();
    for (JsonNode node : root) {
      if (!node.isObject()) throw new IllegalArgumentException("Invalid action");
      Set<String> fields = Set.copyOf(node.propertyNames());
      if (!fields.containsAll(REQUIRED_ACTION_FIELDS) || !ACTION_FIELDS.containsAll(fields)
          || REQUIRED_ACTION_FIELDS.stream().anyMatch(field -> !node.get(field).isString())
          || (node.has("proposed_version_ref") && !node.get("proposed_version_ref").isNull()
              && !node.get("proposed_version_ref").isString())) throw new IllegalArgumentException("Invalid action");
      Instant due;
      try {
        due = Instant.parse(node.get("due_at").asString());
        if (due.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
            || due.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) throw new IllegalArgumentException("Invalid date");
      } catch (DateTimeParseException error) {
        throw new IllegalArgumentException("Invalid date", error);
      }
      String version = node.has("proposed_version_ref") && !node.get("proposed_version_ref").isNull()
          ? node.get("proposed_version_ref").asString() : null;
      var action = new AssessmentClient.LearningAction(node.get("description").asString(),
          node.get("owner_id").asString(), due, version, node.get("closure_test").asString());
      if (!validator.validate(action).isEmpty()) throw new IllegalArgumentException("Invalid action");
      actions.add(action);
    }
    return List.copyOf(actions);
  }

  record ProposalForm(@Size(max=200) String experimentId, @NotBlank @Size(max=200100) String hypotheses,
      @NotBlank @Size(max=1000000) String actionsJson, @NotBlank @Size(max=20100) String evidenceRefs,
      @NotNull @Min(1) Long expectedRevision, @NotBlank @Size(max=2000) String reason,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId) {}
}
