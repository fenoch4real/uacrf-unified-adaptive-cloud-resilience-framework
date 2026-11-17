package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class CrossServiceProcess {
  private CrossServiceProcess() {}

  static URI start(String service, Map<String, String> environment, Path temp, List<Process> processes,
      HttpClient http) throws Exception {
    Path root = Path.of("../..").toAbsolutePath().normalize();
    String version = System.getProperty("uacrf.test.version");
    if (version == null || version.isBlank()) throw new IllegalStateException("Missing integration test version.");
    Path jar = root.resolve("services/" + service + "/target/" + service + "-service-" + version + ".jar");
    if (!Files.isRegularFile(jar)) throw new IllegalStateException("Missing built service JAR: " + jar);
    Path log = temp.resolve(service + ".log");
    ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-jar", jar.toString(), "--server.port=0", "--server.address=127.0.0.1");
    builder.environment().putAll(environment);
    Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    processes.add(process);
    Pattern pattern = Pattern.compile("Tomcat started on port ([0-9]+)");
    Instant deadline = Instant.now().plusSeconds(60);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) throw new IllegalStateException(service + " exited: " + Files.readString(log));
      var match = pattern.matcher(Files.readString(log));
      if (match.find()) {
        URI base = URI.create("http://127.0.0.1:" + match.group(1));
        try {
          var response = http.send(HttpRequest.newBuilder(base.resolve("/actuator/health"))
              .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
          if (response.statusCode() == 200) return base;
        } catch (IOException ignored) {
          // The server may not yet be accepting connections.
        }
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException(service + " did not become ready: " + Files.readString(log));
  }
}
