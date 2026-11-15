package org.uacrf.advisory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class ScopedArtifactJson {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  private ScopedArtifactJson() {}

  static Map<?, ?> read(String tenantId, String environmentId, String expectedReference, String expectedDigest,
                        DigestBoundArtifact artifact, String protocol, String entriesField) {
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    if (artifact == null || !artifact.reference().equals(expectedReference)
        || !artifact.digest().equals(expectedDigest)) {
      throw new IllegalArgumentException("Configuration does not match the requested artifact.");
    }
    byte[] bytes = artifact.bytes();
    if (bytes.length == 0 || bytes.length > InputPacket.MAX_BYTES) {
      throw new IllegalArgumentException("Configuration exceeds the supported bounds.");
    }
    String content;
    try {
      content = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException("Configuration is not valid UTF-8.", failure);
    }
    Object value;
    try {
      value = JSON.readValue(content, Object.class);
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("Configuration is not valid JSON.");
    }
    if (!(value instanceof Map<?, ?> document)
        || !document.keySet().equals(Set.of("protocol", "tenant_id", "environment_id", entriesField))
        || !protocol.equals(document.get("protocol"))
        || !tenantId.equals(document.get("tenant_id")) || !environmentId.equals(document.get("environment_id"))) {
      throw new IllegalArgumentException("Configuration has an invalid shape or scope.");
    }
    return document;
  }
}
