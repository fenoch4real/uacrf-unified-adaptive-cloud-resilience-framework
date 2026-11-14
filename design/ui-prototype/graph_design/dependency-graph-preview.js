const uacrfGraphFixture = window.UACRF_GRAPH_SAMPLE;
if (!uacrfGraphFixture) throw new Error("The illustrative dependency graph fixture did not load.");

let uacrfGraphView = "service";
let uacrfGraphScreen = null;
let uacrfGraphSelection = null;

const uacrfGraphStyles = document.createElement("style");
uacrfGraphStyles.textContent = `
  .sample-graph-card{margin:18px 0;padding:20px;border:1px solid #dce5ef;border-radius:12px;background:#fff}
  .sample-graph-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;flex-wrap:wrap}
  .sample-graph-heading h2{margin:0 0 6px}
  .sample-graph-scope{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0}
  .sample-graph-scope button[aria-pressed=true]{background:#145d93;color:#fff;border-color:#145d93}
  .sample-graph-warning{padding:10px 12px;border-left:4px solid #d89621;background:#fff8e8;color:#654712;border-radius:4px}
  .sample-graph-layout{display:grid;grid-template-columns:minmax(0,3fr) minmax(250px,1fr);gap:14px;align-items:start;min-width:0}
  .sample-graph-layout.combined{grid-template-columns:minmax(0,1fr)}
  .sample-graph-layout>div,.sample-graph-canvas{min-width:0;max-width:100%}
  .sample-graph-canvas{overflow:auto;border:1px solid #e1e9f1;border-radius:8px;background:#f8fbff}
  .sample-graph-canvas svg{display:block;width:100%;height:auto;min-width:780px}
  .sample-graph-edge{stroke:#6f88a3;stroke-width:2;fill:none;pointer-events:none}
  .sample-graph-edge-hit{stroke:transparent;stroke-width:14;fill:none;pointer-events:stroke}
  .sample-graph-edge-group{cursor:pointer}
  .sample-graph-edge-group.selected .sample-graph-edge{stroke:#0c7493;stroke-width:3}
  .sample-graph-edge-label{font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:#536d88;stroke:#f8fbff;stroke-width:5px;stroke-linejoin:round;paint-order:stroke;pointer-events:none}
  .sample-graph-node{cursor:pointer}
  .sample-graph-node rect{fill:#fff;stroke:#9db1c5;stroke-width:1.5}
  .sample-graph-node.service rect{fill:#eef6ff;stroke:#78a5d0}
  .sample-graph-node.agent rect{fill:#eff9f7;stroke:#58a99a}
  .sample-graph-node.resource rect{fill:#fff8eb;stroke:#d9ad5b}
  .sample-graph-node.root rect{stroke-width:2.5}
  .sample-graph-node.selected rect{stroke:#155f96;stroke-width:3}
  .sample-graph-node:focus{outline:none}
  .sample-graph-node:focus rect{stroke:#0b79bd;stroke-width:3}
  .sample-graph-node-title{font:600 14px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:#17334c}
  .sample-graph-node-subtitle{font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:#667d95}
  .sample-graph-inspector{padding:16px;border:1px solid #dce5ef;border-radius:8px;background:#fff}
  .sample-graph-inspector h3{margin-top:0}
  .sample-graph-inspector dl{display:grid;grid-template-columns:1fr 1.2fr;gap:8px;font-size:13px}
  .sample-graph-inspector dt{color:#667d95}
  .sample-graph-inspector dd{margin:0;overflow-wrap:anywhere}
  .sample-graph-legend{display:flex;gap:16px;flex-wrap:wrap;margin-top:12px;font-size:13px}
  .sample-graph-key{display:inline-flex;align-items:center;gap:6px}
  .sample-graph-dot{width:12px;height:12px;border:1px solid #8299ae;border-radius:3px;background:#eef6ff}
  .sample-graph-dot.agent{background:#eff9f7;border-color:#58a99a}
  .sample-graph-dot.resource{background:#fff8eb;border-color:#d9ad5b}
  @media(max-width:850px){.sample-graph-layout{grid-template-columns:minmax(0,1fr)}}
`;
document.head.appendChild(uacrfGraphStyles);

