package org.uacrf.security;

import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class DelegationJwtConfiguration {
  @Bean
  RSAPublicKey delegationPublicKey(
      @Value("${uacrf.delegation.public-key-base64}") String encodedPublicKey) {
    return DelegationKeyMaterial.decodePublicKey(encodedPublicKey);
  }

  @Bean
  JwtDecoder delegationJwtDecoder(
      RSAPublicKey delegationPublicKey,
      @Value("${uacrf.delegation.issuer:https://uacrf.local/s01}") String issuer,
      @Value("${uacrf.delegation.audience}") String audience,
      @Value("${uacrf.delegation.key-id}") String keyId) {
    DelegationKeyMaterial.validateKeyId(keyId);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(delegationPublicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            JwtValidators.createDefaultWithIssuer(issuer),
            new DelegationTokenValidator(issuer, audience, keyId)));
    return decoder;
  }
}
