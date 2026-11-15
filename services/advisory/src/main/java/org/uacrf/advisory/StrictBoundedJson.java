package org.uacrf.advisory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class StrictBoundedJson {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  private StrictBoundedJson() {}

  static Map<?, ?> readObject(byte[] bytes, String description) {
    if (bytes == null || bytes.length == 0 || bytes.length > InputPacket.MAX_BYTES) {
      throw new IllegalArgumentException(description + " exceeds the supported bounds.");
    }
    String content;
    try {
      content = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException(description + " is not valid UTF-8.", failure);
    }
    Object value;
    try {
      value = JSON.readValue(content, Object.class);
    } catch (JacksonException failure) {
      throw new IllegalArgumentException(description + " is not valid JSON.");
    }
    if (!(value instanceof Map<?, ?> document)) {
      throw new IllegalArgumentException(description + " must be a JSON object.");
    }
    return document;
  }
}
