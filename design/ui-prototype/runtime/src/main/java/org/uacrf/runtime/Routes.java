package org.uacrf.runtime;

import java.util.*;
import java.util.regex.*;

record Routes(
    String method,
    String path,
    String operation,
    String contract,
    Pattern regex,
    List<String> names) {
  static Routes of(String method, String path, String op, String contract) {
    Matcher m = Pattern.compile("\\{([^}]+)\\}").matcher(path);
    var names = new ArrayList<String>();
    var b = new StringBuilder("^");
    int i = 0;
    while (m.find()) {
      b.append(Pattern.quote(path.substring(i, m.start()))).append("([A-Za-z0-9_.:-]{1,200})");
      names.add(m.group(1));
      i = m.end();
    }
    b.append(Pattern.quote(path.substring(i))).append("$");
    return new Routes(
        method, path, op, contract, Pattern.compile(b.toString()), List.copyOf(names));
  }

  static final List<Routes> ALL = build();

  static List<Routes> build() {
    var r = new ArrayList<Routes>();
    String[][] identity = {
      {"GET", "/v1/identity-providers", "identity-providers"},
      {"GET", "/v1/principals", "principals"},
      {"POST", "/v1/principals", "principal-create"},
      {"POST", "/v1/principals/{id}:set-status", "principal-status"},
      {"GET", "/v1/owner-candidates", "owner-candidates"},
      {"POST", "/v1/ownership:transfer", "ownership-transfer"},
      {"GET", "/v1/memberships", "memberships"},
      {"POST", "/v1/memberships", "membership-create"},
      {"POST", "/v1/memberships/{id}:revoke", "membership-revoke"}
    };
    String[][] catalog = {
      {"GET", "/v1/customer-journeys", "journeys"},
      {"GET", "/v1/customer-journeys/{id}", "journey"},
      {"POST", "/v1/customer-journeys", "journey-create"},
      {"POST", "/v1/customer-journeys/{id}/steps", "step-create"},
      {"POST", "/v1/customer-journeys/{id}/capabilities", "journey-link-create"},
      {"GET", "/v1/services", "services"},
      {"GET", "/v1/shared-resources", "resources"},
      {"GET", "/v1/shared-resources/{id}", "resource"},
      {"POST", "/v1/shared-resources", "resource-create"},
      {"POST", "/v1/shared-resources/{id}/consumers", "consumer-create"}
    };
    for (var x : identity) r.add(of(x[0], x[1], x[2], "identity_design/openapi.proposed.json"));
    for (var x : catalog) r.add(of(x[0], x[1], x[2], "catalog_contracts/openapi.json"));
    for (String action : List.of("update", "retire")) {
      r.add(
          of(
              "POST",
              "/v1/customer-journeys/{id}/capabilities/{link_id}:" + action,
              "journey-link-" + action,
              "relationship_design/openapi.proposed.json"));
      r.add(
          of(
              "POST",
              "/v1/shared-resources/{id}/consumers/{link_id}:" + action,
              "consumer-" + action,
              "relationship_design/openapi.proposed.json"));
    }
    r.add(
        of(
            "GET",
            "/v1/graphs/revisions/{revision}",
            "graph-revision",
            "api_reference/core.openapi.json"));
    for (String action : List.of("dependencies", "impact", "recovery-independence"))
      r.add(
          of(
              "GET",
              "/v1/graphs/revisions/{revision}/" + action,
              action.equals("recovery-independence") ? "graph-independence" : "graph-" + action,
              "graph_design/openapi.proposed.json"));
    r.add(
        of(
            "POST",
            "/v1/recovery-assessments",
            "assurance-create",
            "assurance_design/openapi.proposed.json"));
    r.add(
        of(
            "GET",
            "/v1/recovery-assessments/{id}",
            "assurance-get",
            "assurance_design/openapi.proposed.json"));
    r.add(
        of(
            "POST",
            "/v1/recovery-assessments/{id}:reassess",
            "assurance-review",
            "assurance_design/openapi.proposed.json"));
    return List.copyOf(r);
  }
}
