package org.uacrf.sampledata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Renders every committed sample fixture, keyed by its path relative to the sample-data module. */
final class SampleFixtures {
  static final Path DICTIONARY = Path.of("..", "design", "technical-approach", "data-dictionary");

  private SampleFixtures() {}

  static Map<String, String> render() throws IOException {
    DataDictionary dictionary = DataDictionary.load(DICTIONARY);
    Map<String, Object> multiCloud = ConnectionsSamples.multiCloudEstate();
    Map<String, String> files = new LinkedHashMap<>();
    files.put("connections/sample-local-estate.json", Json.pretty(ConnectionsSamples.localEstate()));
    files.put("connections/sample-multicloud-estate.json", Json.pretty(multiCloud));
    files.put("graph/sample-multicloud-graph.json", Json.pretty(GraphSample.build(dictionary, multiCloud)));
    return files;
  }

  static void write(Path root, Map<String, String> files) throws IOException {
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path target = root.resolve(file.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.getValue());
    }
  }
}
