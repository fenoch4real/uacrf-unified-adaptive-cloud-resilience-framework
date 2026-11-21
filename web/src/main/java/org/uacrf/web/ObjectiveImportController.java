package org.uacrf.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Controller
class ObjectiveImportController {
  private final IdentityAccessClient identity;
  private final ObjectivesClient objectives;
  private final ConnectionsClient connections;

  ObjectiveImportController(IdentityAccessClient identity, ObjectivesClient objectives, ConnectionsClient connections) {
    this.identity = identity;
    this.objectives = objectives;
    this.connections = connections;
  }

  @GetMapping("/objectives/import/sample-source")
  String sampleSource(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String connectionId,
      @RequestParam String externalObjectiveId, @RequestParam String targetWorkloadId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (connectionId.isBlank() || connectionId.length() > 200
        || externalObjectiveId.isBlank() || externalObjectiveId.length() > 1000
        || targetWorkloadId.isBlank() || targetWorkloadId.length() > 200) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("sourceError", "Provide a valid connection, sample objective and workload ID.");
      return "objective-import-sample-source";
    }
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S03", null,
          List.of("connections.connection.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("source", connections.sampleObjective(token, connectionId, externalObjectiveId, targetWorkloadId));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("sourceError", "Identity or connections service is unavailable; the sample was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("sourceError", "Sample source access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("sourceError", "Identity or connections service returned an invalid response.");
    }
    return "objective-import-sample-source";
  }

  @GetMapping("/objectives/import")
  String form(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    model.addAttribute("importForm", new ImportForm("", "", "", null, null, UUID.randomUUID().toString(),
        context.tenantId(), context.environmentId(), client.getPrincipalName()));
    return "objective-import";
  }

  @PostMapping("/objectives/import")
  String submit(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("importForm") ImportForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("importError", "The signed-in user or context changed. Return to the original user and context.");
      return "objective-import";
    }
    if (errors.hasErrors()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("importError", "Check the required fields and revision values.");
      return "objective-import";
    }
    String token;
    try {
      token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.write"), context.tenantId(), context.environmentId()).accessToken();
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("importError", "Identity service is unavailable; the request was not submitted.");
      return "objective-import";
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("importError", "Import request was not authorized (HTTP " + error.getStatusCode().value() + ").");
      return "objective-import";
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("importError", "Identity service returned invalid authorization; the request was not submitted.");
      return "objective-import";
    }
    try {
      model.addAttribute("receipt", objectives.previewImport(token, form.idempotencyKey(),
          new ObjectivesClient.ImportPreview(form.connectionId(), form.externalObjectiveId(),
              form.targetWorkloadId(), form.mappingRevision(), form.expectedLocalRevision())));
      return "objective-import-receipt";
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("importError", "Request outcome is unconfirmed. Retry this same form unchanged in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("importError", "Request failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form unchanged.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("importError", "The response was invalid; the outcome is unconfirmed. Retry this same form unchanged.");
    }
    return "objective-import";
  }

  @GetMapping("/objectives/import/receipt")
  String receipt(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String id, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("receipt", objectives.importReceipt(token, id));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("receiptError", "Identity or objectives service is unavailable; the receipt was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("receiptError", "Import receipt access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("receiptError", "Identity or objectives service returned an invalid response.");
    }
    return "objective-import-receipt";
  }

  @GetMapping("/objectives/import/receipt/sample-source")
  String receiptSampleSource(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String id, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("selectedContext", context);
    model.addAttribute("receiptId", id);
    if (!ObjectivesClient.validUuid(id)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("sourceError", "Provide a valid import receipt ID.");
      return "objective-import-receipt-source";
    }
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("source", objectives.inspectImportSample(token, id));
      model.addAttribute("captureForm", new CaptureForm(id, UUID.randomUUID().toString(),
          context.tenantId(), context.environmentId(), client.getPrincipalName()));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("sourceError", "Identity or objectives service is unavailable; the sample was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("sourceError", "Pending sample inspection failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("sourceError", "Identity or objectives service returned an invalid response.");
    }
    return "objective-import-receipt-source";
  }

