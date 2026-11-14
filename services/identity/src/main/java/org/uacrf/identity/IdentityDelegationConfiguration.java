package org.uacrf.identity;

import java.time.Clock;
import java.security.KeyPair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationKeyMaterial;
import org.uacrf.security.DelegationTokenIssuer;

@Configuration
class IdentityDelegationConfiguration {
  @Bean
  KeyPair identityDelegationKeyPair(
      @Value("${uacrf.delegation.public-key-base64}") String encodedPublicKey,
      @Value("${uacrf.delegation.private-key-base64}") String encodedPrivateKey) {
    return DelegationKeyMaterial.decodeKeyPair(encodedPublicKey, encodedPrivateKey);
  }

  @Bean
  JwtEncoder delegationJwtEncoder(
      KeyPair identityDelegationKeyPair,
      @Value("${uacrf.delegation.key-id}") String keyId) {
    return DelegationJwtEncoderFactory.create(identityDelegationKeyPair, keyId);
  }

  @Bean
  DelegationTokenIssuer identityDelegationTokenIssuer(
      @Qualifier("delegationJwtEncoder") JwtEncoder delegationJwtEncoder,
      @Value("${uacrf.delegation.key-id}") String keyId,
      @Value("${uacrf.delegation.issuer}") String issuer) {
    return new DelegationTokenIssuer(delegationJwtEncoder, Clock.systemUTC(), issuer, keyId);
  }
}