function uacrfGraphNodeName(node) {
  return JSON.parse(node.properties.metadata_json).name;
}

function uacrfGraphNodeSubtitle(node) {
  const properties = node.properties;
  const metadata = JSON.parse(properties.metadata_json);
  if (properties.workload_kind === "AGENT") return "Protected agent";
  if (properties.workload_kind === "SERVICE") return "Service workload";
  return [metadata.provider, metadata.region].filter(Boolean).join(" · ");
}

function uacrfGraphNodeClass(node) {
  if (node.properties.workload_kind === "AGENT") return "agent";
  if (node.properties.workload_kind === "SERVICE") return "service";
  return "resource";
}

function uacrfGraphPosition(nodeId, positions) {
  const position = positions[nodeId];
  if (!position) throw new Error(`The graph fixture has no position for ${nodeId}.`);
  return position;
}

function uacrfGraphLine(edge, positions) {
  const [sx, sy] = uacrfGraphPosition(edge.source_id, positions);
  const [tx, ty] = uacrfGraphPosition(edge.target_id, positions);
  const sourceX = sx + 105;
  const sourceY = sy + 38;
  const targetX = tx + 105;
  const targetY = ty + 38;
  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const length = Math.max(Math.hypot(dx, dy), 1);
  const ux = dx / length;
  const uy = dy / length;
  const sourceOffset = Math.min(105 / Math.max(Math.abs(ux), 0.01), 38 / Math.max(Math.abs(uy), 0.01));
  const targetOffset = sourceOffset;
  const x1 = sourceX + ux * sourceOffset;
  const y1 = sourceY + uy * sourceOffset;
  const x2 = targetX - ux * targetOffset;
  const y2 = targetY - uy * targetOffset;
  const label = { CALLS: "API", READS_FROM: "data", DEPENDS_ON: "depends", RUNS_ON: "runs on", USES_MODEL: "model", USES_IDENTITY: "identity", USES_KEY: "key" }[edge.type] || edge.type.toLowerCase();
  return `<g class="sample-graph-edge-group${uacrfGraphSelection === edge.relationship_id ? " selected" : ""}" data-sample-edge="${esc(edge.relationship_id)}" tabindex="0" role="button" aria-label="${esc(uacrfGraphNodeName(uacrfGraphFixture.apiResponse.nodes.find(n => n.canonical_id === edge.source_id)))} ${esc(label)} ${esc(uacrfGraphNodeName(uacrfGraphFixture.apiResponse.nodes.find(n => n.canonical_id === edge.target_id)))}"><title>${esc(label)}</title><path class="sample-graph-edge-hit" d="M${x1},${y1} L${x2},${y2}"/><path class="sample-graph-edge" d="M${x1},${y1} L${x2},${y2}" marker-end="url(#uacrf-graph-arrow)"/><text class="sample-graph-edge-label" x="${(x1 + x2) / 2}" y="${(y1 + y2) / 2 - 7}" text-anchor="middle">${esc(label)}</text></g>`;
}

function uacrfGraphCard(node, position, selectedId, rootIds) {
  const [x, y] = position;
  const name = uacrfGraphNodeName(node);
  const classes = ["sample-graph-node", uacrfGraphNodeClass(node), rootIds.includes(node.canonical_id) ? "root" : "", selectedId === node.canonical_id ? "selected" : ""].filter(Boolean).join(" ");
  return `<g class="${classes}" data-sample-node="${esc(node.canonical_id)}" tabindex="0" role="button" aria-label="${esc(name)}, ${esc(uacrfGraphNodeSubtitle(node))}"><rect x="${x}" y="${y}" width="210" height="76" rx="9"/><text class="sample-graph-node-title" x="${x + 12}" y="${y + 29}">${esc(name)}</text><text class="sample-graph-node-subtitle" x="${x + 12}" y="${y + 52}">${esc(uacrfGraphNodeSubtitle(node))}</text></g>`;
}

