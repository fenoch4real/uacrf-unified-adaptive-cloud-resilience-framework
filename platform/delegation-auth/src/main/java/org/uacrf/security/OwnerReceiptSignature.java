package org.uacrf.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.erdtman.jcs.JsonCanonicalizer;

/** RFC 8785 canonical JSON signatures for owner-receipt protocol v1. */
public final class OwnerReceiptSignature {
  private OwnerReceiptSignature() {}

  public static String sign(String json, RSAPrivateKey privateKey) {
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(canonicalBytes(json));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Unable to sign owner receipt.", error);
    }
  }

  public static boolean verify(String json, String encodedSignature, RSAPublicKey publicKey) {
    if (encodedSignature == null || !encodedSignature.matches("^[A-Za-z0-9_-]+$")) {
      return false;
    }
    try {
      byte[] signatureBytes = Base64.getUrlDecoder().decode(encodedSignature);
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(canonicalBytes(json));
      return verifier.verify(signatureBytes);
    } catch (IllegalArgumentException error) {
      return false;
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Unable to verify owner receipt.", error);
    }
  }

  public static String canonicalize(String json) {
    try {
      return new JsonCanonicalizer(json).getEncodedString();
    } catch (Exception error) {
      throw new IllegalArgumentException("Owner receipt is not valid canonicalizable JSON.", error);
    }
  }

  private static byte[] canonicalBytes(String json) {
    return canonicalize(json).getBytes(StandardCharsets.UTF_8);
  }
}
