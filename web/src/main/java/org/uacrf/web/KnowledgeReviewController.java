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
class KnowledgeReviewController {
  private static final String ITEM_ID = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final String VERSION = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$";
  private final IdentityAccessClient identity;
  private final KnowledgeClient knowledge;

  KnowledgeReviewController(IdentityAccessClient identity, KnowledgeClient knowledge) {
    this.identity = identity;
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge/{itemId}/versions/{version}/review")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String itemId, @PathVariable String version,
      @RequestParam String classification, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("itemId", itemId);
    model.addAttribute("version", version);
    model.addAttribute("classification", classification);
    if (!itemId.matches(ITEM_ID) || !version.matches(VERSION) || !validClassification(classification)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("reviewError", "Invalid item, version, or classification.");
      return "knowledge-review";
    }
    var ceiling = KnowledgeClient.Classification.valueOf(classification);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          capabilities(ceiling), context.tenantId(), context.environmentId()).accessToken();
      var candidate = knowledge.candidate(token, itemId, version, ceiling);
      model.addAttribute("candidate", candidate);
      model.addAttribute("reviewForm", new ReviewForm(candidate.metadata().revision(), candidate.metadata().sourceDigest(),
          classification, "", "", context.tenantId(), context.environmentId()));
      model.addAttribute("showForm", true);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity or knowledge service is unavailable; candidate content was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Candidate access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity or knowledge service returned invalid candidate details.");
    }
    return "knowledge-review";
  }

  @PostMapping("/knowledge/{itemId}/versions/{version}/review")
  String review(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String itemId, @PathVariable String version,
      @Valid @ModelAttribute("reviewForm") ReviewForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("itemId", itemId);
    model.addAttribute("version", version);
    model.addAttribute("classification", form.classification());
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("reviewError", "The selected context changed. Return to the original context and reopen the candidate.");
      return "knowledge-review";
    }
    if (!itemId.matches(ITEM_ID) || !version.matches(VERSION) || errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("reviewError", "Invalid review details. Reopen the candidate before submitting.");
      return "knowledge-review";
    }
    var classification = KnowledgeClient.Classification.valueOf(form.classification());
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          capabilities(classification), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Identity service is unavailable; review was not authorized.");
      return "knowledge-review";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Review was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "knowledge-review";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "Identity service returned invalid authorization; review was not submitted.");
      return "knowledge-review";
    }
    try {
      var command = new KnowledgeClient.BoundReviewRequest(form.decision(), form.expectedRevision(), version,
          form.sourceDigest(), form.reason());
      model.addAttribute("receipt", knowledge.review(token, itemId, command, classification));
      return "knowledge-reviewed";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("reviewError", "Review is unconfirmed. Read current metadata before attempting another review.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("reviewError", "Review failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Read current metadata before attempting another review.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("reviewError", "The review response was invalid; review is unconfirmed. Read current metadata before attempting another review.");
    }
    return "knowledge-review";
  }

  private static boolean validClassification(String value) {
    return "PUBLIC".equals(value) || "INTERNAL".equals(value) || "RESTRICTED".equals(value);
  }

  private static List<String> capabilities(KnowledgeClient.Classification classification) {
    List<String> capabilities = new ArrayList<>(List.of("knowledge.item.read"));
    capabilities.add("knowledge.item.review");
    if (classification == KnowledgeClient.Classification.INTERNAL) capabilities.add("knowledge.classification.internal.read");
    if (classification == KnowledgeClient.Classification.RESTRICTED) capabilities.add("knowledge.classification.restricted.read");
    return List.copyOf(capabilities);
  }

  record ReviewForm(@NotNull @Min(1) @Max(Long.MAX_VALUE - 1) Long expectedRevision,
      @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String sourceDigest,
      @NotBlank @Pattern(regexp="PUBLIC|INTERNAL|RESTRICTED") String classification,
      @NotBlank @Pattern(regexp="APPROVE|REJECT") String decision,
      @NotBlank @Size(max=2000) String reason, @NotBlank String tenantId, @NotBlank String environmentId) {}
}
