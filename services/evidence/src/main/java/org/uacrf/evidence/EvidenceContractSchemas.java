package org.uacrf.evidence;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class EvidenceContractSchemas {
  private final ObjectMapper objectMapper;
  private final Schema observation;
  private final Schema policyWrite;
  private final Schema policyDecision;
  private final Schema waiverWrite;
  private final Schema waiverDecision;
  private final Schema waiverRevoke;
  private final Schema waiverAttestation;

  EvidenceContractSchemas(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    this.observation = registry.getSchema(SchemaLocation.of("classpath:contracts/evidence-observation.schema.json"));
    JsonNode openApi = readContract("contracts/openapi.proposed.json");
    JsonNode schemas = openApi.path("components").path("schemas");
    this.policyWrite = registry.getSchema(requireSchema(schemas, "PolicyWrite"));
    this.policyDecision = registry.getSchema(requireSchema(schemas, "PolicyDecision"));
    this.waiverWrite = registry.getSchema(requireSchema(schemas, "WaiverWrite"));
    this.waiverDecision = registry.getSchema(requireSchema(schemas, "WaiverDecision"));
    this.waiverRevoke = registry.getSchema(requireSchema(schemas, "WaiverRevoke"));
    this.waiverAttestation =
        registry.getSchema(SchemaLocation.of("classpath:contracts/waiver-attestation.schema.json"));
    this.observation.initializeValidators();
    this.policyWrite.initializeValidators();
    this.policyDecision.initializeValidators();
    this.waiverWrite.initializeValidators();
    this.waiverDecision.initializeValidators();
    this.waiverRevoke.initializeValidators();
    this.waiverAttestation.initializeValidators();
  }

  boolean validObservation(JsonNode value) {
    return valid(observation, value);
  }

  boolean validPolicyWrite(JsonNode value) {
    return valid(policyWrite, value);
  }

  boolean validPolicyDecision(JsonNode value) {
    return valid(policyDecision, value);
  }

  boolean validWaiverWrite(JsonNode value) {
    return valid(waiverWrite, value);
  }

  boolean validWaiverDecision(JsonNode value) {
    return valid(waiverDecision, value);
  }

  boolean validWaiverRevoke(JsonNode value) {
    return valid(waiverRevoke, value);
  }

  boolean validWaiverAttestation(JsonNode value) {
    return valid(waiverAttestation, value);
  }

  private boolean valid(Schema schema, JsonNode value) {
    try {
      return schema
          .validate(
              objectMapper.writeValueAsString(value),
              InputFormat.JSON,
              context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
          .isEmpty();
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize a contract value for validation.", error);
    }
  }

  private JsonNode readContract(String path) {
    try (InputStream input = new ClassPathResource(path).getInputStream()) {
      return objectMapper.readTree(input);
    } catch (IOException error) {
      throw new IllegalStateException("Could not load the evidence service contract: " + path, error);
    }
  }

  private static JsonNode requireSchema(JsonNode schemas, String name) {
    JsonNode schema = schemas.path(name);
    if (schema.isMissingNode() || schema.isNull()) {
      throw new IllegalStateException("The qualification contract is missing schema " + name + ".");
    }
    return schema;
  }
}
