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
class KnowledgeWithdrawalController {
  private static final String ITEM_ID = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final String VERSION = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$";
  private final IdentityAccessClient identity;
  private final KnowledgeClient knowledge;

  KnowledgeWithdrawalController(IdentityAccessClient identity, KnowledgeClient knowledge) {
    this.identity = identity;
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge/{itemId}/versions/{version}/withdraw")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String itemId, @PathVariable String version,
      @RequestParam String classification, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    populate(model, context, itemId, version, classification);
    if (!itemId.matches(ITEM_ID) || !version.matches(VERSION)
        || !List.of("PUBLIC", "INTERNAL", "RESTRICTED").contains(classification)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("withdrawError", "Invalid item, version, or classification.");
      return "knowledge-withdraw";
    }
    var ceiling = KnowledgeClient.Classification.valueOf(classification);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          capabilities(ceiling, false), context.tenantId(), context.environmentId()).accessToken();
      var matches = knowledge.list(token, itemId, null, ceiling).stream()
          .filter(item -> item.version().equals(version) && item.classification() == ceiling
              && item.state() == KnowledgeClient.State.APPROVED).toList();
      if (matches.size() != 1) {
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("withdrawError", "This exact approved version is not available. Read current metadata.");
        return "knowledge-withdraw";
      }
      var item = matches.getFirst();
      model.addAttribute("item", item);
      model.addAttribute("withdrawForm", new WithdrawForm(item.revision(), item.sourceDigest(), classification,
          "", context.tenantId(), context.environmentId()));
      model.addAttribute("showForm", true);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("withdrawError", "Identity or knowledge service is unavailable; metadata was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("withdrawError", "Metadata access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("withdrawError", "Identity or knowledge service returned invalid metadata.");
    }
    return "knowledge-withdraw";
  }

  @PostMapping("/knowledge/{itemId}/versions/{version}/withdraw")
  String withdraw(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @PathVariable String itemId, @PathVariable String version,
      @Valid @ModelAttribute("withdrawForm") WithdrawForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    populate(model, context, itemId, version, form.classification());
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("withdrawError", "The selected context changed. Reopen this version in the original context.");
      return "knowledge-withdraw";
    }
    if (!itemId.matches(ITEM_ID) || !version.matches(VERSION) || errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("withdrawError", "Invalid withdrawal details. Reopen the approved version before submitting.");
      return "knowledge-withdraw";
    }
    var classification = KnowledgeClient.Classification.valueOf(form.classification());
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S08", null,
          capabilities(classification, true), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("withdrawError", "Identity service is unavailable; withdrawal was not authorized.");
      return "knowledge-withdraw";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("withdrawError", "Withdrawal was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "knowledge-withdraw";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("withdrawError", "Identity service returned invalid authorization; withdrawal was not submitted.");
      return "knowledge-withdraw";
    }
    try {
      var command = new KnowledgeClient.BoundWithdrawRequest(form.expectedRevision(), version, form.sourceDigest(), form.reason());
      model.addAttribute("receipt", knowledge.withdraw(token, itemId, command, classification));
      return "knowledge-withdrawn";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("withdrawError", "Withdrawal is unconfirmed. Read current metadata before attempting another withdrawal.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("withdrawError", "Withdrawal failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Read current metadata before attempting another withdrawal.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("withdrawError", "The withdrawal response was invalid; withdrawal is unconfirmed. Read current metadata before attempting another withdrawal.");
    }
    return "knowledge-withdraw";
  }

  private static void populate(Model model, BrowserContext context, String itemId, String version, String classification) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("itemId", itemId);
    model.addAttribute("version", version);
    model.addAttribute("classification", classification);
  }

  private static List<String> capabilities(KnowledgeClient.Classification classification, boolean withdraw) {
    List<String> capabilities = new ArrayList<>(List.of("knowledge.item.read"));
    if (withdraw) capabilities.add("knowledge.item.withdraw");
    if (classification == KnowledgeClient.Classification.INTERNAL) capabilities.add("knowledge.classification.internal.read");
    if (classification == KnowledgeClient.Classification.RESTRICTED) capabilities.add("knowledge.classification.restricted.read");
    return List.copyOf(capabilities);
  }

  record WithdrawForm(@NotNull @Min(1) @Max(Long.MAX_VALUE - 1) Long expectedRevision,
      @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String sourceDigest,
      @NotBlank @Pattern(regexp="PUBLIC|INTERNAL|RESTRICTED") String classification,
      @NotBlank @Size(max=2000) String reason, @NotBlank String tenantId, @NotBlank String environmentId) {}
}
