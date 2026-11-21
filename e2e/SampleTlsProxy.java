import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

// Test-only TLS termination; the disposable owner services remain on the Compose network.
class SampleTlsProxy {
  public static void main(String[] args) throws Exception {
    char[] password = "isolated-test-only".toCharArray();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(Path.of("/fixture/server.p12"))) {
      store.load(input, password);
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password);
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(keys.getKeyManagers(), null, null);
    HttpsServer server = HttpsServer.create(new InetSocketAddress(8443), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(tls));
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getRawPath();
      String base;
      if ("POST".equals(exchange.getRequestMethod())
          && "/internal/v1/delegations:exchange".equals(path)) {
        base = "http://identity:8082";
      } else if ("GET".equals(exchange.getRequestMethod())
          && path.matches("/v1/connections/[A-Za-z0-9_.:%-]+/sample-objective-definition")) {
        base = "http://connections:8085";
      } else {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      try {
        byte[] body = exchange.getRequestBody().readNBytes(65_537);
        if (body.length > 65_536) {
          exchange.sendResponseHeaders(413, -1);
          return;
        }
        var request = HttpRequest.newBuilder(URI.create(base + exchange.getRequestURI()))
            .timeout(Duration.ofSeconds(3))
            .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : new String[] {"Authorization", "Content-Type"}) {
          String value = exchange.getRequestHeaders().getFirst(name);
          if (value != null) request.header(name, value);
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        response.headers().firstValue("Content-Type")
            .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (java.io.IOException error) {
        System.err.println("Synthetic test proxy upstream I/O failed: " + error.getClass().getSimpleName());
        exchange.sendResponseHeaders(502, -1);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        exchange.sendResponseHeaders(502, -1);
      } finally {
        exchange.close();
      }
    });
    server.start();
    System.out.println("Synthetic test TLS proxy ready on 8443.");
  }
}
