package org.uacrf.catalog;

import java.time.Clock;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.uacrf.security.DelegationKeyMaterial;

@Configuration
class OwnerReceiptSigningConfiguration {
  @Bean
  OwnerReceiptSigningKey ownerReceiptSigningKey(
      @Value("${uacrf.owner-receipt.public-key-base64}") String encodedPublicKey,
      @Value("${uacrf.owner-receipt.private-key-base64}") String encodedPrivateKey,
      @Value("${uacrf.owner-receipt.key-id}") String keyId) {
    DelegationKeyMaterial.validateKeyId(keyId);
    KeyPair keyPair = DelegationKeyMaterial.decodeKeyPair(encodedPublicKey, encodedPrivateKey);
    return new OwnerReceiptSigningKey(keyId, (RSAPrivateKey) keyPair.getPrivate());
  }

  @Bean
  Clock catalogClock() {
    return Clock.systemUTC();
  }
}
