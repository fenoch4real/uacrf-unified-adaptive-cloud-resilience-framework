package io.uacrf.ux;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed HTTP client calling EDGE01 on behalf of UX01 views with explicit tenant, environment, and actor context.
 */
@Component
public class UacrfEdgeClient {

    private final RestClient client;

    public UacrfEdgeClient(@Value("${uacrf.ux.edge-base-url}") String edgeBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(8000);
        this.client = RestClient.builder().baseUrl(edgeBaseUrl).requestFactory(factory).build();
    }

    public <T> List<T> getList(String path, Class<T> elementType) {
        try {
            List<Map<String, Object>> raw = client.get().uri(path)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (raw == null) {
                return List.of();
            }
            // Keep generic Map views when elementType is Map.class
            @SuppressWarnings("unchecked")
            List<T> cast = (List<T>) raw;
            return cast;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getObject(String path) {
        return client.get().uri(path)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> getObjectOrEmpty(String path) {
        try {
            Map<String, Object> obj = getObject(path);
            return obj != null ? obj : Collections.emptyMap();
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    public Map<String, Object> postJson(String path, UUID actorId, String idempotencyKey, Object payload) {
        var spec = client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-UACRF-Actor-Id", actorId.toString());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.body(payload).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> putJson(String path, UUID actorId, Object payload) {
        return client.put().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-UACRF-Actor-Id", actorId.toString())
                .body(payload)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
