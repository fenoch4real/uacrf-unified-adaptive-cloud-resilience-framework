package org.uacrf.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class DelegationKeyMaterial {
  private static final int MINIMUM_RSA_KEY_BITS = 2048;
  private static final byte[] KEY_PAIR_CHALLENGE =
      "uacrf-delegation-key-pair-check".getBytes(StandardCharsets.UTF_8);

  private DelegationKeyMaterial() {}

  public static RSAPublicKey decodePublicKey(String encodedKey) {
    try {
      var key =
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)));
      if (!(key instanceof RSAPublicKey rsaKey)) {
        throw new IllegalStateException("Delegation public key must be an RSA key");
      }
      requireMinimumKeySize(rsaKey.getModulus().bitLength());
      return rsaKey;
    } catch (IllegalArgumentException | GeneralSecurityException error) {
      throw new IllegalStateException(
          "Delegation public key must be Base64-encoded X.509 RSA key material", error);
    }
  }

  public static KeyPair decodeKeyPair(String encodedPublicKey, String encodedPrivateKey) {
    RSAPublicKey publicKey = decodePublicKey(encodedPublicKey);
    RSAPrivateKey privateKey;
    try {
      var key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedPrivateKey)));
      if (!(key instanceof RSAPrivateKey rsaKey)) {
        throw new IllegalStateException("Delegation private key must be an RSA key");
      }
      requireMinimumKeySize(rsaKey.getModulus().bitLength());
      privateKey = rsaKey;
    } catch (IllegalArgumentException | GeneralSecurityException error) {
      throw new IllegalStateException(
          "Delegation private key must be Base64-encoded PKCS#8 RSA key material", error);
    }

    KeyPair keyPair = new KeyPair(publicKey, privateKey);
    validateKeyPair(keyPair);
    return keyPair;
  }

  public static void validateKeyPair(KeyPair keyPair) {
    if (!(keyPair.getPublic() instanceof RSAPublicKey publicKey)
        || !(keyPair.getPrivate() instanceof RSAPrivateKey privateKey)) {
      throw new IllegalStateException("Delegation signing keys must use RSA");
    }
    requireMinimumKeySize(publicKey.getModulus().bitLength());
    requireMinimumKeySize(privateKey.getModulus().bitLength());
    verifyKeyPair(publicKey, privateKey);
  }

  public static void validateKeyId(String keyId) {
    if (keyId == null || !keyId.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
      throw new IllegalArgumentException("Delegation signing key id is invalid");
    }
  }

  private static void requireMinimumKeySize(int keySizeBits) {
    if (keySizeBits < MINIMUM_RSA_KEY_BITS) {
      throw new IllegalStateException(
          "Delegation RSA keys must contain at least " + MINIMUM_RSA_KEY_BITS + " bits");
    }
  }

  private static void verifyKeyPair(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(KEY_PAIR_CHALLENGE);
      byte[] signature = signer.sign();

      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(KEY_PAIR_CHALLENGE);
      if (!verifier.verify(signature)) {
        throw new IllegalStateException("Delegation public and private keys do not match");
      }
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Unable to validate delegation RSA key pair", error);
    }
  }
}
