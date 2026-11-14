package org.uacrf.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class DelegationJwtEncoderFactory {
  private DelegationJwtEncoderFactory() {}

  public static JwtEncoder create(KeyPair keyPair, String keyId) {
    DelegationKeyMaterial.validateKeyId(keyId);
    DelegationKeyMaterial.validateKeyPair(keyPair);
    if (!(keyPair.getPublic() instanceof RSAPublicKey publicKey)
        || !(keyPair.getPrivate() instanceof RSAPrivateKey privateKey)) {
      throw new IllegalArgumentException("Delegation signing keys must use RSA");
    }
    RSAKey jwk =
        new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyID(keyId)
            .build();
    return new NimbusJwtEncoder(
        new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
  }
}
