package org.uacrf.web;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class MembershipController {
  private final IdentityAccessClient identity;

  MembershipController(IdentityAccessClient identity) {
    this.identity = identity;
  }

  @GetMapping("/memberships")
  String memberships(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      @RequestParam(required = false) String cursor,
      Model model) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }
    return renderMemberships(client, context, cursor, null, null, false, model);
  }

  @PostMapping("/memberships/grant-decisions")
  String createGrantDecision(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      @Valid @ModelAttribute("grantDecisionForm") GrantDecisionForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      jakarta.servlet.http.HttpServletResponse response) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }
    Instant expiresAt = parseExpiry(form.expiresAt(), bindingResult);
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("grantDecisionError", "Correct the highlighted grant-decision fields.");
      return renderMemberships(client, context, null, null, null, false, model);
    }

    try {
      IdentityAccessClient.GrantDecisionReceipt receipt =
          identity.createGrantDecision(
              client.getAccessToken().getTokenValue(),
              context,
              form.id(),
              form.principalId(),
              form.capability(),
              form.serviceScope(),
              expiresAt,
              form.reason());
      redirectAttributes.addFlashAttribute(
          "grantDecisionMessage",
          "S01 recorded the independent grant decision. No membership was created.");
      redirectAttributes.addFlashAttribute("grantDecisionRef", receipt.id());
      return "redirect:/memberships";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "grantDecisionError",
          "UX01 is unavailable; retry with the same decision ID to confirm the outcome.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "grantDecisionError",
          "S01 did not record the grant decision (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("grantDecisionError", "UX01 returned an invalid grant-decision receipt.");
    }
    return renderMemberships(client, context, null, null, null, false, model);
  }

  @PostMapping("/memberships")
  String createMembership(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      @Valid @ModelAttribute("membershipCreationForm") MembershipCreationForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      jakarta.servlet.http.HttpServletResponse response) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }
    Instant expiresAt = parseExpiry(form.expiresAt(), bindingResult);
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("membershipCreationError", "Correct the highlighted membership fields.");
      return renderMemberships(client, context, null, null, null, false, model);
    }

    try {
      IdentityAccessClient.MembershipMutationReceipt receipt =
          identity.createMembership(
              client.getAccessToken().getTokenValue(),
              context,
              form.id(),
              form.principalId(),
              form.capability(),
              form.serviceScope(),
              expiresAt,
              form.reason(),
              form.authorizationRef(),
              form.idempotencyKey());
      redirectAttributes.addFlashAttribute(
          "membershipMessage",
          "S01 created membership "
              + receipt.id()
              + " and consumed the independent approval. No runtime action was performed.");
      return "redirect:/memberships";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "membershipCreationError",
          "UX01 is unavailable; the outcome is uncertain. Retry with the same approval reference and request key.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "membershipCreationError",
          "S01 did not confirm membership creation (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute(
          "membershipCreationError", "UX01 returned an invalid membership-creation receipt.");
    }
    return renderMemberships(client, context, null, null, null, false, model);
  }

  @PostMapping("/memberships/{membershipId}:revoke")
  String revokeMembership(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      @PathVariable("membershipId") String membershipId,
      @RequestParam(required = false) String cursor,
      @Valid @ModelAttribute("revokeForm") MembershipRevocationForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      jakarta.servlet.http.HttpServletResponse response) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("membershipError", "Provide a valid revision, reason, and request key.");
      return renderMemberships(
          client,
          context,
          cursor,
          membershipId,
          form,
          bindingResult.hasFieldErrors("reason"),
          model);
    }

    try {
      IdentityAccessClient.MembershipMutationReceipt receipt =
          identity.revokeMembership(
              client.getAccessToken().getTokenValue(),
              context,
              membershipId,
              form.expectedRevision(),
              form.reason(),
              form.idempotencyKey());
      redirectAttributes.addFlashAttribute(
          "membershipMessage",
          "Membership "
              + receipt.id()
              + " was revoked. No runtime action was performed ("
              + receipt.runtimeEffect()
              + ").");
      return "redirect:/memberships";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("membershipError", "UX01 is unavailable; the membership was not confirmed revoked.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "membershipError",
          "S01 did not revoke the membership (HTTP " + error.getStatusCode().value() + ").");
    }
    return renderMemberships(client, context, cursor, membershipId, form, false, model);
  }

  private String renderMemberships(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String cursor,
      String submittedMembershipId,
      MembershipRevocationForm submittedForm,
      boolean invalidReason,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("requestCursor", cursor);
    model.addAttribute("memberships", List.of());
    if (!model.containsAttribute("grantDecisionForm")) {
      model.addAttribute(
          "grantDecisionForm",
          new GrantDecisionForm(UUID.randomUUID().toString(), "", "", "", "", ""));
    }
    if (!model.containsAttribute("membershipCreationForm")) {
      model.addAttribute(
          "membershipCreationForm",
          new MembershipCreationForm(
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString(),
              "",
              "",
              "",
              "",
              "",
              ""));
    }

    model.addAttribute("canApproveGrant", false);
    model.addAttribute("canManageMemberships", false);
    try {
      IdentityAccessClient.MeAccess access =
          identity.currentAccess(
              client.getAccessToken().getTokenValue(),
              context.tenantId(),
              context.environmentId());
      if (access == null
          || access.capabilityIds() == null
          || access.capabilityIds().stream().anyMatch(capability -> capability == null || capability.isBlank())) {
        throw new IllegalStateException("S01 returned an incomplete current-access response.");
      }
      Set<String> capabilities = Set.copyOf(access.capabilityIds());
      model.addAttribute("canApproveGrant", capabilities.contains("identity.grant.approve"));
      model.addAttribute(
          "canManageMemberships", capabilities.contains("identity.membership.manage"));
    } catch (ResourceAccessException error) {
      model.addAttribute(
          "membershipAccessError", "UX01 is unavailable; membership controls were hidden.");
    } catch (RestClientResponseException error) {
      model.addAttribute(
          "membershipAccessError",
          "S01 did not return current permissions (HTTP " + error.getStatusCode().value() + "); controls were hidden.");
    } catch (IllegalStateException error) {
      model.addAttribute(
          "membershipAccessError", "S01 returned invalid current permissions; membership controls were hidden.");
    }

    try {
      IdentityAccessClient.MembershipPage page =
          identity.listMemberships(client.getAccessToken().getTokenValue(), context, cursor);
      List<MembershipRow> rows =
          page.items().stream()
              .map(
                  membership ->
                      new MembershipRow(
                          membership.id(),
                          membership.principalId(),
                          membership.capability(),
                          membership.serviceScope(),
                          membership.expiresAt().toString(),
                          membership.revision(),
                          UUID.randomUUID().toString(),
                          "",
                          false))
              .map(
                  row -> {
                    if (row.id().equals(submittedMembershipId) && submittedForm != null) {
                      return new MembershipRow(
                          row.id(),
                          row.principalId(),
                          row.capability(),
                          row.serviceScope(),
                          row.expiresAt(),
                          row.revision(),
                          validIdempotencyKey(submittedForm.idempotencyKey()),
                          submittedForm.reason(),
                          invalidReason);
                    }
                    return row;
                  })
              .toList();
      model.addAttribute("memberships", rows);
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      model.addAttribute("membershipsError", "UX01 is unavailable; memberships were not loaded.");
    } catch (RestClientResponseException error) {
      model.addAttribute(
          "membershipsError",
          "S01 did not return memberships (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "S01 returned an invalid response.", error);
    }
    return "memberships";
  }

  private static String validIdempotencyKey(String key) {
    try {
      UUID parsed = UUID.fromString(key);
      return parsed.toString().equalsIgnoreCase(key) ? key : UUID.randomUUID().toString();
    } catch (IllegalArgumentException | NullPointerException error) {
      return UUID.randomUUID().toString();
    }
  }

  record MembershipRow(
      String id,
      String principalId,
      String capability,
      String serviceScope,
      String expiresAt,
      long revision,
      String idempotencyKey,
      String reason,
      boolean invalidReason) {}

  record MembershipRevocationForm(
      @Min(1) long expectedRevision,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          String idempotencyKey) {}

  record GrantDecisionForm(
      @NotBlank
          @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          String id,
      @NotBlank
          @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          String principalId,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String capability,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String serviceScope,
      @NotBlank @Size(max = 50) String expiresAt,
      @NotBlank @Size(max = 2000) String reason) {}

  record MembershipCreationForm(
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          String id,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          String idempotencyKey,
      @NotBlank
          @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          String principalId,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String capability,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String serviceScope,
      @NotBlank @Size(max = 50) String expiresAt,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank
          @Size(max = 200)
          @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          String authorizationRef) {}

  private static Instant parseExpiry(String value, BindingResult bindingResult) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException | NullPointerException error) {
      bindingResult.rejectValue(
          "expiresAt", "invalid.expiresAt", "Enter an ISO-8601 UTC timestamp ending in Z.");
      return null;
    }
  }
}
