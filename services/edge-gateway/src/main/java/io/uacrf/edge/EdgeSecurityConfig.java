package io.uacrf.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Enterprise Security & OIDC/JWT Enforcement on EDGE01 API Gateway.
 * Enforces security headers across all responses and authenticates Bearer JWTs when enabled.
 */
@Configuration
public class EdgeSecurityConfig {

    @Bean
    public SecurityFilterChain edgeSecurityFilterChain(
            HttpSecurity http,
            @Value("${uacrf.edge.security.jwt-enabled:false}") boolean jwtEnabled) throws Exception {

        http.csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'"))
                        .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .frameOptions(frame -> frame.deny())
                );

        if (jwtEnabled) {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .anyRequest().authenticated()
            ).oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }
}
