package org.uacrf.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Cursors {
  final byte[] key;

  Cursors(byte[] key) {
    this.key = key.clone();
  }

  String mac(String raw, String binding) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal((raw + '\0' + binding).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String mint(String after, String authority, String binding) {
    String raw =
        (Instant.now().getEpochSecond() + 300)
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(after.getBytes(StandardCharsets.UTF_8))
            + "."
            + authority;
    return raw + "." + mac(raw, binding);
  }

  ObjectNode read(String value, String binding) {
    try {
      var p = value.split("\\.", -1);
      if (value.length() > 500 || p.length != 4) throw new IllegalArgumentException();
      String raw = String.join(".", p[0], p[1], p[2]);
      if (!MessageDigest.isEqual(
              mac(raw, binding).getBytes(StandardCharsets.UTF_8),
              p[3].getBytes(StandardCharsets.UTF_8))
          || Long.parseLong(p[0]) < Instant.now().getEpochSecond())
        throw new IllegalArgumentException();
      String after = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
      if (!after.matches("[A-Za-z0-9_.:-]{1,200}") || !p[2].matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException();
      return Json.M.createObjectNode().put("after", after).put("authority_digest", p[2]);
    } catch (Exception e) {
      throw new Problem(409, "Invalid expired or mismatched continuation cursor");
    }
  }
}
