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
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class KnowledgeRetrievalController {
  private static final String RETRIEVAL_ID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  private final IdentityAccessClient identity;
  private final KnowledgeClient knowledge;

  KnowledgeRetrievalController(IdentityAccessClient identity, KnowledgeClient knowledge) {
    this.identity = identity;
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge/retrieve")
  String form(HttpSession session, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("retrievalForm", new RetrievalForm("", "", "PUBLIC", "", 10,
        UUID.randomUUID().toString(), context.tenantId(), context.environmentId()));
    return "knowledge-retrieve";
  }

  @GetMapping("/knowledge/retrievals")
  String lookup(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String retrievalId,
      @RequestParam(defaultValue = "PUBLIC") String classification, Model model) {
    if (retrievalId != null) return read(client, session, response, retrievalId, classification, model);
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("classification", classification);
    return "knowledge-retrieval-lookup";
  }

  @GetMapping("/knowledge/retrievals/{id}")
  String read(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String id,
      @RequestParam String classification, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("retrievalId", id);
    model.addAttribute("classification", classification);
    if (!id.matches(RETRIEVAL_ID) || !List.of("PUBLIC", "INTERNAL", "RESTRICTED").contains(classification)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("lookupError", "Invalid retrieval ID or classification.");
      return "knowledge-retrieval-lookup";
    }
    var ceiling = KnowledgeClient.Classification.valueOf(classification);
    List<String> capabilities = new ArrayList<>(List.of("knowledge.retrieval.read"));
    if (ceiling == KnowledgeClient.Classification.INTERNAL) capabilities.add("knowledge.classification.internal.read");
    if (ceiling == KnowledgeClient.Classification.RESTRICTED) capabilities.add("knowledge.classification.restricted.read");
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          List.copyOf(capabilities), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("receipt", knowledge.readRetrieval(token, id, ceiling));
      return "knowledge-retrieved";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("lookupError", "Identity or knowledge service is unavailable; retrieval was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("lookupError", "Retrieval access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("lookupError", "Retrieval details were invalid or exceed the selected classification.");
    }
    return "knowledge-retrieval-lookup";
  }

  @GetMapping("/knowledge/retrieval-history")
  String history(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(defaultValue = "PUBLIC") String classification,
      @RequestParam(required = false) String after, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("classification", classification);
    if (!List.of("PUBLIC", "INTERNAL", "RESTRICTED").contains(classification)
        || (after != null && !after.matches(RETRIEVAL_ID))) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("historyError", "Invalid classification or page cursor.");
      return "knowledge-retrieval-history";
    }
    var ceiling = KnowledgeClient.Classification.valueOf(classification);
    List<String> capabilities = new ArrayList<>(List.of("knowledge.retrieval.read"));
    if (ceiling == KnowledgeClient.Classification.INTERNAL) capabilities.add("knowledge.classification.internal.read");
    if (ceiling == KnowledgeClient.Classification.RESTRICTED) capabilities.add("knowledge.classification.restricted.read");
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          List.copyOf(capabilities), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("page", knowledge.listRetrievals(token, after, ceiling));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("historyError", "Identity or knowledge service is unavailable; history was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("historyError", "Retrieval history access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("historyError", "Identity or knowledge service returned invalid retrieval history.");
    }
    return "knowledge-retrieval-history";
  }

  @PostMapping("/knowledge/retrieve")
  String retrieve(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("retrievalForm") RetrievalForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("retrievalError", "The selected context changed. Return to the original context before retrying.");
      return "knowledge-retrieve";
    }
    if (form.query() != null && !java.util.regex.Pattern.compile("[\\p{L}\\p{N}]").matcher(form.query()).find()) {
      errors.rejectValue("query", "invalid", "Enter at least one letter or number.");
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("validationErrors", errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
      return "knowledge-retrieve";
    }
    var classification = KnowledgeClient.Classification.valueOf(form.classification());
    List<String> capabilities = new ArrayList<>(List.of("knowledge.retrieval.run"));
    if (classification == KnowledgeClient.Classification.INTERNAL) capabilities.add("knowledge.classification.internal.read");
    if (classification == KnowledgeClient.Classification.RESTRICTED) capabilities.add("knowledge.classification.restricted.read");
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          List.copyOf(capabilities), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("retrievalError", "Identity service is unavailable; retrieval was not authorized.");
      return "knowledge-retrieve";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("retrievalError", "Retrieval was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "knowledge-retrieve";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("retrievalError", "Identity service returned invalid authorization; retrieval was not submitted.");
      return "knowledge-retrieve";
    }
    var request = new KnowledgeClient.RetrievalRequest(
        form.caseId() == null || form.caseId().isEmpty() ? null : form.caseId(),
        form.purpose(), classification, form.query(), form.maxResults());
    try {
      model.addAttribute("receipt", knowledge.retrieve(token, form.idempotencyKey(), request));
      return "knowledge-retrieved";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("retrievalError", "Retrieval is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("retrievalError", "Retrieval failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged; changed details require a new form.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("retrievalError", "The retrieval response was invalid; retrieval is unconfirmed. Retry this same form unchanged.");
    }
    return "knowledge-retrieve";
  }

  record RetrievalForm(@Pattern(regexp="^$|^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String caseId,
      @NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String purpose,
      @NotBlank @Pattern(regexp="PUBLIC|INTERNAL|RESTRICTED") String classification,
      @NotBlank @Size(max=2000) String query, @NotNull @Min(1) @Max(10) Integer maxResults,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId) {}
}
