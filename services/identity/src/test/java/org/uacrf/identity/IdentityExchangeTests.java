package org.uacrf.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.uacrf.security.DelegationJwtEncoderFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class IdentityExchangeTests {
  private static final String ISSUER = "https://uacrf.local/s01";
  private static final String KEY_ID = "test-key";
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private Instant now;
  private JwtEncoder encoder;
  private JwtDecoder decoder;
  private JwtDecoder outputDecoder;
  private IdentityExchangeService service;

  @BeforeEach
  void setup() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    encoder = DelegationJwtEncoderFactory.create(keys, KEY_ID);
    decoder = new IdentitySecurityConfiguration().exchangeJwtDecoder(keys, ISSUER, KEY_ID);
    outputDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
    service = new IdentityExchangeService(jdbc, encoder, ISSUER, KEY_ID, Clock.fixed(now, java.time.ZoneOffset.UTC));
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
  }

  @Test
  void issuesExactOwnerAndEvidenceScopesAndRechecksOriginalActor() {
    Jwt assertion = incoming("S06", "topology.proposal.write", now.minusSeconds(1), now.plusSeconds(20));
    for (var mapping : List.of(
        List.of("S02", "agent_workload", "catalog.agent.owner-receipt"),
        List.of("S04", "evidence_event", "evidence.topology.summary"))) {
      Map<String, Object> request = request(mapping.get(0), mapping.get(1), "topology.dependency-proposal");
      Jwt result = decodeOutput(service.exchange(assertion, request).accessToken(), mapping.get(0));
      assertThat(result.getSubject()).isEqualTo("principal-1");
      assertThat(result.getClaimAsString("actor_subject")).isEqualTo("directory-user");
      assertThat(result.getClaimAsString("actor_issuer")).isEqualTo("https://directory.example");
      assertThat(result.getClaimAsString("service_scope")).isEqualTo("resource-1");
      assertThat(result.getClaimAsString("resource_id")).isEqualTo("resource-1");
      assertThat(result.getClaimAsString("resource_type")).isEqualTo(mapping.get(1));
      assertThat(result.getClaimAsString("purpose")).isEqualTo("topology.dependency-proposal");
      assertThat(result.getClaimAsStringList("capabilities")).containsExactly(mapping.get(2));
      assertThat(result.getExpiresAt()).isEqualTo(now.plusSeconds(4));
      assertThat(Duration.between(result.getIssuedAt(), result.getExpiresAt())).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, org.mockito.Mockito.times(2)).queryForObject(anyString(), eq(Boolean.class), args.capture());
    assertThat(args.getValue()).containsExactly(
        "tenant-1", "env-1", "principal-1", "https://directory.example", "directory-user",
        java.sql.Timestamp.from(assertion.getIssuedAt()), "topology.proposal.write",
        "topology.proposal.write", "*");
  }

  @Test
  void checksEachPurposeAndDoesNotExceedIncomingExpiry() {
    for (var pair : List.of(
        List.of("topology.dependency-review", "topology.proposal.review"),
        List.of("topology.graph.current-use", "topology.graph.read"))) {
      Jwt assertion = incoming("S06", pair.get(1), now.minusSeconds(1), now.plusSeconds(2));
      Jwt result = decodeOutput(service.exchange(assertion, request("S02", "agent_workload", pair.get(0))).accessToken(), "S02");
      assertThat(result.getExpiresAt()).isEqualTo(assertion.getExpiresAt());
      assertThat(result.getClaimAsString("purpose")).isEqualTo(pair.get(0));
    }
  }

  @Test
  void rejectsUntrustedOrExpiredAssertions() throws Exception {
    assertThatThrownBy(() -> incoming("S02", "topology.graph.read", now.minusSeconds(1), now.plusSeconds(10)))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> incoming("S06", "topology.graph.read", now.minusSeconds(3), now.minusSeconds(1)))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> incoming("S06", "topology.graph.read", now.plusSeconds(10), now.plusSeconds(20)))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> incoming("S06", "topology.graph.read", now.minusSeconds(1), now.plusSeconds(61)))
        .isInstanceOf(JwtException.class);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    JwtEncoder untrusted = DelegationJwtEncoderFactory.create(generator.generateKeyPair(), KEY_ID);
    String forged = encode(untrusted, "S06", "topology.graph.read", now.minusSeconds(1), now.plusSeconds(10));
    assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsInvalidInputsAndExcessAuthority() {
    Jwt assertion = incoming("S06", "topology.proposal.write", now.minusSeconds(1), now.plusSeconds(20));
    Map<String, Object> base = request("S02", "agent_workload", "topology.dependency-proposal");
    for (String forbidden : List.of("actor_id", "actor_subject", "capability", "capability_ids")) {
      Map<String, Object> extra = new HashMap<>(base);
      extra.put(forbidden, "forged");
      assertInvalid(assertion, extra);
    }
    for (Map<String, Object> invalid : List.of(
        request("S04", "agent_workload", "topology.dependency-proposal"),
        request("S02", "unknown", "topology.dependency-proposal"),
        request("S02", "agent_workload", "other-purpose"))) {
      assertInvalid(assertion, invalid);
    }
    for (Instant deadline : List.of(now, now.minusSeconds(1), now.plusSeconds(6))) {
      Map<String, Object> invalid = new HashMap<>(base);
      invalid.put("operation_deadline", deadline.toString());
      assertInvalid(assertion, invalid);
    }
    for (var replacement : Map.<String, Object>of(
        "operation_deadline", "invalid-date", "resource_id", "../other", "purpose", 123).entrySet()) {
      Map<String, Object> invalid = new HashMap<>(base);
      invalid.put(replacement.getKey(), replacement.getValue());
      assertInvalid(assertion, invalid);
    }
    assertThatThrownBy(() -> service.exchange(assertion, request("S02", "agent_workload", "topology.dependency-review")))
        .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.status().value()).isEqualTo(403));
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
    assertThatThrownBy(() -> service.exchange(assertion, base))
        .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.status().value()).isEqualTo(403));
  }

  @Test
  void rejectsWhenBudgetExpiresDuringMembershipCheck() {
    Jwt assertion = incoming("S06", "topology.proposal.write", now.minusSeconds(1), now.plusSeconds(20));
    Clock advancing = mock(Clock.class);
    when(advancing.instant()).thenReturn(now, now.plusSeconds(5));
    IdentityExchangeService delayed = new IdentityExchangeService(jdbc, encoder, ISSUER, KEY_ID, advancing);
    assertThatThrownBy(() -> delayed.exchange(assertion, request("S02", "agent_workload", "topology.dependency-proposal")))
        .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.status().value()).isEqualTo(400));
  }

  @Test
  void rejectsWhenDeadlineCannotBeRepresentedAsFutureJwtExpiry() {
    Jwt assertion = incoming("S06", "topology.proposal.write", now.minusSeconds(1), now.plusSeconds(20));
    Map<String, Object> request = new HashMap<>(request("S02", "agent_workload", "topology.dependency-proposal"));
    request.put("operation_deadline", now.plusMillis(900).toString());
    assertInvalid(assertion, request);
  }

  @Test
  void httpRouteRejectsAdditionalActorFields() throws Exception {
    Jwt assertion = incoming("S06", "topology.proposal.write", now.minusSeconds(1), now.plusSeconds(20));
    var mvc = MockMvcBuilders.standaloneSetup(new IdentityController(mock(IdentityAccessService.class), service))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new IdentityExceptionHandler())
        .build();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(assertion, null, List.of()));
    try {
      String json = "{\"audience\":\"S02\",\"resource_type\":\"agent_workload\","
          + "\"resource_id\":\"resource-1\",\"purpose\":\"topology.dependency-proposal\","
          + "\"operation_deadline\":\"" + now.plusSeconds(4) + "\"";
      mvc.perform(post("/internal/v1/delegations:exchange").contentType(MediaType.APPLICATION_JSON)
          .content(json + "}")).andExpect(status().isOk());
      mvc.perform(post("/internal/v1/delegations:exchange").contentType(MediaType.APPLICATION_JSON)
          .content(json + ",\"actor_id\":\"forged\"}")).andExpect(status().isBadRequest());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void securityChainSelectsDelegationDecoderOnlyForExchange() throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
          "uacrf.delegation.issuer", ISSUER, "uacrf.delegation.key-id", KEY_ID,
          "uacrf.identity.oidc.issuer-uri", "https://directory.example",
          "uacrf.identity.oidc.jwk-set-uri", "https://directory.example/keys",
          "uacrf.identity.oidc.audience", "s01")));
      context.register(SecurityTestConfiguration.class);
      context.refresh();
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters((jakarta.servlet.Filter) context.getBean("springSecurityFilterChain"))
          .build();
      JwtEncoder signer = context.getBean("delegationJwtEncoder", JwtEncoder.class);
      Instant instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      String token = encode(signer, "S06", "topology.proposal.write", instant.minusSeconds(1), instant.plusSeconds(20));
      String wrongAudience = encode(signer, "S02", "topology.proposal.write", instant.minusSeconds(1), instant.plusSeconds(20));
      String json = "{\"audience\":\"S02\",\"resource_type\":\"agent_workload\","
          + "\"resource_id\":\"resource-1\",\"purpose\":\"topology.dependency-proposal\","
          + "\"operation_deadline\":\"" + instant.plusSeconds(4) + "\"}";
      mvc.perform(post("/internal/v1/delegations:exchange").header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
      mvc.perform(post("/internal/v1/delegations:exchange").header("Authorization", "Bearer " + wrongAudience)
          .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
      mvc.perform(post("/internal/v1/delegations")
          .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
  }

  @TestConfiguration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({IdentitySecurityConfiguration.class, IdentityController.class, IdentityExchangeService.class})
  static class SecurityTestConfiguration {
    @Bean
    KeyPair identityDelegationKeyPair() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    }

    @Bean
    JwtEncoder delegationJwtEncoder(KeyPair identityDelegationKeyPair) {
      return DelegationJwtEncoderFactory.create(identityDelegationKeyPair, KEY_ID);
    }

    @Bean
    JdbcTemplate jdbc() {
      JdbcTemplate jdbc = mock(JdbcTemplate.class);
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
      return jdbc;
    }

    @Bean
    IdentityAccessService identityAccessService() {
      return mock(IdentityAccessService.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }

  private void assertInvalid(Jwt assertion, Map<String, Object> request) {
    assertThatThrownBy(() -> service.exchange(assertion, request))
        .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.status().value()).isEqualTo(400));
  }

  private Map<String, Object> request(String audience, String type, String purpose) {
    return Map.of("audience", audience, "resource_type", type, "resource_id", "resource-1",
        "purpose", purpose, "operation_deadline", now.plusSeconds(4).toString());
  }

  private Jwt incoming(String audience, String capability, Instant issued, Instant expires) {
    return decoder.decode(encode(encoder, audience, capability, issued, expires));
  }

  private String encode(JwtEncoder signer, String audience, String capability, Instant issued, Instant expires) {
    JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject("principal-1")
        .audience(List.of(audience)).issuedAt(issued).expiresAt(expires)
        .claim("tenant_id", "tenant-1").claim("environment_id", "env-1")
        .claim("service_scope", "*").claim("actor_issuer", "https://directory.example")
        .claim("actor_subject", "directory-user").claim("capabilities", List.of(capability)).build();
    return signer.encode(JwtEncoderParameters.from(
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
  }

  private Jwt decodeOutput(String token, String audience) {
    Jwt result = outputDecoder.decode(token);
    assertThat(result.getAudience()).containsExactly(audience);
    return result;
  }
}
