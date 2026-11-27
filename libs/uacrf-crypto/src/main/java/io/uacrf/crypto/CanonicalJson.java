package io.uacrf.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic canonical JSON serialization and SHA-256 digest helper (RFC 8785 key-sorted JSON).
 * Ensures identical payloads produce identical hashes regardless of input key ordering.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private CanonicalJson() {
    }

    public static byte[] canonicalizeBytes(Object value) {
        try {
            JsonNode tree = value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
            JsonNode sorted = sortRecursively(tree);
            return MAPPER.writeValueAsString(sorted).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize payload", e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String digestObject(Object value) {
        return sha256Hex(canonicalizeBytes(value));
    }

    private static JsonNode sortRecursively(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(sortRecursively(child));
            }
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String key : keys) {
                out.set(key, sortRecursively(node.get(key)));
            }
            return out;
        }
        return node;
    }
}
