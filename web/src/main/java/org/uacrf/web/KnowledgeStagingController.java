package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class KnowledgeStagingController {
  private static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private final IdentityAccessClient identity;
  private final KnowledgeClient knowledge;

  KnowledgeStagingController(IdentityAccessClient identity, KnowledgeClient knowledge) {
    this.identity = identity;
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge/new")
  String form(HttpSession session, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("stageForm", new StageForm("", "", "", "", "", "PUBLIC",
        "", "", "", "", UUID.randomUUID().toString(), context.tenantId(), context.environmentId()));
    return "knowledge-new";
  }

  @PostMapping("/knowledge")
  String stage(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("stageForm") StageForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("stageError", "The selected context changed. Return to the original context before retrying this form.");
      return "knowledge-new";
    }
    List<String> purposes = lines(form.purposes());
    List<String> workloads = lines(form.workloads());
    if (!validSet(purposes, false)) errors.rejectValue("purposes", "invalid", "Enter 1 to 100 unique valid purpose identifiers.");
    if (!validSet(workloads, true)) errors.rejectValue("workloads", "invalid", "Enter at most 100 unique valid workload identifiers.");
    Instant deadline = null;
    try {
      if (form.reviewDeadline() == null || !form.reviewDeadline().endsWith("Z")) throw new DateTimeParseException("UTC required", "", 0);
      deadline = Instant.parse(form.reviewDeadline());
    } catch (DateTimeParseException error) {
      errors.rejectValue("reviewDeadline", "invalid", "Enter a UTC timestamp ending in Z, such as 2030-01-01T12:00:00Z.");
    }
    String content = form.content();
    if (content != null && (!StandardCharsets.UTF_8.newEncoder().canEncode(content)
        || content.getBytes(StandardCharsets.UTF_8).length > 262144
        || content.chars().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t'))) {
      errors.rejectValue("content", "invalid", "Enter valid plaintext of at most 256 KiB UTF-8 without unsupported control characters.");
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "knowledge-new";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          List.of("knowledge.item.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("stageError", "Identity service is unavailable; staging was not authorized.");
      return "knowledge-new";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("stageError", "Staging was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "knowledge-new";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("stageError", "Identity service returned an invalid response; staging was not authorized.");
      return "knowledge-new";
    }
    var request = new KnowledgeClient.StageRequest(form.itemId(), form.version(), form.title(),
        form.sourceRef() == null || form.sourceRef().isEmpty() ? null : form.sourceRef(), content,
        KnowledgeClient.Classification.valueOf(form.classification()), purposes, workloads, deadline, form.reason());
    try {
      model.addAttribute("receipt", knowledge.stage(token, form.idempotencyKey(), request));
      return "knowledge-staged";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("stageError", "Staging is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("stageError", "Staging failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; changed details require a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("stageError", "The staging response was invalid; staging is unconfirmed. Retry this same form unchanged.");
    }
    return "knowledge-new";
  }

  private static List<String> lines(String input) {
    return input == null ? List.of() : input.lines().map(String::strip).filter(value -> !value.isEmpty()).toList();
  }

  private static boolean validSet(List<String> values, boolean emptyAllowed) {
    return (emptyAllowed || !values.isEmpty()) && values.size() <= 100
        && values.stream().distinct().count() == values.size()
        && values.stream().allMatch(value -> value.matches(IDENTIFIER));
  }

  record StageForm(@NotBlank @Pattern(regexp=IDENTIFIER) String itemId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$") String version,
      @NotBlank @Size(max=200) String title, @Size(max=500) String sourceRef,
      @NotBlank @Size(max=262144) String content,
      @NotBlank @Pattern(regexp="PUBLIC|INTERNAL|RESTRICTED") String classification,
      @NotBlank @Size(max=20100) String purposes, @Size(max=20100) String workloads,
      @NotBlank @Size(max=40) String reviewDeadline, @NotBlank @Size(max=2000) String reason,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId) {}
}
