package org.uacrf.advisory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class AdvisoryInputPacket {
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private AdvisoryInputPacket() {}

  // Integrity only: referenced artifacts and receipts still need owner authorization and current eligibility.
  static InputPacket create(SourceReceiptConsistency.Expected expected, OwnerReceiptClient.Receipts receipts,
                            String caseMaterial, PromptConfiguration.Prompt prompt) {
    if (receipts == null) {
      throw new IllegalArgumentException("Verified owner receipts are required.");
    }
    SourceReceiptConsistency.check(expected, receipts.sourceCase(), receipts.retrieval());
    if (caseMaterial == null || !UnicodeText.isValid(caseMaterial)
        || !receipts.sourceCase().caseDigest().equals(Sha256.hex(caseMaterial.getBytes(StandardCharsets.UTF_8)))
        || receipts.excerpts().size() != receipts.retrieval().results().size()
        || prompt == null || prompt.declaredInstructionsDigest() == null
        || !prompt.declaredInstructionsDigest().matches("[a-f0-9]{64}")
        || prompt.text() == null || prompt.text().isBlank() || !UnicodeText.isValid(prompt.text())) {
      throw new IllegalArgumentException("Verified case material and prompt are required.");
    }
    var sources = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < receipts.retrieval().results().size(); i++) {
      var result = receipts.retrieval().results().get(i);
      String excerpt = receipts.excerpts().get(i);
      if (excerpt == null || !UnicodeText.isValid(excerpt)
          || !result.excerptDigest().equals(Sha256.hex(excerpt.getBytes(StandardCharsets.UTF_8)))) {
        throw new IllegalArgumentException("Verified retrieval excerpt does not match its receipt.");
      }
      var source = new LinkedHashMap<String, Object>();
      source.put("item_id", result.itemId());
      source.put("version", result.version());
      source.put("chunk_index", result.chunkIndex());
      source.put("start_offset", result.startOffset());
      source.put("end_offset", result.endOffset());
      source.put("source_digest", result.sourceDigest());
      source.put("excerpt_digest", result.excerptDigest());
      source.put("classification", result.classification().name());
      source.put("allowed_purposes", result.allowedPurposes());
      source.put("excerpt", excerpt);
      sources.add(source);
    }
    var packet = new LinkedHashMap<String, Object>();
    packet.put("protocol", "UACRF_ADVISORY_INPUT_V1");
    packet.put("case_id", expected.caseId());
    packet.put("case_digest", receipts.sourceCase().caseDigest());
    packet.put("case_material", caseMaterial);
    packet.put("retrieval_id", expected.retrievalId());
    packet.put("manifest_digest", expected.manifestDigest());
    packet.put("purpose", expected.purpose());
    packet.put("instructions_digest", prompt.declaredInstructionsDigest());
    packet.put("instructions", prompt.text());
    packet.put("sources", sources);
    return new InputPacket(JSON.writeValueAsBytes(packet));
  }
}
