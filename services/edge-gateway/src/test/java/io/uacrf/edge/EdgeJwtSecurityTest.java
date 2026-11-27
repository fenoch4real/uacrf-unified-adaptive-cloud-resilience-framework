package io.uacrf.edge;

import com.nimbusds.jose.jwk.RSAKey;
import io.uacrf.crypto.EnvelopeSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"uacrf.edge.security.jwt-enabled=true"})
class EdgeJwtSecurityTest {

    private static final RSAKey TEST_RSA = EnvelopeSigner.generateEphemeralRsaKey("edge-jwt-test");

    @TestConfiguration
    static class JwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(TEST_RSA.toRSAPublicKey()).build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void enforcesBearerJwtAndSecurityHeadersWhenEnabled() throws Exception {
        // Missing token must return 401 Unauthorized
        mockMvc.perform(get("/v1/services?tenantId=11111111-1111-7111-8111-111111111111&environmentId=22222222-2222-7222-8222-222222222222"))
                .andExpect(status().isUnauthorized());

        // Signed RS256 Bearer JWT passes gateway security filter (downstreams unreachable returns 502 with security headers)
        EnvelopeSigner signer = new EnvelopeSigner(TEST_RSA, Clock.systemUTC());
        String jwt = signer.sign("http://keycloak:8180/realms/uacrf", "uacrf-edge",
                "maya.chen", "jti-1", Instant.now().plusSeconds(300), Map.of("tenant", "11111111-1111-7111-8111-111111111111"));

        mockMvc.perform(get("/v1/services?tenantId=11111111-1111-7111-8111-111111111111&environmentId=22222222-2222-7222-8222-222222222222")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }
}
