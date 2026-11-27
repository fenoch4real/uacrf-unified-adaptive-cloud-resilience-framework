package io.uacrf.crypto;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalJsonAndSignerTest {

    @Test
    void canonicalDigestIsOrderIndependent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 2);
        a.put("a", Map.of("z", true, "x", "hello"));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("x", "hello", "z", true));
        b.put("b", 2);

        assertEquals(CanonicalJson.digestObject(a), CanonicalJson.digestObject(b));
    }

    @Test
    void verifiesAudienceAndRejectsExpiredOrWrongAudience() {
        Instant fixedNow = Instant.parse("2026-11-26T16:30:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        RSAKey key = EnvelopeSigner.generateEphemeralRsaKey("uacrf-test-k1");
        EnvelopeSigner signer = new EnvelopeSigner(key, clock);

        String token = signer.sign(
                "uacrf-s10", "k8s-rollback-adapter", "action-42", "nonce-1",
                fixedNow.plusSeconds(60), Map.of("tenantId", "t1"));

        var claims = signer.verify(token, key.toPublicJWK(), "k8s-rollback-adapter");
        assertEquals("action-42", claims.getSubject());

        assertThrows(SecurityException.class,
                () -> signer.verify(token, key.toPublicJWK(), "wrong-adapter"));

        EnvelopeSigner laterVerifier = new EnvelopeSigner(key, Clock.fixed(fixedNow.plusSeconds(120), ZoneOffset.UTC));
        assertThrows(SecurityException.class,
                () -> laterVerifier.verify(token, key.toPublicJWK(), "k8s-rollback-adapter"));
    }

    @Test
    void enforcesFips1403MinimumKeyStrengthAndKmsProviderProfile() {
        RSAKey nonFips2048 = FipsCryptoProfile.generateKey("dev-2048", false);
        assertThrows(SecurityException.class, () -> FipsCryptoProfile.assertKeyCompliant(nonFips2048, true));

        var kms = new FipsCryptoProfile.VaultOrCloudKmsProvider("AWS_KMS_FIPS", true);
        RSAKey fipsKey = kms.resolveSigningKey("prod-fips-3072");
        assertTrue(kms.fipsValidated());
        assertTrue(fipsKey.size() >= FipsCryptoProfile.FIPS_MIN_RSA_KEY_BITS);

        EnvelopeSigner signer = new EnvelopeSigner(fipsKey, Clock.systemUTC());
        String jws = signer.sign("uacrf-s10", "multi-cloud-traffic-adapter", "recovery-99",
                "nonce-fips-1", Instant.now().plusSeconds(120), Map.of("fips", true));
        assertNotNull(signer.verify(jws, fipsKey.toPublicJWK(), "multi-cloud-traffic-adapter"));
    }
}
