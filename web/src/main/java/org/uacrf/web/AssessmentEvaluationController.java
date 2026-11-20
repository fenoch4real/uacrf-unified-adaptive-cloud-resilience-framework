package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
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
class AssessmentEvaluationController {
  private static final Set<String> MEASUREMENT_FIELDS =
      Set.of("source_receipt_id", "source_kind", "observed_at", "value", "unit");
  private final IdentityAccessClient identity;
  private final AssessmentClient assessment;
  private final ObjectMapper json;
  private final Validator validator;

  AssessmentEvaluationController(IdentityAccessClient identity, AssessmentClient assessment,
      ObjectMapper json, Validator validator) {
    this.identity = identity;
    this.assessment = assessment;
    this.json = json;
    this.validator = validator;
  }

  @GetMapping("/assessment-cases/{caseId}/evaluate")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    model.addAttribute("evaluationForm", new EvaluationForm("", "SHADOW", "[]",
        UUID.randomUUID().toString(), context.tenantId(), context.environmentId()));
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.case.read"), context.tenantId(), context.environmentId()).accessToken();
      var item = assessment.getCase(token, caseId);
      if (!"SEALED".equals(item.state())) {
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("evaluationError", "Only a sealed case can be evaluated.");
      } else {
        model.addAttribute("caseItem", item);
        model.addAttribute("showForm", true);
      }
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("evaluationError", "Identity or assessment service is unavailable; the case was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("evaluationError", "Case access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("evaluationError", "Identity or assessment service returned invalid case details.");
    }
    return "assessment-evaluation-new";
  }

  @PostMapping("/assessment-cases/{caseId}/evaluate")
  String evaluate(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String caseId,
      @Valid @ModelAttribute("evaluationForm") EvaluationForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) {
      return "redirect:/contexts";
    }
    model.addAttribute("selectedContext", context);
    model.addAttribute("caseId", caseId);
    model.addAttribute("showForm", true);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("evaluationError", "The selected context changed. Return to the original context before retrying this form.");
      return "assessment-evaluation-new";
    }
    Instant cutoff = null;
    List<AssessmentClient.EvaluationMeasurement> measurements = List.of();
    if (!errors.hasFieldErrors("observationCutoff")) {
      try {
        cutoff = Instant.parse(form.observationCutoff());
        cutoff.minusSeconds(31536000);
      } catch (DateTimeException error) {
        errors.rejectValue("observationCutoff", "invalid", "Enter an ISO-8601 timestamp with a UTC offset, such as 2026-01-01T12:00:00Z.");
      }
    }
    if (!errors.hasFieldErrors("measurementsJson")) {
      try {
        measurements = parseMeasurements(form.measurementsJson());
      } catch (IllegalArgumentException | JacksonException error) {
        errors.rejectValue("measurementsJson", "invalid", "Enter a JSON array of up to 1,000 valid measurements as described below, with unique receipt IDs.");
      }
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "assessment-evaluation-new";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S07", null,
          List.of("assessment.evaluation.run"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("evaluationError", "Identity service is unavailable; evaluation was not authorized.");
      return "assessment-evaluation-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("evaluationError", "Evaluation was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "assessment-evaluation-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("evaluationError", "Identity service returned an invalid response; evaluation was not authorized.");
      return "assessment-evaluation-new";
    }
    try {
      var request = new AssessmentClient.EvaluationRequest(cutoff, form.mode(), measurements);
      model.addAttribute("evaluation", assessment.evaluateCase(token, caseId, form.idempotencyKey(), request));
      return "assessment-evaluation-created";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("evaluationError", "Evaluation is unconfirmed. Retry this same form without changing it.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("evaluationError", "Evaluation failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; check the history before starting a new request.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("evaluationError", "The evaluation response was invalid; evaluation is unconfirmed. Retry this same form unchanged.");
    }
    return "assessment-evaluation-new";
  }

  private List<AssessmentClient.EvaluationMeasurement> parseMeasurements(String input) {
    JsonNode root = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(input);
    if (root == null || !root.isArray() || root.size() > 1000) throw new IllegalArgumentException("Invalid array");
    List<AssessmentClient.EvaluationMeasurement> values = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode node : root) {
      if (!node.isObject() || !MEASUREMENT_FIELDS.equals(Set.copyOf(node.propertyNames()))
          || !node.get("source_receipt_id").isString() || !node.get("source_kind").isString()
          || !node.get("observed_at").isString() || !node.get("value").isNumber()
          || !node.get("unit").isString()) throw new IllegalArgumentException("Invalid measurement");
      Instant observedAt;
      try {
        observedAt = Instant.parse(node.get("observed_at").asString());
      } catch (DateTimeParseException error) {
        throw new IllegalArgumentException("Invalid observation timestamp", error);
      }
      var value = new AssessmentClient.EvaluationMeasurement(node.get("source_receipt_id").asString(),
          node.get("source_kind").asString(), observedAt, node.get("value").decimalValue(), node.get("unit").asString());
      if ((long) value.value().precision() - value.value().scale() > 100L
          || value.value().scale() > 50 || !validator.validate(value).isEmpty() || !ids.add(value.sourceReceiptId())) {
        throw new IllegalArgumentException("Invalid measurement values");
      }
      values.add(value);
    }
    return List.copyOf(values);
  }

  record EvaluationForm(
      @NotBlank @Size(max=100) String observationCutoff,
      @NotBlank @Pattern(regexp="SHADOW|DRY_RUN|LIVE") String mode,
      @NotBlank @Size(max=1000000) String measurementsJson,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId) {}
}
