package org.uacrf.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
class IdentitySecurityConfiguration {
  @Bean
  JwtDecoder identityJwtDecoder(
      @Value("${uacrf.identity.oidc.issuer-uri}") String issuer,
      @Value("${uacrf.identity.oidc.jwk-set-uri}") String jwkSetUri,
      @Value("${uacrf.identity.oidc.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtClaimValidator<Object>(
            "aud",
            value -> value instanceof List<?> values && values.contains(audience));
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }

  @Bean
  SecurityFilterChain identitySecurity(
      HttpSecurity http, JwtDecoder identityJwtDecoder, ObjectMapper objectMapper)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(identityJwtDecoder)))
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    (request, response, error) -> {
                      response.setStatus(401);
                      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                      objectMapper.writeValue(
                          response.getOutputStream(),
                          new IdentityResponses.Error(
                              "UNAUTHENTICATED",
                              "A valid Keycloak access token is required.",
                              UUID.randomUUID().toString()));
                    }))
        .build();
  }
}
