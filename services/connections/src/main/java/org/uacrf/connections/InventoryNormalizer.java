package org.uacrf.connections;

import org.springframework.stereotype.Component;
import org.uacrf.connections.ConnectionsResponses.NormalizedInventoryObservation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class InventoryNormalizer {
  private final ObjectMapper objectMapper;

  InventoryNormalizer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  NormalizedInventoryObservation normalize(
      String sourceClass,
      String observationId,
      String sourceRef,
      String kind,
      String payload,
      String payloadDigest,
      String observedAt) {
    String provider =
        switch (kind) {
          case "aws.config.resources" -> "AWS";
          case "azure.resourcegraph.rows" -> "AZURE";
          case "gcp.assetinventory.assets" -> "GCP";
          default -> throw new IllegalStateException("Unsupported inventory observation kind.");
        };
    if (!sourceClass.equals("SAMPLE_LOCAL") && !sourceClass.equals(provider)) {
      throw new IllegalStateException("Inventory observation provider does not match its connection.");
    }

    try {
      JsonNode item = objectMapper.readTree(payload);
      if (item == null || !item.isObject()) {
        throw new IllegalStateException("Inventory observation payload must be an object.");
      }
      String nativeId = required(item, provider.equals("AWS") ? "resourceId" : provider.equals("AZURE") ? "id" : "name");
      String nativeType =
          required(item, provider.equals("AWS") ? "resourceType" : provider.equals("AZURE") ? "type" : "assetType");
      String location =
          provider.equals("GCP") ? null : optional(item, provider.equals("AWS") ? "awsRegion" : "location");
      return new NormalizedInventoryObservation(
          "inventory-observation.v1", sourceClass, provider, nativeId, nativeType, location,
          observationId, sourceRef, kind, payloadDigest, observedAt);
    } catch (JacksonException error) {
      throw new IllegalStateException("Inventory observation payload is not valid JSON.", error);
    }
  }

  private static String required(JsonNode item, String name) {
    String value = optional(item, name);
    if (value == null) {
      throw new IllegalStateException("Inventory observation is missing " + name + ".");
    }
    return value;
  }

  private static String optional(JsonNode item, String name) {
    JsonNode value = item.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString() || value.stringValue().isBlank()) {
      throw new IllegalStateException("Inventory observation field " + name + " must be a nonblank string.");
    }
    return value.stringValue();
  }
}
