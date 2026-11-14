package org.uacrf.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Offline administrator utility. Never exposed as a controller or under the API login. */
public final class Admin {
  static Connection connect() throws SQLException {
    var e = System.getenv();
    return DriverManager.getConnection(
        Config.required(e, "UACRF_ADMIN_JDBC_URL"),
        Config.required(e, "UACRF_ADMIN_USER"),
        Config.required(e, "UACRF_ADMIN_PASSWORD"));
  }

  static void execute(Connection c, String sql, Object... values) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      s.execute();
    }
  }

  static String scalar(Connection c, String sql, Object... values) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      try (var r = s.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }

  static void install(Connection c, Path root, String login, String password) throws Exception {
    if (!login.matches("[a-z][a-z0-9_]{0,62}")
        || Set.of("postgres", "uacrf_runtime_owner", "uacrf_api").contains(login)
        || password.length() < 24)
      throw new IllegalArgumentException(
          "Distinct API login and password of at least 24 characters required");
    if (scalar(c, "SELECT 1 FROM pg_namespace WHERE nspname IN ('uacrf','uacrf_app')") != null
        || scalar(
                c,
                "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE"
                    + " c.relkind IN ('r','p','v','m') AND n.nspname NOT IN"
                    + " ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_%'")
            != null)
      throw new IllegalArgumentException(
          "Fresh isolated database required; existing relations will not be reset");
    if (scalar(
            c,
            "SELECT 1 FROM pg_roles WHERE rolname IN"
                + " ('uacrf_runtime_owner','uacrf_api','uacrf_graph_worker',?)",
            login)
        != null)
      throw new IllegalArgumentException(
          "Runtime roles already exist; use a separate isolated cluster");
    c.setAutoCommit(false);
    try {
      List<Path> scripts;
      try (var stream = Files.list(root.resolve("database/postgres"))) {
        scripts =
            new ArrayList<>(stream.filter(p -> p.toString().endsWith(".sql")).sorted().toList());
      }
      if (scripts.size() != 18)
        throw new IllegalStateException("Expected all 18 baseline migrations");
      scripts.add(root.resolve("relationship_design/storage.proposed.sql"));
      scripts.add(root.resolve("identity_design/storage.proposed.sql"));
      scripts.add(root.resolve("runtime/001_runtime.sql"));
      scripts.add(root.resolve("runtime/002_graph_runtime.sql"));
      scripts.add(root.resolve("runtime/003_assurance_runtime.sql"));
      for (var p : scripts)
        try (var statement = c.createStatement()) {
          statement.execute(
              Files.readString(p)
                  .replaceAll("(?m)^BEGIN;[ \t]*$", "")
                  .replaceAll("(?m)^COMMIT;[ \t]*$", ""));
        }
      // PostgreSQL format supplies database-side identifier/literal quoting; no shell
      // interpolation.
      String ddl =
          scalar(
              c,
              "SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD"
                  + " %L',?::text,?::text)",
              login,
              password);
      try (var s = c.createStatement()) {
        s.execute(ddl);
        s.execute("GRANT uacrf_api TO \"" + login + "\"");
      }
      c.commit();
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  static String bootstrap(
      Connection c,
      Config cfg,
      Identity.Source source,
      String token,
      String principal,
      String reason,
      String witnessToken)
      throws Exception {
    if (!principal.matches("[A-Za-z0-9_.:-]{1,200}") || reason.isBlank() || reason.length() > 2000)
      throw new IllegalArgumentException(
          "Stable principal ID and bounded retained reason required");
    ObjectNode claims = Identity.verify(token, cfg.issuer(), cfg.audience(), cfg.keys(), 900);
    ObjectNode identity = source.verified("identity", claims.path("sub").asText());
    if (!identity.path("kind").asText().equals("HUMAN")
        || !identity.path("issuer").asText().equals(cfg.issuer())
        || !identity.path("subject").asText().equals(claims.path("sub").asText()))
      throw new IllegalArgumentException("Verified human bootstrap identity required");
    String witness = null;
    if (witnessToken != null) {
      var w = Identity.verify(witnessToken, cfg.issuer(), cfg.audience(), cfg.keys(), 900);
      var wi = source.verified("identity", w.path("sub").asText());
      if (!wi.path("kind").asText().equals("HUMAN")
          || !cfg.issuer().equals(wi.path("issuer").asText())
          || !wi.path("subject").asText().equals(w.path("sub").asText())
          || w.path("sub").asText().equals(claims.path("sub").asText()))
        throw new IllegalArgumentException("Distinct verified human recovery witness required");
      witness = w.path("sub").asText();
    }
    c.setAutoCommit(false);
    try {
      execute(
          c,
          "SELECT"
              + " pg_advisory_xact_lock(hashtextextended(jsonb_build_array(?::text,?::text,?::text)::text,0))",
          cfg.tenant(),
          cfg.environment(),
          "runtime-v1");
      execute(
          c,
          "SELECT"
              + " set_config('app.tenant_id',?,true),set_config('app.environment_id',?,true),set_config('app.actor_id',?,true)",
          cfg.tenant(),
          cfg.environment(),
          principal);
      boolean exists =
          scalar(
                  c,
                  "SELECT 1 FROM uacrf.command WHERE tenant_id=? AND environment_id=? AND"
                      + " operation='runtime.bootstrap'",
                  cfg.tenant(),
                  cfg.environment())
              != null;
      if (exists && witness == null)
        throw new IllegalArgumentException("Already bootstrapped; controlled recovery required");
      if (!exists && witness != null)
        throw new IllegalArgumentException("Recovery requires an existing bootstrap");
      execute(
          c,
          "INSERT INTO uacrf.environment(tenant_id,environment_id,id,name,lifecycle)"
              + " VALUES(?,?,?,?,'ACTIVE') ON CONFLICT(tenant_id,environment_id) DO NOTHING",
          cfg.tenant(),
          cfg.environment(),
          cfg.environment(),
          cfg.environment());
      String binding =
          scalar(
              c,
              "SELECT issuer||chr(1)||subject FROM uacrf.principal WHERE tenant_id=? AND"
                  + " environment_id=? AND id=? FOR UPDATE",
              cfg.tenant(),
              cfg.environment(),
              principal);
      if (binding != null && !binding.equals(cfg.issuer() + "\1" + claims.path("sub").asText()))
        throw new IllegalArgumentException("Existing identity binding cannot change");
      if (binding == null)
        execute(
            c,
            "INSERT INTO"
                + " uacrf.principal(tenant_id,environment_id,id,issuer,subject,display_name,kind)"
                + " VALUES(?,?,?,?,?,?,'HUMAN')",
            cfg.tenant(),
            cfg.environment(),
            principal,
            cfg.issuer(),
            claims.path("sub").asText(),
            identity.path("display_name").asText());
      else
        execute(
            c,
            "UPDATE uacrf.principal SET"
                + " enabled=true,revision=revision+1,payload=payload||jsonb_build_object('token_valid_after',?::bigint)"
                + " WHERE tenant_id=? AND environment_id=? AND id=?",
            claims.path("iat").asLong(),
            cfg.tenant(),
            cfg.environment(),
            principal);
      String command = "bootstrap:" + UUID.randomUUID();
      var caps = Json.read(scalar(c, "SELECT to_jsonb(uacrf_app.capabilities())"));
      for (var cap : caps)
        execute(
            c,
            "INSERT INTO"
                + " uacrf.membership(tenant_id,environment_id,id,principal_id,capability,service_scope,expires_at,payload)"
                + " VALUES(?,?,?,?,?,'*',clock_timestamp()+interval '1 hour',?::jsonb)",
            cfg.tenant(),
            cfg.environment(),
            command + ":" + cap.asText(),
            principal,
            cap.asText(),
            Json.text(Map.of("reason", reason, "bootstrap", true)));
      ObjectNode payload =
          Json.M.createObjectNode()
              .put("reason", reason)
              .put("subject", claims.path("sub").asText())
              .put("expires_in_seconds", 3600);
      if (witness == null) payload.putNull("recovery_witness");
      else payload.put("recovery_witness", witness);
      String p = Json.text(payload),
          operation = witness == null ? "runtime.bootstrap" : "runtime.recovery";
      execute(
          c,
          "INSERT INTO"
              + " uacrf.command(tenant_id,environment_id,id,operation,actor_id,request_key,request_digest,status,payload)"
              + " VALUES(?,?,?,?,?,?,encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),'SUCCEEDED',?::jsonb)",
          cfg.tenant(),
          cfg.environment(),
          command,
          operation,
          principal,
          command,
          p,
          p);
      execute(
          c,
          "INSERT INTO"
              + " uacrf.audit_event(tenant_id,environment_id,id,actor_id,action,target_id,digest,payload)"
              + " VALUES(?,?,?,?,?,?,encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),?::jsonb)",
          cfg.tenant(),
          cfg.environment(),
          command,
          principal,
          operation,
          principal,
          p,
          p);
      execute(
          c,
          "INSERT INTO uacrf.outbox(tenant_id,environment_id,id,topic,aggregate_id,payload)"
              + " VALUES(?,?,?,'runtime.administration',?,?::jsonb)",
          cfg.tenant(),
          cfg.environment(),
          command,
          principal,
          p);
      c.commit();
      return command;
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  static String secret(String prompt) {
    var console = System.console();
    if (console == null)
      throw new IllegalArgumentException(
          "Interactive console required; do not pass tokens or passwords as arguments");
    char[] value = console.readPassword("%s: ", prompt);
    if (value == null) throw new IllegalArgumentException("Input cancelled");
    try {
      return new String(value);
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  static void graphInstall(Connection c, Path root) throws Exception {
    if (scalar(c, "SELECT to_regclass('uacrf_app.graph_projection')") != null)
      throw new IllegalArgumentException("Graph runtime already installed");
    if (scalar(c, "SELECT to_regclass('uacrf.graph_revision')") == null)
      throw new IllegalArgumentException("Install the existing baseline/runtime first");
    c.setAutoCommit(false);
    try (var statement = c.createStatement()) {
      statement.execute(
          Files.readString(root.resolve("runtime/002_graph_runtime.sql"))
              .replaceAll("(?m)^BEGIN;[ \t]*$", "")
              .replaceAll("(?m)^COMMIT;[ \t]*$", ""));
      c.commit();
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  static void assuranceInstall(Connection c, Path root) throws Exception {
    if (scalar(c, "SELECT to_regclass('uacrf_app.recovery_assessment')") != null)
      throw new IllegalArgumentException("Assurance runtime already installed");
    if (scalar(c, "SELECT to_regclass('uacrf_app.graph_projection')") == null)
      throw new IllegalArgumentException("Install the existing baseline/runtime first");
    c.setAutoCommit(false);
    try (var statement = c.createStatement()) {
      statement.execute(
          Files.readString(root.resolve("runtime/003_assurance_runtime.sql"))
              .replaceAll("(?m)^BEGIN;[ \t]*$", "")
              .replaceAll("(?m)^COMMIT;[ \t]*$", ""));
      c.commit();
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  static void workerLogin(Connection c, Config cfg, String login, String password)
      throws Exception {
    if (!login.matches("[a-z][a-z0-9_]{0,62}")
        || Set.of("postgres", "uacrf_api", "uacrf_runtime_owner", "uacrf_graph_worker")
            .contains(login)
        || password.length() < 24)
      throw new IllegalArgumentException("Distinct worker login and strong password required");
    c.setAutoCommit(false);
    try {
      String ddl =
          scalar(
              c,
              "SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD"
                  + " %L',?::text,?::text)",
              login,
              password);
      try (var statement = c.createStatement()) {
        statement.execute(ddl);
        statement.execute("GRANT uacrf_graph_worker TO \"" + login + "\"");
      }
      execute(
          c,
          "INSERT INTO uacrf_app.graph_worker_binding(login_name,tenant_id,environment_id)"
              + " VALUES(?,?,?)",
          login,
          cfg.tenant(),
          cfg.environment());
      c.commit();
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("graph-load")) {
      GraphAdmin.main(java.util.Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    if (args.length == 1 && args[0].equals("assurance-install")) {
      try (var c = connect()) {
        assuranceInstall(c, Path.of(Config.required(System.getenv(), "UACRF_RELEASE_ROOT")));
      }
      return;
    }
    if (args.length == 1 && args[0].equals("graph-install")) {
      try (var c = connect()) {
        graphInstall(c, Path.of(Config.required(System.getenv(), "UACRF_RELEASE_ROOT")));
      }
      System.out.println("Graph SQL extension installed; no graph content was accepted");
      return;
    }
    if (args.length == 1 && args[0].equals("graph-constraints")) {
      Path root = Path.of(Config.required(System.getenv(), "UACRF_RELEASE_ROOT"));
      try (var store = new GraphStore(GraphConfig.load(true), root)) {
        store.constraints(root);
        store.assertConstraints();
      }
      System.out.println("Neo4j uniqueness constraints installed");
      return;
    }
    if (args.length == 2 && args[0].equals("graph-worker-login")) {
      Config cfg = Config.load();
      try (var c = connect()) {
        workerLogin(c, cfg, args[1], secret("New graph-worker database password"));
      }
      System.out.println("Environment-bound graph worker login created");
      return;
    }
    if (args.length == 4 && args[0].equals("graph-seal")) {
      Config cfg = Config.load();
      var claims =
          Identity.verify(
              secret("Verified graph acceptance token"),
              cfg.issuer(),
              cfg.audience(),
              cfg.keys(),
              900);
      var source = new Identity.Directory(cfg).verified("identity", claims.path("sub").asText());
      if (!source.path("kind").asText().equals("HUMAN")
          || !source.path("subject").asText().equals(claims.path("sub").asText()))
        throw new IllegalArgumentException("Verified human acceptance required");
      try (var c = connect()) {
        String actor =
            scalar(
                c,
                "SELECT id FROM uacrf.principal WHERE tenant_id=? AND environment_id=? AND issuer=?"
                    + " AND subject=? AND enabled AND kind='HUMAN' AND"
                    + " ?::bigint>=coalesce((payload->>'token_valid_after')::bigint,0)",
                cfg.tenant(),
                cfg.environment(),
                cfg.issuer(),
                claims.path("sub").asText(),
                claims.path("iat").asLong());
        if (actor == null)
          throw new IllegalArgumentException("Current accountable principal unavailable");
        System.out.println(
            scalar(
                c,
                "SELECT uacrf_app.graph_seal(?,?,?,?::bigint,?,?)",
                cfg.tenant(),
                cfg.environment(),
                args[1],
                Long.parseLong(args[2]),
                args[3],
                actor));
      }
      return;
    }

    if (args.length == 3
        && args[0].equals("install")
        && args[1].equals("--acknowledge-empty-isolated-database")) {
      Path root = Path.of(Config.required(System.getenv(), "UACRF_RELEASE_ROOT"));
      try (var c = connect()) {
        install(c, root, args[2], secret("New API password"));
      }
      System.out.println(
          "Installed isolated database; use separate API credentials to start the server.");
    } else if (args.length >= 3
        && args.length <= 4
        && args[0].equals("bootstrap")
        && (args.length == 3 || args[3].equals("--recover"))) {
      var cfg = Config.load();
      String token = secret("Verified bootstrap token"),
          witness = args.length == 4 ? secret("Distinct verified recovery witness token") : null;
      try (var c = connect()) {
        System.out.println(
            bootstrap(c, cfg, new Identity.Directory(cfg), token, args[1], args[2], witness));
      }
    } else
      throw new IllegalArgumentException(
          "Usage: admin install --acknowledge-empty-isolated-database LOGIN | admin bootstrap"
              + " PRINCIPAL_ID REASON [--recover]");
  }
}