function uacrfGraphDetails(view, node, edge) {
  if (edge) {
    const source = uacrfGraphFixture.apiResponse.nodes.find(n => n.canonical_id === edge.source_id);
    const target = uacrfGraphFixture.apiResponse.nodes.find(n => n.canonical_id === edge.target_id);
    return `<h3>Dependency relationship</h3><p><strong>${esc(uacrfGraphNodeName(source))}</strong> ${esc(edge.type.replaceAll("_", " ").toLowerCase())} <strong>${esc(uacrfGraphNodeName(target))}</strong></p><dl><dt>Source</dt><dd>${esc(edge.properties.qualification_id)}</dd><dt>Confidence</dt><dd>${Math.round(edge.properties.confidence * 100)}% · illustrative</dd><dt>Evidence</dt><dd>Not verified</dd></dl>`;
  }
  const metadata = JSON.parse(node.properties.metadata_json);
  return `<h3>${esc(uacrfGraphNodeName(node))}</h3><dl><dt>Type</dt><dd>${esc(uacrfGraphNodeSubtitle(node))}</dd><dt>Provider</dt><dd>${esc(metadata.provider || "Not applicable")}</dd><dt>Region</dt><dd>${esc(metadata.region || "Not specified")}</dd><dt>Reference</dt><dd>${esc(node.properties.qualification_id)}</dd><dt>Evidence</dt><dd>Illustrative only · not qualified</dd></dl>`;
}

function uacrfRenderGraphSample() {
  const view = uacrfGraphFixture.views[uacrfGraphView];
  const graph = uacrfGraphFixture.apiResponse;
  const nodeIds = new Set(view.node_ids);
  const nodes = graph.nodes.filter(node => nodeIds.has(node.canonical_id));
  const edges = graph.edges.filter(edge => nodeIds.has(edge.source_id) && nodeIds.has(edge.target_id));
  if (!nodes.length || nodes.length !== view.node_ids.length) throw new Error(`The ${uacrfGraphView} graph sample references missing nodes.`);
  const selectedEdge = edges.find(edge => edge.relationship_id === uacrfGraphSelection);
  const selectedNode = nodes.find(node => node.canonical_id === uacrfGraphSelection) || (!selectedEdge ? nodes.find(node => view.root_ids.includes(node.canonical_id)) || nodes[0] : null);
  const positions = view.positions;
  const width = uacrfGraphView === "both" ? 1100 : 930;
  const height = uacrfGraphView === "both" ? 650 : 570;
  const layoutClass = `sample-graph-layout${uacrfGraphView === "both" ? " combined" : ""}`;
  return `<article class="sample-graph-card" id="sample-graph-card"><div class="sample-graph-heading"><div><h2>Dependency graph sample</h2><p>Northstar Payments · AWS us-east-1 · Fictional example</p></div><span class="badge">Illustrative · Not connected</span></div><p class="sample-graph-warning">${esc(uacrfGraphFixture.notice)} Do not treat this graph or its evidence references as provider observations.</p><div class="sample-graph-scope" role="group" aria-label="Choose protected workload scope">${Object.entries(uacrfGraphFixture.views).map(([key, option]) => `<button type="button" data-graph-view="${key}" aria-pressed="${key === uacrfGraphView}">${esc(option.label)}</button>`).join("")}</div><p class="smallnote">Each view starts from a service, an agent, or both. Its connected service and cloud-resource dependencies remain visible.</p><div class="${layoutClass}"><div><div class="sample-graph-canvas"><svg id="sample-graph-canvas" style="min-width:${uacrfGraphView === "both" ? "1100px" : "930px"}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(view.label)} dependency graph for fictional Northstar Payments"><title>${esc(view.label)} dependency graph</title><defs><marker id="uacrf-graph-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#6f88a3"/></marker></defs>${edges.map(edge => uacrfGraphLine(edge, positions)).join("")}${nodes.map(node => uacrfGraphCard(node, uacrfGraphPosition(node.canonical_id, positions), selectedNode?.canonical_id, view.root_ids)).join("")}</svg></div><div class="sample-graph-legend"><span class="sample-graph-key"><i class="sample-graph-dot"></i>Service</span><span class="sample-graph-key"><i class="sample-graph-dot agent"></i>Protected agent</span><span class="sample-graph-key"><i class="sample-graph-dot resource"></i>Cloud or shared resource</span><span>Arrow direction follows the declared dependency.</span></div><p class="smallnote">Select a node or relationship for its details.</p></div><aside class="sample-graph-inspector" id="sample-graph-details">${uacrfGraphDetails(view, selectedNode, selectedEdge)}</aside></div><p class="smallnote">The graph API returns a specific authorized, accepted revision. Cloud discovery belongs to S03, evidence qualification to S04, and graph acceptance and projection to S06. This preview makes no provider call and does not imply a verified dependency.</p><div class="tools"><a href="#UI-02">Service inventory</a><a href="#UI-34">Agent inventory</a><a href="#UI-47">Customer journeys</a><a href="../../Microservices_Architecture/MS01/contracts/source/graph_design/openapi.proposed.json">S06 graph API contract</a><a href="../graph_design/README.md">Graph integration guide</a></div></article>`;
}

