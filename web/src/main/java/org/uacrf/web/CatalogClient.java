package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class CatalogClient {
  private final RestClient restClient;

  CatalogClient(@Qualifier("catalogRestClient") RestClient catalogRestClient) {
    this.restClient = catalogRestClient;
  }

  JourneyPage listJourneys(String delegationToken, String cursor) {
    JourneyPage response =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri = uriBuilder.path("/v1/customer-journeys").queryParam("pageSize", 25);
                  if (cursor != null && !cursor.isBlank()) {
                    uri.queryParam("cursor", cursor);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .retrieve()
            .body(JourneyPage.class);
    if (response == null || response.items() == null) {
      throw new IllegalStateException("S02 returned an empty customer-journey page.");
    }
    return response;
  }

  JourneyDetails getJourney(String delegationToken, String journeyId) {
    JourneyDetails response =
        restClient
            .get()
            .uri("/v1/customer-journeys/{id}", journeyId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .retrieve()
            .body(JourneyDetails.class);
    if (response == null
        || !journeyId.equals(response.id())
        || response.ownerId() == null
        || response.steps() == null
        || response.capabilities() == null) {
      throw new IllegalStateException("S02 returned an incomplete customer journey.");
    }
    return response;
  }

  MutationReceipt registerJourney(
      String delegationToken,
      String idempotencyKey,
      JourneyRegistration registration) {
    MutationReceipt response =
        restClient
            .post()
            .uri("/v1/customer-journeys")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(registration)
            .retrieve()
            .body(MutationReceipt.class);
    if (response == null
        || !registration.id().equals(response.id())
        || response.revision() < 1
        || !"CUSTOMER_JOURNEY".equals(response.recordKind())
        || !List.of("CREATED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("S02 returned an invalid journey registration receipt.");
    }
    return response;
  }

  MutationReceipt registerService(
      String delegationToken,
      String idempotencyKey,
      ServiceRegistration registration) {
    MutationReceipt response =
        restClient
            .post()
            .uri("/v1/services")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(registration)
            .retrieve()
            .body(MutationReceipt.class);
    if (response == null
        || !registration.id().equals(response.id())
        || response.revision() < 1
        || !"SERVICE".equals(response.recordKind())
        || !List.of("CREATED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("S02 returned an invalid service registration receipt.");
    }
    return response;
  }

  MutationReceipt registerAgent(
      String delegationToken,
      String idempotencyKey,
      AgentRegistration registration) {
    MutationReceipt response =
        restClient
            .post()
            .uri("/v1/agent-workloads")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(registration)
            .retrieve()
            .body(MutationReceipt.class);
    if (response == null
        || !registration.id().equals(response.id())
        || response.revision() < 1
        || !"AGENT_WORKLOAD".equals(response.recordKind())
        || !List.of("CREATED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("S02 returned an invalid agent registration receipt.");
    }
    return response;
  }

  MutationReceipt registerStep(
      String delegationToken,
      String journeyId,
      String idempotencyKey,
      StepRegistration registration) {
    MutationReceipt response =
        restClient
            .post()
            .uri("/v1/customer-journeys/{id}/steps", journeyId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(registration)
            .retrieve()
            .body(MutationReceipt.class);
    if (response == null
        || !registration.id().equals(response.id())
        || response.revision() < 1
        || !List.of("CREATED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("S02 returned an invalid journey-step receipt.");
    }
    return response;
  }

  MutationReceipt linkCapability(
      String delegationToken,
      String journeyId,
      String idempotencyKey,
      CapabilityLink link) {
    MutationReceipt response =
        restClient
            .post()
            .uri("/v1/customer-journeys/{id}/capabilities", journeyId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(link)
            .retrieve()
            .body(MutationReceipt.class);
    if (response == null
        || !link.id().equals(response.id())
        || response.revision() < 1
        || !"JOURNEY_CAPABILITY".equals(response.recordKind())
        || !List.of("CREATED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("S02 returned an invalid journey-capability receipt.");
    }
    return response;
  }

  record StepRegistration(
      String id,
      int position,
      String name,
      @JsonProperty("intended_outcome") String intendedOutcome) {}

  record ServiceRegistration(
      String id,
      String name,
      @JsonProperty("owner_id") String ownerId,
      String reason,
      @JsonProperty("expected_revision") long expectedRevision,
      int criticality,
      String lifecycle) {}

  record AgentRegistration(
      String id,
      @JsonProperty("service_id") String serviceId,
      String name,
      String kind,
      @JsonProperty("owner_id") String ownerId,
      @JsonProperty("runtime_principal_id") String runtimePrincipalId,
      String purpose) {}

  record JourneyRegistration(
      String id,
      String name,
      @JsonProperty("owner_id") String ownerId,
      String purpose,
      String lifecycle) {}

  record CapabilityLink(
      String id,
      @JsonProperty("capability_kind") String capabilityKind,
      @JsonProperty("capability_id") String capabilityId,
      @JsonProperty("step_id") String stepId,
      String criticality) {}

  record MutationReceipt(
      String id,
      long revision,
      @JsonProperty("record_kind") String recordKind,
      String result,
      @JsonProperty("runtime_effect") String runtimeEffect) {}

  record JourneyPage(
      @JsonProperty("items") List<Journey> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record Journey(
      String id,
      String name,
      @JsonProperty("owner_id") String ownerId,
      String purpose,
      String lifecycle,
      long revision) {}

  record JourneyDetails(
      String id,
      String name,
      @JsonProperty("owner_id") String ownerId,
      String purpose,
      String lifecycle,
      long revision,
      List<Step> steps,
      List<Capability> capabilities) {}

  record Step(
      String id,
      int position,
      String name,
      @JsonProperty("intended_outcome") String intendedOutcome,
      long revision) {}

  record Capability(
      String id,
      @JsonProperty("capability_kind") String capabilityKind,
      @JsonProperty("capability_id") String capabilityId,
      @JsonProperty("step_id") String stepId,
      String criticality,
      long revision) {}
}
