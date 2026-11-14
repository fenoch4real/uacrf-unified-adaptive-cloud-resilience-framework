package org.uacrf.identity;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    prefix = "uacrf.identity.bootstrap", name = "enabled", havingValue = "true")
class IdentityBootstrap implements ApplicationRunner {
  private static final List<String> INITIAL_ADMIN_CAPABILITIES =
      List.of(
          "identity.directory.read",
          "identity.principal.manage",
          "identity.membership.read",
          "identity.membership.manage",
          "identity.owner.select",
          "ownership.transfer",
          "identity.provider.manage");

  private static final List<String> APPROVER_CAPABILITIES =
      List.of("identity.directory.read", "identity.grant.approve");

  private final JdbcTemplate jdbc;
  private final String approverPrincipalId;
  private final String approverSubject;
  private final String approverDisplayName;
  private final String providerId;
  private final String providerName;
  private final String issuer;
  private final String tenantId;
  private final String tenantName;
  private final String environmentId;
  private final String environmentName;
  private final String principalId;
  private final String subject;
  private final String displayName;
  private final Instant adminExpiresAt;
  private final String reason;

  IdentityBootstrap(
      JdbcTemplate jdbc,
      @Value("${uacrf.identity.bootstrap.provider-id}") String providerId,
      @Value("${uacrf.identity.bootstrap.provider-name}") String providerName,
      @Value("${uacrf.identity.bootstrap.issuer}") String issuer,
      @Value("${uacrf.identity.bootstrap.tenant-id}") String tenantId,
      @Value("${uacrf.identity.bootstrap.tenant-name}") String tenantName,
      @Value("${uacrf.identity.bootstrap.environment-id}") String environmentId,
      @Value("${uacrf.identity.bootstrap.environment-name}") String environmentName,
      @Value("${uacrf.identity.bootstrap.principal-id}") String principalId,
      @Value("${uacrf.identity.bootstrap.subject}") String subject,
      @Value("${uacrf.identity.bootstrap.display-name}") String displayName,
      @Value("${uacrf.identity.bootstrap.admin-expires-at}") Instant adminExpiresAt,
      @Value("${uacrf.identity.bootstrap.reason}") String reason,
      @Value("${uacrf.identity.bootstrap.approver-principal-id:}") String approverPrincipalId,
      @Value("${uacrf.identity.bootstrap.approver-subject:}") String approverSubject,
      @Value("${uacrf.identity.bootstrap.approver-display-name:}") String approverDisplayName) {
    this.jdbc = jdbc;
    this.approverPrincipalId = approverPrincipalId;
    this.approverSubject = approverSubject;
    this.approverDisplayName = approverDisplayName;
    this.providerId = providerId;
    this.providerName = providerName;
    this.issuer = issuer;
    this.tenantId = tenantId;
    this.tenantName = tenantName;
    this.environmentId = environmentId;
    this.environmentName = environmentName;
    this.principalId = principalId;
    this.subject = subject;
    this.displayName = displayName;
    this.adminExpiresAt = adminExpiresAt;
    this.reason = reason;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (providerId.isBlank()
        || providerName.isBlank()
        || tenantId.isBlank()
        || tenantName.isBlank()
        || environmentId.isBlank()
        || environmentName.isBlank()
        || principalId.isBlank()
        || subject.isBlank()
        || displayName.isBlank()
        || reason.isBlank()) {
      throw new IllegalStateException("Enabled identity bootstrap requires all identity fields.");
    }

    boolean hasApprover = !approverPrincipalId.isBlank();
    if (hasApprover == approverSubject.isBlank() || hasApprover == approverDisplayName.isBlank()) {
      throw new IllegalStateException(
          "Identity bootstrap approver requires principal id, subject and display name together.");
    }
    if (hasApprover
        && (approverPrincipalId.equals(principalId) || approverSubject.equals(subject))) {
      throw new IllegalStateException(
          "Identity bootstrap approver must be a different principal from the administrator.");
    }

    URI issuerUri = URI.create(issuer);
    validateIssuer(issuerUri);
    if (!adminExpiresAt.isAfter(Instant.now())) {
      throw new IllegalStateException("Identity bootstrap admin grants must have a future expiry.");
    }

    Boolean initialized =
        jdbc.queryForObject(
            "SELECT initialized FROM uacrf_identity.bootstrap_guard "
                + "WHERE singleton = true FOR UPDATE",
            Boolean.class);
    List<Boolean> bootstrap =
        jdbc.query(
            "SELECT true FROM uacrf_identity.bootstrap_audit WHERE singleton = true FOR UPDATE",
            (rs, row) -> rs.getBoolean(1));
    if (!bootstrap.isEmpty()) {
      verifyCompletedBootstrap();
      return;
    }
    if (Boolean.TRUE.equals(initialized)) {
      throw new IllegalStateException("Identity bootstrap state is inconsistent.");
    }
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM uacrf_identity.principal) "
                + "OR EXISTS (SELECT 1 FROM uacrf_identity.membership)",
            Boolean.class))) {
      throw new IllegalStateException(
          "Refusing first-admin bootstrap because the identity store is already populated.");
    }

    jdbc.update(
        "INSERT INTO uacrf_identity.tenant (id, display_name, status, revision) "
            + "VALUES (?, ?, 'ACTIVE', 1)",
        tenantId,
        tenantName);
    jdbc.update(
        "INSERT INTO uacrf_identity.environment "
            + "(tenant_id, id, display_name, status, revision) VALUES (?, ?, ?, 'ACTIVE', 1)",
        tenantId,
        environmentId,
        environmentName);
    jdbc.update(
        "INSERT INTO uacrf_identity.identity_provider "
            + "(id, issuer_uri, display_name, status, revision) VALUES (?, ?, ?, 'TRUSTED', 1)",
        providerId,
        issuer,
        providerName);
    jdbc.update(
        "INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES (?, ?, ?, ?, 'HUMAN', true, date_trunc('second', now()), 1)",
        principalId,
        issuer,
        subject,
        displayName);
    for (String capability : INITIAL_ADMIN_CAPABILITIES) {
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, ?, ?, ?, ?, '*', ?, 1)",
          UUID.randomUUID(),
          tenantId,
          environmentId,
          principalId,
          capability,
          Timestamp.from(adminExpiresAt));
    }
    if (hasApprover) {
      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
              + "VALUES (?, ?, ?, ?, 'HUMAN', true, date_trunc('second', now()), 1)",
          approverPrincipalId,
          issuer,
          approverSubject,
          approverDisplayName);
      for (String capability : APPROVER_CAPABILITIES) {
        jdbc.update(
            "INSERT INTO uacrf_identity.membership "
                + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
                + "expires_at, revision) VALUES (?, ?, ?, ?, ?, '*', ?, 1)",
            UUID.randomUUID(),
            tenantId,
            environmentId,
            approverPrincipalId,
            capability,
            Timestamp.from(adminExpiresAt));
      }
    }
    jdbc.update(
        "INSERT INTO uacrf_identity.bootstrap_audit "
            + "(singleton, provider_id, issuer_uri, subject, tenant_id, environment_id, "
            + "principal_id, admin_expires_at, granted_capabilities, bootstrap_reason, "
            + "approver_principal_id, approver_subject, approver_capabilities) "
            + "VALUES (true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        providerId,
        issuer,
        subject,
        tenantId,
        environmentId,
        principalId,
        Timestamp.from(adminExpiresAt),
        String.join(",", INITIAL_ADMIN_CAPABILITIES),
        reason,
        hasApprover ? approverPrincipalId : null,
        hasApprover ? approverSubject : null,
        hasApprover ? String.join(",", APPROVER_CAPABILITIES) : null);
    jdbc.update(
        "UPDATE uacrf_identity.bootstrap_guard SET initialized = true WHERE singleton = true");
  }

  static void validateIssuer(URI issuerUri) {
    String scheme = issuerUri.getScheme();
    String host = issuerUri.getHost();
    boolean secureIssuer = "https".equalsIgnoreCase(scheme) && host != null;
    boolean localDevelopmentIssuer =
        "http".equalsIgnoreCase(scheme)
            && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
    if (!secureIssuer && !localDevelopmentIssuer) {
      throw new IllegalStateException(
          "Identity bootstrap issuer must use HTTPS or loopback HTTP for local development.");
    }
  }

  private void verifyCompletedBootstrap() {
    List<String> principalIds =
        jdbc.query(
            "SELECT principal_id FROM uacrf_identity.bootstrap_audit WHERE singleton = true "
                + "AND provider_id = ? AND issuer_uri = ? AND subject = ? AND tenant_id = ? "
                + "AND environment_id = ? AND principal_id = ? AND admin_expires_at = ? "
                + "AND granted_capabilities = ? AND bootstrap_reason = ? "
                + "AND approver_principal_id IS NOT DISTINCT FROM ? "
                + "AND approver_subject IS NOT DISTINCT FROM ?",
            (rs, row) -> rs.getString(1),
            providerId,
            issuer,
            subject,
            tenantId,
            environmentId,
            principalId,
            Timestamp.from(adminExpiresAt),
            String.join(",", INITIAL_ADMIN_CAPABILITIES),
            reason,
            approverPrincipalId.isBlank() ? null : approverPrincipalId,
            approverSubject.isBlank() ? null : approverSubject);
    if (principalIds.isEmpty()) {
      throw new IllegalStateException(
          "Identity bootstrap was already completed with different configuration.");
    }
  }
}
