package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
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

@Controller
class OwnershipTransferController {
  private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final List<String> JOURNEY_READ_CAPABILITIES =
      List.of("catalog.journey.read");
  private static final List<String> SERVICE_READ_CAPABILITIES =
      List.of("catalog.service.read");

  private final IdentityAccessClient identity;
  private final CatalogClient catalog;

  OwnershipTransferController(IdentityAccessClient identity, CatalogClient catalog) {
    this.identity = identity;
    this.catalog = catalog;
  }

  @GetMapping("/ownership-transfers/{objectKind}/{objectId}")
  String transferForm(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String objectKind,
      @PathVariable String objectId,
      @RequestParam(required = false) String ownerCursor,
      @RequestParam(defaultValue = "false") boolean updated,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }

    CatalogClient.OwnershipTarget target =
        loadTarget(client, context, objectKind, objectId, response, model);
    IdentityAccessClient.OwnerPage owners =
        target == null
            ? null
            : loadOwners(
                client,
                context,
                objectKind,
                objectId,
                ownerCursor,
                response,
                model);
    if (updated && target != null) {
      model.addAttribute("ownershipStatus", "Ownership was updated successfully.");
    }
    return render(context, objectKind, objectId, target, owners, ownerCursor, model);
  }

  @PostMapping("/ownership-transfers/{objectKind}/{objectId}")
  String transfer(
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      @PathVariable String objectKind,
      @PathVariable String objectId,
      @Valid @ModelAttribute("transferForm") TransferForm form,
      BindingResult bindingResult,
      HttpSession session,
      HttpServletResponse response,
      Model model) {
    BrowserContext context = selectedContext(session);
    if (context == null) {
      return "redirect:/contexts";
    }
    if (!isSupported(objectKind) || !isValidId(objectId)) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      model.addAttribute("ownershipError", "The ownership-transfer target is invalid.");
      return render(context, objectKind, objectId, null, null, form.ownerCursor(), model);
    }
    if (bindingResult.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute(
          "validationErrors",
          bindingResult.getFieldErrors().stream()
              .map(error -> error.getField() + ": " + error.getDefaultMessage())
              .toList());
      model.addAttribute("ownerError", fieldError(bindingResult, "ownerId"));
      model.addAttribute("reasonError", fieldError(bindingResult, "reason"));
      model.addAttribute("invalidOwner", bindingResult.hasFieldErrors("ownerId"));
      model.addAttribute("invalidReason", bindingResult.hasFieldErrors("reason"));
      CatalogClient.OwnershipTarget target =
          loadTarget(client, context, objectKind, objectId, response, model);
      IdentityAccessClient.OwnerPage owners =
          target == null
              ? null
              : loadOwners(
                  client,
                  context,
                  objectKind,
                  objectId,
                  form.ownerCursor(),
                  response,
                  model);
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    }

    CatalogClient.OwnershipTarget target =
        loadTarget(client, context, objectKind, objectId, response, model);
    IdentityAccessClient.OwnerPage owners =
        target == null
            ? null
            : loadOwners(
                client,
                context,
                objectKind,
                objectId,
                form.ownerCursor(),
                response,
                model);
    if (target == null || owners == null) {
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    }
    String submittedOwnerId = form.ownerId();
    boolean eligibleOwner =
        owners.items().stream()
            .anyMatch(
                candidate ->
                    submittedOwnerId.equals(candidate.id())
                        && candidate.enabled()
                        && candidate.ownerEligible()
                        && "HUMAN".equals(candidate.kind()));
    if (!eligibleOwner) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      model.addAttribute(
          "ownershipError",
          "Select an active, eligible human owner from the current S01 owner list.");
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    }

    String serviceId = "SERVICE".equals(objectKind) ? objectId : null;
    String writeCapability =
        "JOURNEY".equals(objectKind) ? "catalog.journey.write" : "catalog.service.write";
    String delegationToken;
    try {
      delegationToken =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  serviceId,
                  List.of("ownership.transfer", writeCapability),
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "ownershipError", "UX01 is unavailable; ownership transfer was not authorized.");
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "ownershipError",
          "S01 did not authorize ownership transfer (HTTP "
              + error.getStatusCode().value()
              + ").");
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    }

    CatalogClient.OwnershipTransfer transferRequest =
        new CatalogClient.OwnershipTransfer(
            objectKind,
            objectId,
            form.ownerId(),
            form.expectedRevision(),
            form.reason(),
            null);
    try {
      catalog.transferOwnership(delegationToken, form.idempotencyKey(), transferRequest);
      return "redirect:/ownership-transfers/" + objectKind + "/" + objectId + "?updated=true";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "ownershipError",
          "S02 is unavailable; the ownership result is unconfirmed. Retry this same form to safely replay the request.");
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "ownershipError",
          "S02 did not transfer ownership (HTTP " + error.getStatusCode().value() + ").");
      return render(context, objectKind, objectId, target, owners, form.ownerCursor(), model);
    }
  }

  private CatalogClient.OwnershipTarget loadTarget(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String objectKind,
      String objectId,
      HttpServletResponse response,
      Model model) {
    if (!isSupported(objectKind) || !isValidId(objectId)) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      model.addAttribute("ownershipError", "The ownership-transfer target is invalid.");
      return null;
    }

    boolean service = "SERVICE".equals(objectKind);
    List<String> readCapabilities =
        service ? SERVICE_READ_CAPABILITIES : JOURNEY_READ_CAPABILITIES;
    String serviceId = service ? objectId : null;
    try {
      String delegationToken =
          identity
              .createDelegation(
                  client.getAccessToken().getTokenValue(),
                  "S02",
                  serviceId,
                  readCapabilities,
                  context.tenantId(),
                  context.environmentId())
              .accessToken();
      if (service) {
        return catalog
            .findService(delegationToken, objectId)
            .map(
                record ->
                    new CatalogClient.OwnershipTarget(
                        objectKind, record.id(), record.name(), record.ownerId(), record.revision()))
            .orElseGet(
                () -> {
                  response.setStatus(HttpStatus.NOT_FOUND.value());
                  model.addAttribute("ownershipError", "The service is absent or not visible.");
                  return null;
                });
      }
      CatalogClient.JourneyDetails journey = catalog.getJourney(delegationToken, objectId);
      return new CatalogClient.OwnershipTarget(
          objectKind, journey.id(), journey.name(), journey.ownerId(), journey.revision());
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "ownershipError", "A required UACRF service is unavailable; the target was not loaded.");
      return null;
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "ownershipError",
          "S01 or S02 did not authorize reading the target (HTTP "
              + error.getStatusCode().value()
              + ").");
      return null;
    }
  }

  private IdentityAccessClient.OwnerPage loadOwners(
      OAuth2AuthorizedClient client,
      BrowserContext context,
      String objectKind,
      String objectId,
      String cursor,
      HttpServletResponse response,
      Model model) {
    if (cursor != null && cursor.length() > 500) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("ownershipError", "The owner-list cursor is invalid; reload the page.");
      return null;
    }
    try {
      IdentityAccessClient.MeAccess access =
          identity.currentAccess(
              client.getAccessToken().getTokenValue(),
              context.tenantId(),
              context.environmentId());
      if (access.capabilityIds() == null
          || !access.capabilityIds().contains("ownership.transfer")
          || !access.capabilityIds().contains("identity.owner.select")) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        model.addAttribute(
            "ownershipError",
            "The selected context does not grant ownership.transfer and identity.owner.select.");
        return null;
      }
      IdentityAccessClient.OwnerPage page =
          identity.ownerCandidates(
              client.getAccessToken().getTokenValue(),
              context,
              objectKind,
              objectId,
              normalizeCursor(cursor));
      if (page == null) {
        throw new IllegalStateException("UX01 returned an empty owner-candidate page.");
      }
      return page;
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute(
          "ownershipError", "UX01 is unavailable; eligible owners were not loaded.");
      return null;
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute(
          "ownershipError",
          "S01 did not authorize owner selection (HTTP " + error.getStatusCode().value() + ").");
      return null;
    }
  }

  private String render(
      BrowserContext context,
      String objectKind,
      String objectId,
      CatalogClient.OwnershipTarget target,
      IdentityAccessClient.OwnerPage owners,
      String ownerCursor,
      Model model) {
    model.addAttribute("selectedContext", context);
    model.addAttribute("objectKind", objectKind);
    model.addAttribute("objectId", objectId);
    model.addAttribute("target", target);
    model.addAttribute(
        "ownerCandidates",
        owners == null || target == null
            ? null
            : owners.items().stream()
                .filter(owner -> !target.ownerId().equals(owner.id()))
                .toList());
    model.addAttribute("nextOwnerCursor", owners == null ? null : owners.nextCursor());
    if (!model.containsAttribute("transferForm")) {
      model.addAttribute(
          "transferForm",
          new TransferForm(
              "",
              target == null ? 0 : target.revision(),
              "",
              UUID.randomUUID().toString(),
              ownerCursor));
    }
    return "ownership-transfer";
  }

  private static boolean isSupported(String objectKind) {
    return "JOURNEY".equals(objectKind) || "SERVICE".equals(objectKind);
  }

  private static boolean isValidId(String value) {
    return value != null && value.matches(ID_PATTERN);
  }

  private static String normalizeCursor(String cursor) {
    return cursor == null || cursor.isBlank() ? null : cursor;
  }

  private static String fieldError(BindingResult bindingResult, String field) {
    return bindingResult.hasFieldErrors(field)
        ? bindingResult.getFieldError(field).getDefaultMessage()
        : null;
  }

  private static BrowserContext selectedContext(HttpSession session) {
    Object contextAttribute = session.getAttribute(BrowserContext.SESSION_KEY);
    return contextAttribute instanceof BrowserContext context ? context : null;
  }

  record TransferForm(
      @NotBlank @Pattern(regexp = ID_PATTERN) String ownerId,
      @Min(1) long expectedRevision,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank
          @Pattern(
              regexp =
                  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
          String idempotencyKey,
          @Size(max = 500) String ownerCursor) {}
}
