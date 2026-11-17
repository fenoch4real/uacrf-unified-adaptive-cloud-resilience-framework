package org.uacrf.catalog;

import java.security.interfaces.RSAPrivateKey;

record OwnerReceiptSigningKey(String keyId, RSAPrivateKey privateKey) {}
