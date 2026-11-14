package org.uacrf.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;

final class Database {
  final Config cfg;

  Database(Config cfg) {
    this.cfg = cfg;
  }

  Connection connect() throws SQLException {
    return DriverManager.getConnection(cfg.jdbcUrl(), cfg.user(), cfg.password());
  }

  void checkRole() {
    try (var c = connect();
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT rolsuper OR rolbypassrls OR"
                    + " pg_has_role(current_user,'uacrf_runtime_owner','MEMBER') OR"
                    + " has_table_privilege(current_user,(SELECT c.oid FROM pg_class c JOIN"
                    + " pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='uacrf' AND"
                    + " c.relname='principal'),'SELECT,INSERT,UPDATE,DELETE') FROM pg_roles WHERE"
                    + " rolname=current_user")) {
      if (!r.next() || r.getBoolean(1))
        throw new IllegalArgumentException("API login must have only guarded RPC privileges");
    } catch (SQLException e) {
      throw new IllegalStateException("API database role validation failed", e);
    }
  }

  ObjectNode call(
      ObjectNode claims,
      String op,
      ObjectNode body,
      ObjectNode params,
      String key,
      ObjectNode source) {
    try (var c = connect()) {
      c.setAutoCommit(false);
      try {
        try (var settings = c.createStatement()) {
          settings.execute("SET LOCAL statement_timeout='10s'");
          settings.execute("SET LOCAL lock_timeout='5s'");
        }
        ObjectNode result;
        try (var s =
            c.prepareStatement(
                "SELECT uacrf_app.request(?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb)")) {
          s.setString(1, cfg.tenant());
          s.setString(2, cfg.environment());
          s.setString(3, claims.path("iss").asText());
          s.setString(4, claims.path("sub").asText());
          s.setLong(5, claims.path("iat").asLong());
          s.setString(6, op);
          s.setString(7, Json.text(body));
          s.setString(8, Json.text(params));
          s.setString(9, key);
          s.setString(10, source == null ? null : Json.text(source));
          try (var r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Missing guarded result");
            result = (ObjectNode) Json.read(r.getString(1));
          }
        }
        c.commit();
        return result;
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      String state = e.getSQLState();
      if (state != null && state.matches("U0[0-9]{3}"))
        throw new Problem(
            Integer.parseInt(state.substring(2)),
            "Operation rejected by current authorization or domain rules");
      if (java.util.Set.of("23505", "40001", "40P01", "55P03").contains(state == null ? "" : state))
        throw new Problem(409, "Concurrent revision or identity conflict; retry with the same key");
      if (java.util.Set.of("23503", "23514", "22007", "22023", "22P02", "P0001")
          .contains(state == null ? "" : state))
        throw new Problem(422, "Domain reference or invariant rejected");
      throw new Problem(503, "Database boundary unavailable");
    }
  }
}
