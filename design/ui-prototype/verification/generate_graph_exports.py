#!/usr/bin/env python3
"""Export the fictional dependency-graph fixture as static SVGs and embed it in the UI-18 and UI-37 screen illustrations.

The fixture (UI/graph_design/sample-topologies.js) stays the single source of truth, so the interactive prototype,
the gallery images and the screen illustrations show identical data. Re-run after changing the fixture:

    python3 UI/verification/generate_graph_exports.py
"""
import json
import math
import re
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "graph_design" / "sample-topologies.js"
GRAPHS = ROOT / "ui_workloads" / "graphs"
SCREENS = ROOT / "ui_workloads" / "screens"
MARKER = "<!-- uacrf-graph-sample -->"
BASE_HEIGHT = 1590
FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif"
LABELS = {"CALLS": "API", "READS_FROM": "data", "DEPENDS_ON": "depends", "RUNS_ON": "runs on",
          "USES_MODEL": "model", "USES_IDENTITY": "identity", "USES_KEY": "key"}
COLORS = {"service": ("#eef6ff", "#78a5d0"), "agent": ("#eff9f7", "#58a99a"), "resource": ("#fff8eb", "#d9ad5b")}
SCREEN_SCOPE = {"UI-18": "service", "UI-37": "agent"}


def load_fixture():
    text = FIXTURE.read_text()
    body = text.split("=", 1)[1].strip().rstrip(";")
    body = re.sub(r'([{,]\s*)([A-Za-z_]\w*)\s*:', r'\1"\2":', body)
    return json.loads(body)


def name_of(node):
    return json.loads(node["properties"]["metadata_json"])["name"]


def kind_of(node):
    return {"AGENT": "agent", "SERVICE": "service"}.get(node["properties"]["workload_kind"], "resource")


def subtitle_of(node):
    kind = node["properties"]["workload_kind"]
    if kind == "AGENT":
        return "Protected agent"
    if kind == "SERVICE":
        return "Service workload"
    meta = json.loads(node["properties"]["metadata_json"])
    return " · ".join(filter(None, [meta.get("provider"), meta.get("region")]))


def edge_markup(edge, positions):
    sx, sy = positions[edge["source_id"]]
    tx, ty = positions[edge["target_id"]]
    cx1, cy1, cx2, cy2 = sx + 105, sy + 38, tx + 105, ty + 38
    dx, dy = cx2 - cx1, cy2 - cy1
    length = max(math.hypot(dx, dy), 1)
    ux, uy = dx / length, dy / length
    offset = min(105 / max(abs(ux), 0.01), 38 / max(abs(uy), 0.01))
    x1, y1, x2, y2 = cx1 + ux * offset, cy1 + uy * offset, cx2 - ux * offset, cy2 - uy * offset
    label = LABELS.get(edge["type"], edge["type"].lower())
    return (f'<path d="M{x1:.1f},{y1:.1f} L{x2:.1f},{y2:.1f}" stroke="#6f88a3" stroke-width="2" fill="none" marker-end="url(#arrow)"/>'
            f'<text x="{(x1 + x2) / 2:.1f}" y="{(y1 + y2) / 2 - 7:.1f}" text-anchor="middle" font-family="{FONT}" font-size="12" '
            f'fill="#536d88" stroke="#f8fbff" stroke-width="5" stroke-linejoin="round" paint-order="stroke">{escape(label)}</text>')


def node_markup(node, position, roots):
    x, y = position
    fill, stroke = COLORS[kind_of(node)]
    width = "2.5" if node["canonical_id"] in roots else "1.5"
    return (f'<rect x="{x}" y="{y}" width="210" height="76" rx="9" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>'
            f'<text x="{x + 12}" y="{y + 29}" font-family="{FONT}" font-size="14" font-weight="600" fill="#17334c">{escape(name_of(node))}</text>'
            f'<text x="{x + 12}" y="{y + 52}" font-family="{FONT}" font-size="12" fill="#667d95">{escape(subtitle_of(node))}</text>')


def graph_body(fixture, scope):
    view = fixture["views"][scope]
    graph = fixture["apiResponse"]
    ids = set(view["node_ids"])
    nodes = [n for n in graph["nodes"] if n["canonical_id"] in ids]
    edges = [e for e in graph["edges"] if e["source_id"] in ids and e["target_id"] in ids]
    positions = view["positions"]
    body = "".join(edge_markup(e, positions) for e in edges)
    body += "".join(node_markup(n, positions[n["canonical_id"]], view["root_ids"]) for n in nodes)
    return view, body


