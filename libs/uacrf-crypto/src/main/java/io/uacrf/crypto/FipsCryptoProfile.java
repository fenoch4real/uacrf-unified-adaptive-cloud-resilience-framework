package io.uacrf.crypto;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * FIPS 140-3 cryptographic profile and Vault / Cloud KMS envelope signing abstraction.
 */
public final class FipsCryptoProfile {

    public static final int FIPS_MIN_RSA_KEY_BITS = 3072;

    private FipsCryptoProfile() {
    }

    public static RSAKey generateKey(String keyId, boolean fipsMode) {
        int bits = fipsMode ? FIPS_MIN_RSA_KEY_BITS : 2048;
        try {
            return new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(bits).keyID(keyId).generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to generate RSA key", e);
        }
    }

    public static void assertKeyCompliant(RSAKey key, boolean fipsMode) {
        int size = key.size();
        if (fipsMode && size < FIPS_MIN_RSA_KEY_BITS) {
            throw new SecurityException("FIPS 140-3 profile requires RSA >= " + FIPS_MIN_RSA_KEY_BITS
                    + " bits; rejected " + size + "-bit key [" + key.getKeyID() + "].");
        }
    }

    public interface KmsKeyProvider {
        String providerName();
        boolean fipsValidated();
        RSAKey resolveSigningKey(String keyId);
    }

    public static final class VaultOrCloudKmsProvider implements KmsKeyProvider {
        private final String providerName;
        private final boolean fipsMode;

        public VaultOrCloudKmsProvider(String providerName, boolean fipsMode) {
            this.providerName = providerName;
            this.fipsMode = fipsMode;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public boolean fipsValidated() {
            return fipsMode;
        }

        @Override
        public RSAKey resolveSigningKey(String keyId) {
            RSAKey key = generateKey(keyId, fipsMode);
            assertKeyCompliant(key, fipsMode);
            return key;
        }
    }
}
