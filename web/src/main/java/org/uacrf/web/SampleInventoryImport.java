package org.uacrf.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
class SampleInventoryImport {
  private static final Logger log = LoggerFactory.getLogger(SampleInventoryImport.class);
  private static final List<String> WRITE =
      List.of("connections.connection.write", "connections.observation.write");
  private final IdentityAccessClient identity;
  private final ConnectionsClient connections;
  private final SampleInventory fixture;

  SampleInventoryImport(IdentityAccessClient identity, ConnectionsClient connections, SampleInventory fixture) {
    this.identity = identity;
    this.connections = connections;
    this.fixture = fixture;
  }

  Result run(String accessToken, BrowserContext context, String principal, String owner) {
    String id = SampleInventory.connectionId(context, principal, owner);
    int completed = 0;
    int duplicates = 0;
    String stage = "registering the sample connection";
    try {
      connections.registerSample(delegate(accessToken, context), "sample-register-" + id,
          fixture.connection(id, owner));
      stage = "binding sample inventory sources";
      for (SampleInventory.Binding binding : fixture.bindings(id)) {
        connections.bindSample(delegate(accessToken, context), id, "sample-binding-" + binding.id(), binding);
      }
      for (SampleInventory.Observation observation : fixture.observations(id)) {
        stage = "submitting observation " + observation.id();
        ConnectionsClient.IngestResult result =
            connections.submitSample(delegate(accessToken, context), id, observation);
        if ("QUARANTINED".equals(result.status())) {
          throw new ImportFailure(HttpStatus.CONFLICT.value(), id, completed,
              "S03 quarantined " + observation.id() + " (" + result.reason()
                  + "). Inspect the quarantine before retrying.");
        }
        completed++;
        if ("DUPLICATE".equals(result.status())) {
          duplicates++;
        }
      }
      return new Result(id, completed, duplicates);
    } catch (ResourceAccessException error) {
      log.warn("Sample inventory import interrupted while {}; outcome may be unconfirmed.", stage, error);
      throw new ImportFailure(HttpStatus.SERVICE_UNAVAILABLE.value(), id, completed,
          "A service was unavailable while " + stage + "; the last operation may have completed.");
    } catch (RestClientResponseException error) {
      log.warn("Sample inventory import rejected while {} (HTTP {}).", stage, error.getStatusCode().value());
      throw new ImportFailure(error.getStatusCode().value(), id, completed,
          "Import stopped while " + stage + " (HTTP " + error.getStatusCode().value() + ").");
    } catch (IllegalStateException error) {
      log.error("Sample inventory import received an invalid response while {}.", stage, error);
      throw new ImportFailure(HttpStatus.BAD_GATEWAY.value(), id, completed,
          "A service returned an invalid response while " + stage + "; the last operation may have completed.");
    }
  }

  private String delegate(String token, BrowserContext context) {
    return identity.createDelegation(token, "S03", null, WRITE,
        context.tenantId(), context.environmentId()).accessToken();
  }

  record Result(String connectionId, int observations, int duplicates) {}

  static class ImportFailure extends RuntimeException {
    private final int status;
    private final String connectionId;
    private final int completed;

    ImportFailure(int status, String connectionId, int completed, String message) {
      super(message);
      this.status = status;
      this.connectionId = connectionId;
      this.completed = completed;
    }

    int status() { return status; }
    String connectionId() { return connectionId; }
    int completed() { return completed; }
  }
}
