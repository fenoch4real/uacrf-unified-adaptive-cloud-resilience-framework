package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
class PrincipalDirectoryController {
  private final IdentityAccessClient identity;

  PrincipalDirectoryController(IdentityAccessClient identity) {
    this.identity = identity;
  }

  @GetMapping("/principals")
  String principals(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      HttpServletResponse response,
      @RequestParam(required = false) String cursor,
      Model model) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }

    return renderPrincipals(
        client,
        context,
        cursor,
        new PrincipalAdmissionForm(
            UUID.randomUUID().toString(), "", "", "", UUID.randomUUID().toString()),
        null,
        null,
        response,
        model);
  }

  @PostMapping("/principals")
  String admitPrincipal(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      @RequestParam(required = false) String cursor,
      @Valid @ModelAttribute("principalAdmissionForm") PrincipalAdmissionForm form,
      BindingResult bindingResult,
      HttpServletResponse response,
      Model model,
      RedirectAttributes redirectAttributes) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }

    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("principalAdmissionError", "Review the field errors and try again.");
      return renderPrincipals(client, context, cursor, form, null, null, response, model);
    }

    try {
      IdentityAccessClient.PrincipalAdmissionReceipt receipt =
          identity.admitPrincipal(
              client.getAccessToken().getTokenValue(),
              context,
              form.id(),
              form.providerId(),
              form.directorySubjectRef(),
              form.reason(),
              form.idempotencyKey());
      redirectAttributes.addFlashAttribute(
          "principalMessage",
          "Principal "
              + receipt.id()
              + " was admitted from the trusted directory. This operation added no memberships "
              + "or capabilities.");
      return "redirect:/principals";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "principalAdmissionError",
          "UX01 is unavailable; the result is unconfirmed. Retry with the same form values and request key.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "principalAdmissionError",
          "S01 did not confirm principal admission (HTTP "
              + error.getStatusCode().value()
              + ").");
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "S01 returned an invalid admission receipt.", error);
    }
    return renderPrincipals(client, context, cursor, form, null, null, response, model);
  }

  @PostMapping("/principals/{principalId}:disable")
  String disablePrincipal(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session,
      @PathVariable String principalId,
      @RequestParam(required = false) String cursor,
      @Valid @ModelAttribute("principalDisableForm") PrincipalDisableForm form,
      BindingResult bindingResult,
      HttpServletResponse response,
      Model model,
      RedirectAttributes redirectAttributes) {
    BrowserContext context = (BrowserContext) session.getAttribute(BrowserContext.SESSION_KEY);
    if (context == null) {
      return "redirect:/contexts";
    }

    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("principalStatusError", "Correct the disable reason and try again.");
      model.addAttribute("principalDisableErrorPrincipalId", principalId);
      if (bindingResult.getFieldError("reason") != null) {
        model.addAttribute(
            "principalDisableReasonError",
            bindingResult.getFieldError("reason").getDefaultMessage());
      }
      return renderPrincipals(
          client,
          context,
          cursor,
          new PrincipalAdmissionForm(
              UUID.randomUUID().toString(), "", "", "", UUID.randomUUID().toString()),
          principalId,
          form,
          response,
          model);
    }

    try {
      IdentityAccessClient.PrincipalStatusReceipt receipt =
          identity.disablePrincipal(
              client.getAccessToken().getTokenValue(),
              principalId,
              form.expectedRevision(),
              form.reason(),
              form.idempotencyKey());
      redirectAttributes.addFlashAttribute(
          "principalMessage",
          "Principal "
              + receipt.id()
              + " was disabled. No memberships were removed; re-enabling requires trusted-source "
              + "verification and independent grant review.");
      if (cursor != null && !cursor.isBlank()) {
        redirectAttributes.addAttribute("cursor", cursor);
      }
      return "redirect:/principals";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "principalStatusError",
          "UX01 is unavailable; the result is unconfirmed. Retry with the same reason and request key.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "principalStatusError",
          "S01 did not confirm principal disable (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "S01 returned an invalid principal-status receipt.", error);
    }
    return renderPrincipals(
        client,
        context,
        cursor,
        new PrincipalAdmissionForm(
            UUID.randomUUID().toString(), "", "", "", UUID.randomUUID().toString()),
        principalId,
        form,
        response,
        model);
  }

  private String renderPrincipals(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String cursor,
      PrincipalAdmissionForm form,
      String submittedPrincipalId,
      PrincipalDisableForm submittedDisableForm,
      HttpServletResponse response,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("requestCursor", cursor);
    model.addAttribute("principalAdmissionForm", form);
    List<IdentityAccessClient.Principal> principals = List.of();
    model.addAttribute("principals", principals);
    model.addAttribute("nextCursor", null);
    try {
      IdentityAccessClient.PrincipalPage page =
          identity.listPrincipals(client.getAccessToken().getTokenValue(), context, cursor);
      principals = page.items();
      model.addAttribute("principals", principals);
      model.addAttribute("nextCursor", page.nextCursor());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("principalError", "UX01 is unavailable; principals were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "principalError",
          "S01 did not return principals (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "S01 returned an invalid response.", error);
    }

    String accessToken = client.getAccessToken().getTokenValue();
    model.addAttribute("canAdmitPrincipal", false);
    model.addAttribute("canDisablePrincipal", false);
    model.addAttribute("trustedProviders", null);
    Map<String, PrincipalDisableForm> disableForms = new HashMap<>();
    for (IdentityAccessClient.Principal principal : principals) {
      if (principal.enabled()) {
        disableForms.put(
            principal.id(),
            principal.id().equals(submittedPrincipalId) && submittedDisableForm != null
                ? submittedDisableForm
                : new PrincipalDisableForm(
                    principal.revision(), "", UUID.randomUUID().toString()));
      }
    }
    model.addAttribute("principalDisableForms", disableForms);
    try {
      IdentityAccessClient.MeAccess access =
          identity.currentAccess(accessToken, context.tenantId(), context.environmentId());
      if (access.capabilityIds() == null) {
        throw new IllegalStateException("UX01 returned current access without capabilities.");
      }
      if (access.capabilityIds().contains("identity.principal.manage")) {
        model.addAttribute("canAdmitPrincipal", true);
        model.addAttribute("canDisablePrincipal", true);
        model.addAttribute("trustedProviders", List.of());
        try {
          IdentityAccessClient.IdentityProviderList providerList =
              identity.listIdentityProviders(accessToken, context);
          model.addAttribute(
              "trustedProviders",
              providerList.items().stream()
                  .filter(provider -> "TRUSTED".equals(provider.status()))
                  .toList());
        } catch (ResourceAccessException error) {
          response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
          model.addAttribute(
              "identityProvidersError",
              "UX01 is unavailable; trusted identity providers were not loaded.");
        } catch (RestClientResponseException error) {
          response.setStatus(error.getStatusCode().value());
          model.addAttribute(
              "identityProvidersError",
              "S01 did not return trusted identity providers (HTTP "
                  + error.getStatusCode().value()
                  + ").");
        } catch (IllegalStateException error) {
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "S01 returned an invalid identity-provider list.", error);
        }
      }
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "principalAdmissionAuthorityError",
          "UX01 is unavailable; principal-management controls were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "principalAdmissionAuthorityError",
          "S01 did not evaluate principal-management authority (HTTP "
              + error.getStatusCode().value()
              + ").");
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "S01 returned an invalid current-access response.", error);
    }
    return "principals";
  }

  private record PrincipalAdmissionForm(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String id,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") String providerId,
      @NotBlank @Size(max = 200) @Pattern(regexp = "^\\S+$") String directorySubjectRef,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          String idempotencyKey) {}

  private record PrincipalDisableForm(
      @Min(1) long expectedRevision,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank
          @Pattern(
                  regexp =
                      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          String idempotencyKey) {}
}