def legend(x, y):
    items = [("service", "Service"), ("agent", "Protected agent"), ("resource", "Cloud or shared resource")]
    out, cursor = "", x
    for kind, text in items:
        fill, stroke = COLORS[kind]
        out += (f'<rect x="{cursor}" y="{y}" width="14" height="14" rx="3" fill="{fill}" stroke="{stroke}"/>'
                f'<text x="{cursor + 22}" y="{y + 12}" font-family="{FONT}" font-size="13" fill="#193651">{text}</text>')
        cursor += 40 + len(text) * 7.4
    return out + f'<text x="{cursor}" y="{y + 12}" font-family="{FONT}" font-size="13" fill="#526d82">Arrows follow the declared dependency.</text>'


def standalone_svg(fixture, scope):
    view, body = graph_body(fixture, scope)
    width, height = (1100, 650) if scope == "both" else (930, 570)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height + 70}" viewBox="0 0 {width} {height + 70}" role="img" '
            f'aria-label="{escape(view["label"])} dependency graph for fictional Northstar Payments (illustrative, not connected)">'
            f'<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#6f88a3"/></marker></defs>'
            f'<rect width="{width}" height="{height + 70}" fill="#f8fbff"/>{body}'
            f'{legend(30, height + 22)}'
            f'<text x="30" y="{height + 52}" font-family="{FONT}" font-size="12" fill="#654712">Fictional, illustrative sample · not connected to a provider · not accepted evidence</text></svg>\n')


def screen_panel(fixture, scope, top):
    view, body = graph_body(fixture, scope)
    width, height = (1100, 650) if scope == "both" else (930, 570)
    panel_height = height + 260
    notice = escape(fixture["notice"])
    return panel_height, (
        f'{MARKER}<g font-family="Arial,sans-serif"><rect x="255" y="{top}" width="1290" height="{panel_height - 30}" rx="12" fill="#fff" stroke="#dce5ef"/>'
        f'<text x="285" y="{top + 42}" font-size="24" font-weight="700" fill="#193651">Dependency graph sample</text>'
        f'<text x="285" y="{top + 68}" font-size="15" fill="#526d82">Northstar Payments · AWS us-east-1 · {escape(view["label"])} · Fictional example</text>'
        f'<rect x="1290" y="{top + 24}" width="225" height="30" rx="15" fill="#fff8e8" stroke="#d89621"/>'
        f'<text x="1402" y="{top + 44}" font-size="13" text-anchor="middle" fill="#654712">Illustrative · Not connected</text>'
        f'<rect x="285" y="{top + 86}" width="1230" height="40" rx="5" fill="#fff8e8"/><rect x="285" y="{top + 86}" width="4" height="40" fill="#d89621"/>'
        f'<text x="305" y="{top + 111}" font-size="13" fill="#654712">{notice[:150]}</text>'
        f'<rect x="285" y="{top + 140}" width="{width}" height="{height}" rx="8" fill="#f8fbff" stroke="#e1e9f1"/>'
        f'<svg x="285" y="{top + 140}" width="{width}" height="{height}" viewBox="0 0 {width} {height}">'
        f'<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#6f88a3"/></marker></defs>{body}</svg>'
        f'<g transform="translate(0,{top + height + 168})">{legend(285, 0)}</g></g>')


def update_screen(fixture, screen, scope):
    path = SCREENS / f"{screen}.svg"
    svg = path.read_text()
    svg = svg.split(MARKER)[0]
    svg = re.sub(r"</svg>\s*$", "", svg)
    if svg.endswith("</g>"):
        pass
    svg = re.sub(r'(<svg [^>]*?)width="1600" height="\d+" viewBox="0 0 1600 \d+"', rf'\1width="1600" height="{BASE_HEIGHT}" viewBox="0 0 1600 {BASE_HEIGHT}"', svg, count=1)
    panel_height, panel = screen_panel(fixture, scope, BASE_HEIGHT)
    total = BASE_HEIGHT + panel_height
    svg = svg.replace(f'height="{BASE_HEIGHT}" viewBox="0 0 1600 {BASE_HEIGHT}"', f'height="{total}" viewBox="0 0 1600 {total}"', 1)
    svg = svg.replace(f'<rect width="1600" height="{BASE_HEIGHT}" fill="#f6f8fc"/>', f'<rect width="1600" height="{total}" fill="#f6f8fc"/>', 1)
    svg = svg.replace(f'<rect width="225" height="{BASE_HEIGHT}" fill="#09243d"/>', f'<rect width="225" height="{total}" fill="#09243d"/>', 1)
    path.write_text(svg + panel + "</svg>\n")


def main():
    fixture = load_fixture()
    GRAPHS.mkdir(exist_ok=True)
    for scope in ("service", "agent", "both"):
        (GRAPHS / f"northstar-{scope}.svg").write_text(standalone_svg(fixture, scope))
    for screen, scope in SCREEN_SCOPE.items():
        update_screen(fixture, screen, scope)
    print("Exported 3 graph SVGs and updated", ", ".join(SCREEN_SCOPE))


if __name__ == "__main__":
    main()
