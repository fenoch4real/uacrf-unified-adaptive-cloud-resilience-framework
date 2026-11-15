package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class InternalJsonClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final int MAX_RESPONSE_BYTES = 512 * 1024;
  private final long startedNanos = System.nanoTime();
  private final long budgetNanos;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper json = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  InternalJsonClient() {
    budgetNanos = 0;
  }

  InternalJsonClient(Duration budget) {
    if (budget == null || budget.isZero() || budget.isNegative() || budget.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("Invalid internal request budget.");
    }
    budgetNanos = budget.toNanos();
  }

  Map<?, ?> post(URI uri, String token, Map<String, ?> request) throws IOException, InterruptedException {
    HttpRequest httpRequest = HttpRequest.newBuilder(uri).timeout(requestTimeout())
        .header("Authorization", "Bearer " + requireToken(token))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build();
    return send(httpRequest);
  }

  Map<?, ?> get(URI uri, String token) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri).timeout(requestTimeout())
        .header("Authorization", "Bearer " + requireToken(token)).GET().build());
  }

  Map<?, ?> postForm(URI uri, Map<String, String> fields) throws IOException, InterruptedException {
    String body = fields.entrySet().stream()
        .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
    return send(HttpRequest.newBuilder(uri).timeout(requestTimeout())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
  }

  static String requireToken(String token) throws IOException {
    if (token == null || token.length() > 32768 || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
      throw new IOException("A valid bearer token was not supplied.");
    }
    return token;
  }

  static void requireId(String value) {
    if (value == null || value.length() > 200 || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
      throw new IllegalArgumentException("Invalid service request scope.");
    }
  }

  static URI validateBase(URI uri, boolean allowLoopbackHttp) {
    validateEndpoint(uri, allowLoopbackHttp);
    if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
      throw new IllegalArgumentException("Invalid internal service base URI.");
    }
    return uri;
  }

  static URI validateEndpoint(URI uri, boolean allowLoopbackHttp) {
    if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
        || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("Invalid internal service URI.");
    }
    boolean loopback = "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost());
    if (!"https".equalsIgnoreCase(uri.getScheme())
        && !(allowLoopbackHttp && loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalArgumentException("Internal service requires HTTPS or explicitly allowed loopback HTTP.");
    }
    return uri;
  }

  private Map<?, ?> send(HttpRequest request) throws IOException, InterruptedException {
    Duration timeout = requestTimeout();
    var pending = http.sendAsync(request, info -> limitedBodySubscriber());
    HttpResponse<byte[]> response;
    try {
      response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      pending.cancel(true);
      throw new IOException("Internal service request timed out.", failure);
    } catch (InterruptedException failure) {
      pending.cancel(true);
      throw failure;
    } catch (ExecutionException failure) {
      throw new IOException("Internal service request failed.", failure.getCause());
    }
    if (response.statusCode() != 200) {
      throw new IOException("Internal service request failed with HTTP " + response.statusCode() + ".");
    }
    try {
      Object value = json.readValue(response.body(), Object.class);
      if (!(value instanceof Map<?, ?> body)) {
        throw new IOException("Internal service response is not a JSON object.");
      }
      requestTimeout();
      return body;
    } catch (JacksonException failure) {
      throw new IOException("Internal service response contains invalid JSON.");
    }
  }

  private Duration requestTimeout() throws IOException {
    if (budgetNanos == 0) return TIMEOUT;
    long remaining = budgetNanos - (System.nanoTime() - startedNanos);
    if (remaining <= 0) {
      throw new IOException("Internal service request budget expired.");
    }
    return Duration.ofNanos(Math.min(TIMEOUT.toNanos(), remaining));
  }

  void requireBudget() throws IOException {
    requestTimeout();
  }

  static HttpResponse.BodySubscriber<byte[]> limitedBodySubscriber() {
    return new LimitedBodySubscriber();
  }

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int size;
    private boolean exceeded;

    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      delegate.onSubscribe(value);
    }
    @Override public void onNext(java.util.List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_RESPONSE_BYTES - size) {
          exceeded = true;
          subscription.cancel();
          delegate.onError(new IOException("Internal service response exceeded the size limit."));
          return;
        }
        size += buffer.remaining();
      }
      delegate.onNext(buffers);
    }
    @Override public void onError(Throwable error) { if (!exceeded) delegate.onError(error); }
    @Override public void onComplete() { if (!exceeded) delegate.onComplete(); }
  }
}
