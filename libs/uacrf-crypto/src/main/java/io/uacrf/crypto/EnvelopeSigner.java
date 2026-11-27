package io.uacrf.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * RS256 JWS signer and verifier for UACRF delegation tokens, Bounded Recovery Authorizations,
 * and sealed Resilience Decision Envelopes (P1, P3).
 */
public final class EnvelopeSigner {

    private final RSAKey signingKey;
    private final Clock clock;

    public EnvelopeSigner(RSAKey signingKey, Clock clock) {
        this.signingKey = signingKey;
        this.clock = clock;
    }

    public static RSAKey generateEphemeralRsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate RSA key", e);
        }
    }

    public String sign(String issuer, String audience, String subject, String nonce,
                       Instant expiresAt, Map<String, Object> customClaims) {
        try {
            Instant now = clock.instant();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .jwtID(nonce)
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt));
            if (customClaims != null) {
                customClaims.forEach(builder::claim);
            }
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(signingKey.getKeyID())
                    .type(JOSEObjectType.JWT)
                    .build();
            SignedJWT jwt = new SignedJWT(header, builder.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign UACRF envelope", e);
        }
    }

    public JWTClaimsSet verify(String token, RSAKey publicKey, String expectedAudience) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(publicKey.toRSAPublicKey()))) {
                throw new SecurityException("Invalid envelope signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
                throw new SecurityException("Audience mismatch: expected " + expectedAudience);
            }
            Instant now = clock.instant();
            if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)) {
                throw new SecurityException("Envelope authorization expired");
            }
            return claims;
        } catch (ParseException | JOSEException e) {
            throw new SecurityException("Malformed or unverifiable envelope token", e);
        }
    }
}
