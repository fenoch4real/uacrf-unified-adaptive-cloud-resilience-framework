const fs = require("fs");
const vm = require("vm");
const assert = require("assert");
const path = require("path");

const uiRoot = path.join(__dirname, "../ui_workloads");
const html = fs.readFileSync(path.join(uiRoot, "index.html"), "utf8");
const fixtureSource = fs.readFileSync(path.join(__dirname, "../graph_design/sample-topologies.js"), "utf8");
const renderer = fs.readFileSync(path.join(__dirname, "../graph_design/dependency-graph-preview.js"), "utf8");
const screenRegister = JSON.parse(fs.readFileSync(path.join(uiRoot, "SCREEN_REGISTER.json"), "utf8"));
const actionRegister = JSON.parse(fs.readFileSync(path.join(uiRoot, "ACTION_REGISTER.json"), "utf8"));
const fixtureScope = { window: {} };
vm.createContext(fixtureScope);
vm.runInContext(fixtureSource, fixtureScope);

const fixture = fixtureScope.window.UACRF_GRAPH_SAMPLE;
const graph = fixture.apiResponse;
assert.deepStrictEqual(Object.keys(graph).sort(), [
  "completeness",
  "digest",
  "edges",
  "graph_revision_id",
  "nodes",
  "runtime_verification",
  "source_watermark",
  "state"
].sort());
assert.match(graph.digest, /^[a-f0-9]{64}$/);
assert.strictEqual(graph.state, "AVAILABLE");
assert.strictEqual(graph.runtime_verification, "NOT_ESTABLISHED");
const nodes = new Map(graph.nodes.map(node => [node.canonical_id, node]));
const edges = new Set(graph.edges.map(edge => edge.relationship_id));
assert.strictEqual(nodes.size, graph.nodes.length, "fixture node IDs must be unique");
assert.strictEqual(edges.size, graph.edges.length, "fixture edge IDs must be unique");
const allowedRelationships = new Set(["DEPENDS_ON", "CALLS", "READS_FROM", "WRITES_TO", "USES_MODEL", "USES_DATASET", "USES_IDENTITY", "USES_KEY", "USES_DNS", "RUNS_ON"]);
for (const edge of graph.edges) {
  assert(nodes.has(edge.source_id), `missing source node ${edge.source_id}`);
  assert(nodes.has(edge.target_id), `missing target node ${edge.target_id}`);
  assert(allowedRelationships.has(edge.type), `unsupported graph relation ${edge.type}`);
}
for (const [viewName, view] of Object.entries(fixture.views)) {
  assert(view.node_ids.length > 0, `${viewName} scope must render nodes`);
  assert(view.root_ids.every(id => view.node_ids.includes(id)), `${viewName} roots must be visible`);
  assert(view.node_ids.every(id => nodes.has(id)), `${viewName} scope must use API response nodes`);
  assert(view.node_ids.every(id => Array.isArray(view.positions[id])), `${viewName} nodes need display positions`);
}

for (const screenId of ["UI-18", "UI-37"]) {
  const screen = screenRegister.screens.find(entry => entry.id === screenId);
  assert(screen, `${screenId} must remain registered`);
  assert(screen.api_reference.endsWith("/graph_design/openapi.proposed.json"));
  assert(fs.existsSync(path.resolve(uiRoot, screen.api_reference)), `${screenId} contract reference must resolve`);
}
const serviceAction = actionRegister.actions.find(entry => entry.screen === "UI-18");
assert.strictEqual(serviceAction.endpoint, "GET /v1/graphs/revisions/{revision}/dependencies");
assert(html.includes('src="../graph_design/sample-topologies.js"'));
assert(html.includes('src="../graph_design/dependency-graph-preview.js"'));
assert.deepStrictEqual(Object.values(fixture.views).map(view => view.label), [
  "Service view",
  "Agent view",
  "Services + agents"
]);
assert(renderer.includes("Do not treat this graph or its evidence references as provider observations."));

console.log(JSON.stringify({
  status: "PASS_FIXTURE_AND_REGISTRY",
  nodes: graph.nodes.length,
  edges: graph.edges.length,
  scopes: Object.keys(fixture.views),
  screens: ["UI-18", "UI-37"],
  scope: "Fixture, navigation contract references, and API route consistency; browser interaction acceptance is separate"
}));
