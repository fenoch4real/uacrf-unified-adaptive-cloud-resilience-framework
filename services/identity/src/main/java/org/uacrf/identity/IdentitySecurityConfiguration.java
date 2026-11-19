package org.uacrf.identity;

import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.uacrf.security.DelegationKeyMaterial;
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
  JwtDecoder exchangeJwtDecoder(
      java.security.KeyPair identityDelegationKeyPair,
      @Value("${uacrf.delegation.issuer}") String issuer,
      @Value("${uacrf.delegation.key-id}") String keyId) {
    DelegationKeyMaterial.validateKeyId(keyId);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) identityDelegationKeyPair.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(jwt -> {
      Instant now = Instant.now();
      Instant issued = jwt.getIssuedAt();
      Instant expires = jwt.getExpiresAt();
      boolean valid =
          jwt.getIssuer() != null && issuer.equals(jwt.getIssuer().toString())
              && (List.of("S06").equals(jwt.getAudience())
                  || List.of("S05").equals(jwt.getAudience()))
              && keyId.equals(jwt.getHeaders().get("kid"))
              && issued != null && !issued.isAfter(now)
              && expires != null && expires.isAfter(now) && expires.isAfter(issued)
              && Duration.between(issued, expires).compareTo(Duration.ofSeconds(60)) <= 0
              && validId(jwt.getSubject())
              && validId(jwt.getClaimAsString("tenant_id"))
              && validId(jwt.getClaimAsString("environment_id"))
              && ("*".equals(jwt.getClaimAsString("service_scope"))
                  || validId(jwt.getClaimAsString("service_scope")))
              && nonblank(jwt.getClaimAsString("actor_issuer"))
              && nonblank(jwt.getClaimAsString("actor_subject"));
      return valid ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error("invalid_token", "Invalid downstream delegation", null));
    });
    return decoder;
  }

  private static boolean validId(String value) {
    return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$");
  }

  private static boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  @Bean
  @Order(1)
  SecurityFilterChain exchangeSecurity(
      HttpSecurity http, @Qualifier("exchangeJwtDecoder") JwtDecoder decoder) throws Exception {
    return http.securityMatcher("/internal/v1/delegations:exchange")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(decoder)))
        .build();
  }

  @Bean
  @Order(2)
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