  @PostMapping("/objectives/import/receipt/sample-capture")
  String capture(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @Valid @ModelAttribute("captureForm") CaptureForm form, BindingResult errors, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("selectedContext", context);
    if (!context.tenantId().equals(form.tenantId()) || !context.environmentId().equals(form.environmentId())
        || !client.getPrincipalName().equals(form.principalName())) {
      response.setStatus(HttpStatus.CONFLICT.value());
      model.addAttribute("captureError", "The signed-in user or context changed; reopen the original request.");
      return "objective-import-capture";
    }
    if (errors.hasErrors() || !ObjectivesClient.validUuid(form.receiptId())) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("captureError", "The capture form is invalid; reopen the original request.");
      return "objective-import-capture";
    }
    model.addAttribute("retryAllowed", true);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read", "objectives.objective.write"),
          context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("captureReceipt", objectives.captureImportSample(token, form.receiptId(), form.idempotencyKey()));
      model.addAttribute("retryAllowed", false);
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("captureError", "Capture outcome is unconfirmed. Retry this same form in the same context.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("captureError", "Capture failed or is unconfirmed (HTTP " + error.getStatusCode().value()
          + "). Retry only this same form in the same context.");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("captureError", "The response was invalid; the outcome is unconfirmed. Retry this same form.");
    }
    return "objective-import-capture";
  }

  @GetMapping("/objectives/import/receipt/sample-capture")
  String retainedCapture(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response, @RequestParam String id,
      @RequestParam String captureId, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("selectedContext", context);
    if (!ObjectivesClient.validUuid(id) || !ObjectivesClient.validUuid(captureId)) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("captureError", "Provide valid import request and capture IDs.");
      return "objective-import-capture-detail";
    }
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("capture", objectives.getImportSampleCapture(token, id, captureId));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("captureError", "Identity or objectives service is unavailable; the capture was not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("captureError", "Retained capture access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("captureError", "Identity or objectives service returned an invalid capture.");
    }
    return "objective-import-capture-detail";
  }

  @GetMapping("/objectives/import/requests")
  String requests(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      HttpSession session, HttpServletResponse response,
      @RequestParam(required = false) String cursor, Model model) {
    if (!(session.getAttribute(BrowserContext.SESSION_KEY) instanceof BrowserContext context)) return "redirect:/contexts";
    model.addAttribute("selectedContext", context);
    try {
      String token = identity.createDelegation(client.getAccessToken().getTokenValue(), "S05", null,
          List.of("objectives.objective.read"), context.tenantId(), context.environmentId()).accessToken();
      model.addAttribute("requests", objectives.listImports(token, cursor));
    } catch (ResourceAccessException error) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("requestsError", "Identity or objectives service is unavailable; requests were not loaded.");
    } catch (RestClientResponseException error) {
      response.setStatus(error.getStatusCode().value());
      model.addAttribute("requestsError", "Import request access failed (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException | RestClientException error) {
      response.setStatus(HttpStatus.BAD_GATEWAY.value());
      model.addAttribute("requestsError", "Identity or objectives service returned an invalid response.");
    }
    return "objective-import-requests";
  }

  record ImportForm(
      @NotBlank @Size(max=200) String connectionId,
      @NotBlank @Size(max=1000) String externalObjectiveId,
      @NotBlank @Size(max=200) String targetWorkloadId,
      @NotNull @Min(1) Long mappingRevision, @NotNull @Min(0) Long expectedLocalRevision,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}

  record CaptureForm(@NotBlank String receiptId,
      @NotBlank @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String idempotencyKey,
      @NotBlank String tenantId, @NotBlank String environmentId, @NotBlank String principalName) {}
}
