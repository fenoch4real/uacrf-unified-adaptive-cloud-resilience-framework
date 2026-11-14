package org.uacrf.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class BrowserSecurityConfiguration {
  @Bean
  ClientRegistrationRepository clientRegistrationRepository(
      @Value("${uacrf.web.oidc.issuer-uri}") String issuer,
      @Value("${uacrf.web.oidc.client-id}") String clientId,
      @Value("${uacrf.web.oidc.authorization-uri:}") String authorizationUri,
      @Value("${uacrf.web.oidc.token-uri:}") String tokenUri,
      @Value("${uacrf.web.oidc.jwk-set-uri:}") String jwkSetUri,
      @Value("${uacrf.web.oidc.userinfo-uri:}") String userInfoUri) {
    String endpointBase = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile")
            .authorizationUri(
                endpoint(
                    authorizationUri, endpointBase, "/protocol/openid-connect/auth"))
            .tokenUri(endpoint(tokenUri, endpointBase, "/protocol/openid-connect/token"))
            .jwkSetUri(endpoint(jwkSetUri, endpointBase, "/protocol/openid-connect/certs"))
            .userInfoUri(endpoint(userInfoUri, endpointBase, "/protocol/openid-connect/userinfo"))
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .issuerUri(issuer)
            .clientSettings(
                ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
            .build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  private static String endpoint(String configured, String issuerBase, String path) {
    return configured.isBlank() ? issuerBase + path : configured;
  }

  @Bean
  SecurityFilterChain browserSecurity(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/", "/error", "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(login -> login.defaultSuccessUrl("/", true))
        .logout(logout -> logout.logoutSuccessUrl("/"))
        .build();
  }
}
