package org.uacrf.advisory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class KnowledgeSourceVerifier {
  private static final int MAX_SOURCE_BYTES = 256 * 1024;
  private static final int CHUNK_CODE_POINTS = 1000;

  private KnowledgeSourceVerifier() {}

  // Integrity only: caller-supplied bytes and receipts are not proof of authorization or current eligibility.
  static void verifyAll(SourceReceiptConsistency.Expected expected, OwnerReceiptClient.Receipts receipts,
                        Map<String, DigestBoundArtifact> sources) {
    if (receipts == null || sources == null) {
      throw new IllegalArgumentException("Knowledge receipts and sources are required.");
    }
    KnowledgeManifestVerifier.verify(expected, receipts.sourceCase(), receipts.retrieval());
    var results = receipts.retrieval().results();
    if (receipts.excerpts().size() != results.size()) {
      throw new IllegalArgumentException("Every receipt result requires a corresponding excerpt.");
    }
    Set<String> required = new HashSet<>();
    for (int i = 0; i < results.size(); i++) {
      var result = results.get(i);
      required.add(result.sourceDigest());
      verify(result, receipts.excerpts().get(i), sources.get(result.sourceDigest()));
    }
    if (!required.equals(sources.keySet())) {
      throw new IllegalArgumentException("Knowledge sources must match the receipt exactly.");
    }
  }

  // Integrity only: the artifact and receipt still require owner authorization and current eligibility.
  static void verify(SourceReceiptConsistency.ReceiptResult result, String excerpt, DigestBoundArtifact source) {
    if (result == null || excerpt == null || source == null
        || !source.digest().equals(result.sourceDigest())) {
      throw new IllegalArgumentException("Knowledge source does not match the receipt.");
    }
    byte[] bytes = source.bytes();
    if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) {
      throw new IllegalArgumentException("Knowledge source exceeds the supported bounds.");
    }
    String content;
    try {
      content = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException("Knowledge source is not valid UTF-8.", failure);
    }
    int length = content.codePointCount(0, content.length());
    long expectedStart = (long) result.chunkIndex() * CHUNK_CODE_POINTS;
    if (result.chunkIndex() < 0 || expectedStart >= length
        || result.startOffset() != expectedStart
        || result.endOffset() != Math.min(expectedStart + CHUNK_CODE_POINTS, length)) {
      throw new IllegalArgumentException("Knowledge source locator does not match the receipt.");
    }
    int start = content.offsetByCodePoints(0, result.startOffset());
    int end = content.offsetByCodePoints(0, result.endOffset());
    if (!content.substring(start, end).equals(excerpt)
        || !Sha256.hex(excerpt.getBytes(StandardCharsets.UTF_8)).equals(result.excerptDigest())) {
      throw new IllegalArgumentException("Knowledge excerpt does not match the source and receipt.");
    }
  }
}
