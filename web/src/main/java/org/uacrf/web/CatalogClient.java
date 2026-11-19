package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
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

  AgentPage listAgents(String delegationToken, String cursor) {
    AgentPage response =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri = uriBuilder.path("/v1/agent-workloads").queryParam("pageSize", 25);
                  if (cursor != null && !cursor.isBlank()) {
                    uri.queryParam("cursor", cursor);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .retrieve()
            .body(AgentPage.class);
    if (response == null
        || response.items() == null
        || response.items().stream()
            .anyMatch(
                agent ->
                    agent == null
                        || agent.id() == null
                        || agent.id().isBlank()
                        || agent.name() == null
                        || agent.name().isBlank()
                        || agent.kind() == null
                        || agent.kind().isBlank()
                        || agent.ownerId() == null
                        || agent.ownerId().isBlank()
                        || agent.runtimePrincipalId() == null
                        || agent.runtimePrincipalId().isBlank()
                        || agent.purpose() == null
                        || agent.purpose().isBlank()
                        || agent.revision() < 1)) {
      throw new IllegalStateException("S02 returned an invalid agent-workload page.");
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

  Optional<ServiceRecord> findService(String delegationToken, String serviceId) {
    ServicePage response =
        restClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/v1/services")
                        .queryParam("pageSize", 25)
                        .queryParam("includeRetired", true)
                        .build())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .retrieve()
            .body(ServicePage.class);
    if (response == null
        || response.items() == null
        || response.items().stream()
            .anyMatch(
                service ->
                    service == null
                        || service.id() == null
                        || service.id().isBlank()
                        || service.name() == null
                        || service.name().isBlank()
                        || service.ownerId() == null
                        || service.revision() < 1)) {
      throw new IllegalStateException("S02 returned an incomplete service page.");
    }
    return response.items().stream()
        .filter(service -> serviceId.equals(service.id()))
        .findFirst();
  }

  OwnershipTransferReceipt transferOwnership(
      String delegationToken, String idempotencyKey, OwnershipTransfer transfer) {
    OwnershipTransferReceipt response =
        restClient
            .post()
            .uri("/v1/ownership:transfer")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + delegationToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(transfer)
            .retrieve()
            .body(OwnershipTransferReceipt.class);
    if (response == null
        || !transfer.objectId().equals(response.id())
        || response.revision() != transfer.expectedRevision() + 1
        || !List.of("UPDATED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("S02 returned an invalid ownership-transfer receipt.");
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

  record OwnershipTransfer(
      @JsonProperty("object_kind") String objectKind,
      @JsonProperty("object_id") String objectId,
      @JsonProperty("owner_id") String ownerId,
      @JsonProperty("expected_revision") long expectedRevision,
      String reason,
      @JsonProperty("expected_anchor_revision") Object expectedAnchorRevision) {}

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

  record OwnershipTransferReceipt(
      String id,
      long revision,
      String result,
      @JsonProperty("runtime_effect") String runtimeEffect) {}

  record JourneyPage(
      @JsonProperty("items") List<Journey> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record AgentPage(
      @JsonProperty("items") List<AgentWorkload> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record ServicePage(
      @JsonProperty("items") List<ServiceRecord> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record AgentWorkload(
      String id,
      @JsonProperty("service_id") String serviceId,
      String name,
      String kind,
      @JsonProperty("owner_id") String ownerId,
      @JsonProperty("runtime_principal_id") String runtimePrincipalId,
      String purpose,
      long revision) {}

  record ServiceRecord(
      String id,
      String name,
      @JsonProperty("owner_id") String ownerId,
      String lifecycle,
      int criticality,
      long revision) {}

  record OwnershipTarget(String objectKind, String id, String name, String ownerId, long revision) {}

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
