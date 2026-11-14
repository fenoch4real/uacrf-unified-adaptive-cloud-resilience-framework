package org.uacrf.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import java.nio.file.*;
import java.util.*;

final class Contracts {
  final Path root;
  final Map<String, JsonNode> docs = new HashMap<>();
  final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  final SchemaValidatorsConfig options =
      SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  Contracts(Path root) throws Exception {
    this.root = root.toRealPath();
    for (var r : Routes.ALL) load(this.root.resolve(r.contract()));
  }

  JsonNode load(Path p) throws Exception {
    p = p.toRealPath();
    if (!p.startsWith(root))
      throw new IllegalArgumentException("External schema reference forbidden");
    String k = p.toString();
    if (!docs.containsKey(k)) docs.put(k, Json.read(Files.readString(p)));
    return docs.get(k);
  }

  JsonNode operation(Routes r) {
    try {
      return load(root.resolve(r.contract()))
          .path("paths")
          .path(r.path())
          .path(r.method().toLowerCase(Locale.ROOT));
    } catch (Exception e) {
      throw new IllegalStateException("Contract unavailable", e);
    }
  }

  // Resolve only retained local schema references. No request can make the validator fetch a URL.
  JsonNode resolve(JsonNode n, Path file, Set<String> stack) throws Exception {
    if (n.isArray()) {
      var a = Json.M.createArrayNode();
      for (var x : n) a.add(resolve(x, file, stack));
      return a;
    }
    if (!n.isObject()) return n.deepCopy();
    ObjectNode out = Json.M.createObjectNode();
    if (n.has("$ref")) {
      String ref = n.path("$ref").asText();
      String[] parts = ref.split("#", 2);
      if (parts[0].contains(":")) throw new IllegalArgumentException("Remote references forbidden");
      Path target = parts[0].isEmpty() ? file : file.getParent().resolve(parts[0]).normalize();
      String id = target + "#" + (parts.length > 1 ? parts[1] : "");
      if (!stack.add(id))
        throw new IllegalArgumentException(
            "Recursive schema not supported by this bounded contract set");
      JsonNode v = load(target);
      if (parts.length > 1 && !parts[1].isEmpty()) v = v.at(parts[1]);
      if (v.isMissingNode()) throw new IllegalArgumentException("Missing schema pointer");
      JsonNode resolved = resolve(v, target, stack);
      stack.remove(id);
      if (resolved.isBoolean() && resolved.asBoolean()) {
      } else if (resolved.isObject()) out.setAll((ObjectNode) resolved);
      else return resolved;
    }
    var fields = n.fields();
    while (fields.hasNext()) {
      var e = fields.next();
      if (!Set.of("$ref", "$id", "$schema").contains(e.getKey()))
        out.set(e.getKey(), resolve(e.getValue(), file, new HashSet<>(stack)));
    }
    return out;
  }

  boolean valid(JsonNode schema, JsonNode value, String file) {
    try {
      return factory
          .getSchema(resolve(schema, root.resolve(file), new HashSet<>()), options)
          .validate(value)
          .isEmpty();
    } catch (Exception e) {
      throw new IllegalStateException("Contract schema could not be loaded", e);
    }
  }

  void verifyAll() {
    for (var route : Routes.ALL) {
      var op = operation(route);
      if (!op.isObject()) throw new IllegalStateException("Missing route contract");
      if (route.method().equals("POST"))
        valid(
            op.path("requestBody").path("content").path("application/json").path("schema"),
            Json.M.createObjectNode(),
            route.contract());
    }
  }
}
