package org.uacrf.decisiongovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PolicyTrustSetTests {
  private static final KeyPair KEY = generate(2048);

  @Test
  void digestIsDeterministicAcrossKeyOrderAndWhitespaceAndScopeIsEnforced() {
    PolicyTrustSet first = new PolicyTrustSet(set(3, entry("a", KEY), entry("b", generate(2048))));
    String json = set(3, entry("a", KEY));
    assertThat(new PolicyTrustSet(json).digest())
        .isEqualTo(new PolicyTrustSet(json.replace(",", " , ")).digest());
    assertThat(first.revision()).isEqualTo(3);
    String keyDigest = first.key("a").keyDigest();
    assertThat(first.stillTrusts("a", keyDigest, "t", "e", "s", "r")).isTrue();
    assertThat(first.stillTrusts("a", keyDigest, "t", "e", "s", "other")).isFalse();
    assertThat(first.stillTrusts("a", "0".repeat(64), "t", "e", "s", "r")).isFalse();
    assertThat(first.stillTrusts("removed", keyDigest, "t", "e", "s", "r")).isFalse();
    PolicyTrustSet rotated = new PolicyTrustSet(set(4, entry("b", generate(2048))));
    assertThat(rotated.stillTrusts("a", keyDigest, "t", "e", "s", "r")).isFalse();
    assertThat(rotated.digest()).isNotEqualTo(first.digest());
  }

  @Test
  void rejectsMalformedAmbiguousOrWeakConfiguration() {
    assertThat(new PolicyTrustSet("").configured()).isFalse();
    assertThatThrownBy(() -> new PolicyTrustSet(set(0, entry("a", KEY)))).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PolicyTrustSet(set(1, entry("a", KEY), entry("a", generate(2048)))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PolicyTrustSet(set(1, entry("a", KEY), entry("b", KEY))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PolicyTrustSet(set(1, entry("a", generate(1024)))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PolicyTrustSet(set(1, entry("a", KEY)).replace("}]}", "},\"x\":1]}")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PolicyTrustSet("{\"revision\":1,\"revision\":2,\"keys\":[]}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PolicyTrustSet("{\"revision\":1,\"keys\":[],\"extra\":true}"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static String set(long revision, String... entries) {
    return "{\"revision\":" + revision + ",\"keys\":[" + String.join(",", entries) + "]}";
  }

  private static String entry(String kid, KeyPair key) {
    return "{\"kid\":\"" + kid + "\",\"algorithm\":\"RS256\",\"public_key_base64\":\""
        + Base64.getEncoder().encodeToString(key.getPublic().getEncoded())
        + "\",\"tenant_id\":\"t\",\"environment_id\":\"e\",\"service_id\":\"s\",\"reviewer_id\":\"r\"}";
  }

  private static KeyPair generate(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
