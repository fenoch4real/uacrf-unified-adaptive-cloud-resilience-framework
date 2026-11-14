package org.uacrf.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
final class Api {
  final Config cfg;
  final Database db;
  final Contracts contracts;
  final Identity.Source source;
  final Cursors cursors;
  final GraphService graphs;

  Api(Config cfg, Database db, Contracts contracts, Identity.Source source, GraphService graphs) {
    this.cfg = cfg;
    this.graphs = graphs;
    this.db = db;
    this.contracts = contracts;
    this.source = source;
    this.cursors = new Cursors(cfg.cursorKey());
  }

  @RequestMapping(value = "/**")
  ResponseEntity<byte[]> handle(HttpServletRequest request) {
    try {
      if (request.getContentLengthLong() > 65536
          || Collections.list(request.getHeaders("Content-Length")).size() > 1
          || request.getHeader("Transfer-Encoding") != null)
        throw new Problem(400, "Invalid request framing");
      byte[] raw = request.getInputStream().readNBytes(65537);
      if (raw.length > 65536) throw new Problem(400, "Request too large");
      Routes route =
          Routes.ALL.stream()
              .filter(
                  r ->
                      r.method().equals(request.getMethod())
                          && r.regex().matcher(request.getRequestURI()).matches())
              .findFirst()
              .orElseThrow(() -> new Problem(404, "Route not implemented in this runtime slice"));
      String auth = request.getHeader("Authorization");
      if (auth == null
          || !auth.startsWith("Bearer ")
          || Collections.list(request.getHeaders("Authorization")).size() != 1)
        throw new Problem(401, "Bearer identity required");
      ObjectNode claims =
          Identity.verify(auth.substring(7), cfg.issuer(), cfg.audience(), cfg.keys(), 900);
      JsonNode op = contracts.operation(route);
      Map<String, String> query = parseQuery(request.getQueryString());
      Map<String, JsonNode> declarations = new HashMap<>();
      for (var p : op.path("parameters"))
        if (p.path("in").asText().equals("query")) declarations.put(p.path("name").asText(), p);
      if (!declarations.keySet().containsAll(query.keySet()))
        throw new Problem(400, "Unknown query parameter");
      ObjectNode typed = Json.M.createObjectNode();
      for (var e : declarations.entrySet()) {
        String name = e.getKey();
        var p = e.getValue();
        if (!query.containsKey(name)) {
          if (p.path("required").asBoolean()) throw new Problem(400, "Required query field absent");
          continue;
        }
        String value = query.get(name);
        JsonNode schema = p.path("schema"), v;
        if (schema.path("type").asText().equals("integer")) {
          if (!value.matches("[0-9]{1,9}")) throw new Problem(400, "Invalid integer query");
          v = Json.M.getNodeFactory().numberNode(Integer.parseInt(value));
        } else if (schema.path("type").asText().equals("boolean")) {
          if (!Set.of("true", "false").contains(value))
            throw new Problem(400, "Invalid boolean query");
          v = Json.M.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
        } else v = Json.M.getNodeFactory().textNode(value);
        if (!contracts.valid(schema, v, route.contract()))
          throw new Problem(400, "Invalid query field");
        typed.set(name, v);
      }
      ObjectNode body = Json.M.createObjectNode();
      if (route.method().equals("POST")) {
        if (request.getContentType() == null
            || !request.getContentType().split(";", 2)[0].equals("application/json"))
          throw new Problem(400, "JSON content type required");
        JsonNode parsed = Json.read(new String(raw, StandardCharsets.UTF_8));
        if (!(parsed instanceof ObjectNode n)) throw new Problem(422, "Request contract rejected");
        body = n;
        if (!contracts.valid(
            op.path("requestBody").path("content").path("application/json").path("schema"),
            body,
            route.contract())) throw new Problem(422, "Request contract rejected");
      } else if (raw.length > 0) throw new Problem(400, "Read requests have no body");
      ObjectNode path = Json.M.createObjectNode();
      var matcher = route.regex().matcher(request.getRequestURI());
      matcher.matches();
      for (int i = 0; i < route.names().size(); i++)
        path.put(route.names().get(i), matcher.group(i + 1));
      ObjectNode params = path.deepCopy(), filters = Json.M.createObjectNode();
      typed
          .fields()
          .forEachRemaining(
              e -> {
                if (!e.getKey().contains("cursor")) {
                  params.set(e.getKey(), e.getValue());
                  filters.set(e.getKey(), e.getValue());
                }
              });
      params
          .put("provider_id", cfg.providerId())
          .put("provider_name", cfg.providerName())
          .put("limit", typed.path("limit").asInt(typed.path("page_size").asInt(25)));
      // Binding is private to this Java runtime; a language cutover invalidates old cursors safely.
      String binding =
          Json.text(
              List.of(
                  cfg.tenant(),
                  cfg.environment(),
                  claims.path("iss").asText(),
                  claims.path("sub").asText(),
                  route.path(),
                  sorted(path),
                  sorted(filters)));
      Map<String, String> positions =
          Map.of(
              "cursor",
              "after",
              "step_cursor",
              "step_after",
              "capability_cursor",
              "capability_after",
              "consumer_cursor",
              "consumer_after");
      for (var e : positions.entrySet())
        if (typed.has(e.getKey())) {
          var c = cursors.read(typed.path(e.getKey()).asText(), binding + ":" + e.getKey());
          params.set(e.getValue(), c.path("after"));
          params.set("authority_digest", c.path("authority_digest"));
        }
      String key = route.method().equals("POST") ? request.getHeader("Idempotency-Key") : null;
      if (route.method().equals("POST")
          && (key == null
              || key.isEmpty()
              || key.length() > 200
              || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1))
        throw new Problem(400, "Bounded Idempotency-Key required");
      if (route.operation().startsWith("assurance-")) {
        ObjectNode result =
            new Assurance(graphs)
                .handle(claims, route.operation(), params.path("id").asText(), body, key);
        if (!contracts.valid(
            op.path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema"),
            result,
            route.contract())) throw new Problem(503, "Assurance response contract violation");
        return response(200, result);
      }
      if (route.operation().startsWith("graph-")) {
        ObjectNode result =
            graphs.reply(claims, params.path("revision").asText(), route.operation(), params);
        JsonNode schema =
            op.path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema");
        if (!contracts.valid(schema, result, route.contract()))
          throw new Problem(503, "Graph response contract violation");
        return response(200, result);
      }
      ObjectNode assertion = null, reply = null;
      if (Set.of("principal-create", "principal-status", "membership-create")
          .contains(route.operation())) {
        ObjectNode pre = params.deepCopy().put("preflight", "true");
        var preflight = db.call(claims, route.operation(), body, pre, key, null);
        if (preflight.path("replayed").asBoolean()) reply = preflight;
        else if (route.operation().equals("principal-create")) {
          if (!cfg.providerId().equals(body.path("provider_id").asText()))
            throw new Problem(422, "Unconfigured identity provider");
          assertion = source.verified("identity", body.path("directory_subject_ref").asText());
        } else if (route.operation().equals("membership-create"))
          assertion = source.verified("grant", body.path("authorization_ref").asText());
        else if (body.path("enabled").asBoolean())
          assertion = source.verified("review", body.path("grant_review_ref").asText());
      }
      if (reply == null) reply = db.call(claims, route.operation(), body, params, key, assertion);
      int status = reply.path("status").asInt();
      JsonNode result = reply.path("body");
      String authority = reply.path("authority_digest").asText();
      if (result instanceof ObjectNode n) {
        mint(n, "cursor", authority, binding);
        var pages = n.path("related").fields();
        while (pages.hasNext()) {
          var e = pages.next();
          if (e.getValue() instanceof ObjectNode page)
            mint(
                page,
                Map.of(
                        "steps",
                        "step_cursor",
                        "capabilities",
                        "capability_cursor",
                        "consumers",
                        "consumer_cursor")
                    .get(e.getKey()),
                authority,
                binding);
        }
      }
      JsonNode schema =
          op.path("responses")
              .path(Integer.toString(status))
              .path("content")
              .path("application/json")
              .path("schema");
      if (schema.isMissingNode() || !contracts.valid(schema, result, route.contract()))
        throw new Problem(503, "Response contract violation");
      return response(status, result);
    } catch (Problem p) {
      return response(
          p.status,
          Map.of(
              "code",
              "HTTP_" + p.status,
              "message",
              p.getMessage(),
              "request_id",
              UUID.randomUUID().toString()));
    } catch (Exception e) {
      return response(
          503,
          Map.of(
              "code",
              "HTTP_503",
              "message",
              "Runtime boundary unavailable",
              "request_id",
              UUID.randomUUID().toString()));
    }
  }

  static Map<String, JsonNode> sorted(ObjectNode n) {
    Map<String, JsonNode> out = new TreeMap<>();
    n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) return out;
    try {
      for (String part : query.split("&", -1)) {
        String[] pair = part.split("=", 2);
        String k = URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
            v = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
        if (out.putIfAbsent(k, v) != null) throw new Problem(400, "Duplicate query parameter");
      }
      return out;
    } catch (IllegalArgumentException e) {
      throw new Problem(400, "Malformed query string");
    }
  }

  void mint(ObjectNode page, String cursor, String authority, String binding) {
    if (page.hasNonNull("next_cursor"))
      page.put(
          "next_cursor",
          cursors.mint(page.path("next_cursor").asText(), authority, binding + ":" + cursor));
  }

  static ResponseEntity<byte[]> response(int status, Object body) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(Json.text(body).getBytes(StandardCharsets.UTF_8));
  }
}
