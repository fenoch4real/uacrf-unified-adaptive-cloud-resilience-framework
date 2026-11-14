package org.uacrf.web;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
}
