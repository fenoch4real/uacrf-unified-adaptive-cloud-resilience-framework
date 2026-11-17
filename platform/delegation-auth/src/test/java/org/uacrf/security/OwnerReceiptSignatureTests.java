package org.uacrf.security;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

class OwnerReceiptSignatureTests {
  @Test
  void signsCanonicalJsonIndependentOfObjectWhitespaceAndPropertyOrder() throws Exception {
    KeyPair pair = rsaKeyPair();
    String signature =
        OwnerReceiptSignature.sign(
            "{\"key_id\":\"s02-k1\",\"eligible\":true,\"resource_id\":\"agent-a\"}",
            (RSAPrivateKey) pair.getPrivate());

    assertTrue(
        OwnerReceiptSignature.verify(
            "{ \"resource_id\" : \"agent-a\", \"eligible\":true,\"key_id\":\"s02-k1\" }",
            signature,
            (RSAPublicKey) pair.getPublic()));
    assertFalse(
        OwnerReceiptSignature.verify(
            "{\"resource_id\":\"agent-b\",\"eligible\":true,\"key_id\":\"s02-k1\"}",
            signature,
            (RSAPublicKey) pair.getPublic()));
  }

  @Test
  void rejectsMalformedSignaturesAndNonJsonPayloads() throws Exception {
    KeyPair pair = rsaKeyPair();
    assertFalse(
        OwnerReceiptSignature.verify(
            "{\"eligible\":true}", "not+base64", (RSAPublicKey) pair.getPublic()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OwnerReceiptSignature.sign(
                "not-json", (RSAPrivateKey) pair.getPrivate()));
  }

  private static KeyPair rsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
