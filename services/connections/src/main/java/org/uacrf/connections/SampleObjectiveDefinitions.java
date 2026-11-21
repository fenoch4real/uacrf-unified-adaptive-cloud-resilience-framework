package org.uacrf.connections;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class SampleObjectiveDefinitions {
  private final Fixture fixture;

  SampleObjectiveDefinitions(ObjectMapper mapper) throws IOException {
    try (var input = new ClassPathResource(
        "sample-data/objectives/sample-objective-definitions.json").getInputStream()) {
      fixture = mapper.readValue(input, Fixture.class);
    }
    if (fixture == null || fixture.label() == null || !fixture.label().startsWith("SAMPLE DATA")
        || fixture.sourceSystem() == null || !fixture.sourceSystem().startsWith("sample:")
        || fixture.definitions() == null || fixture.definitions().isEmpty()
        || fixture.definitions().stream().anyMatch(definition -> definition == null
            || definition.id() == null || !definition.id().startsWith("sample:")
            || definition.sourceVersion() == null || !definition.sourceVersion().startsWith("sample-")
            || definition.payload() == null || definition.payload().isBlank())
        || fixture.definitions().stream().map(Definition::id).distinct().count() != fixture.definitions().size()) {
      throw new IllegalStateException("The bundled sample objective definitions are invalid.");
    }
  }

  Definition find(String id) {
    return fixture.definitions().stream().filter(definition -> definition.id().equals(id))
        .findFirst().orElseThrow(() -> new ConnectionsException(
            org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "The sample definition is absent."));
  }

  String sourceSystem() {
    return fixture.sourceSystem();
  }

  record Fixture(String label, @JsonProperty("source_system") String sourceSystem,
      List<Definition> definitions) {}

  record Definition(String id, @JsonProperty("source_version") String sourceVersion, String payload) {}
}