function uacrfBindGraphSample() {
  document.querySelectorAll("[data-graph-view]").forEach(button => {
    button.onclick = () => {
      uacrfGraphView = button.getAttribute("data-graph-view");
      uacrfGraphSelection = null;
      draw();
    };
  });
  const canvas = document.querySelector("#sample-graph-canvas");
  if (!canvas) return;
  const inspect = event => {
    const node = event.target.closest("[data-sample-node]");
    const edge = event.target.closest("[data-sample-edge]");
    const item = node || edge;
    if (!item || (event.type === "keydown" && !["Enter", " "].includes(event.key))) return;
    if (event.type === "keydown") event.preventDefault();
    uacrfGraphSelection = item.getAttribute(node ? "data-sample-node" : "data-sample-edge");
    const details = document.querySelector("#sample-graph-details");
    details.innerHTML = node
      ? uacrfGraphDetails(uacrfGraphFixture.views[uacrfGraphView], uacrfGraphFixture.apiResponse.nodes.find(entry => entry.canonical_id === uacrfGraphSelection), null)
      : uacrfGraphDetails(uacrfGraphFixture.views[uacrfGraphView], null, uacrfGraphFixture.apiResponse.edges.find(entry => entry.relationship_id === uacrfGraphSelection));
    canvas.querySelectorAll("[data-sample-node]").forEach(entry => entry.classList.toggle("selected", entry.getAttribute("data-sample-node") === uacrfGraphSelection));
    canvas.querySelectorAll("[data-sample-edge]").forEach(entry => entry.classList.toggle("selected", entry.getAttribute("data-sample-edge") === uacrfGraphSelection));
  };
  canvas.onclick = inspect;
  canvas.onkeydown = inspect;
}

const uacrfOriginalRelationshipWorkspace = relationshipWorkspace;
relationshipWorkspace = function () {
  const workspace = uacrfOriginalRelationshipWorkspace();
  return relationshipScope() === "dependency" ? uacrfRenderGraphSample() + workspace : workspace;
};

const uacrfOriginalDraw = draw;
draw = function () {
  if (!["UI-18", "UI-37"].includes(selected)) {
    uacrfGraphScreen = null;
  } else if (uacrfGraphScreen !== selected) {
    uacrfGraphScreen = selected;
    uacrfGraphView = selected === "UI-37" ? "agent" : "service";
  }
  uacrfOriginalDraw();
  if (relationshipScope() === "dependency") uacrfBindGraphSample();
};

draw();
