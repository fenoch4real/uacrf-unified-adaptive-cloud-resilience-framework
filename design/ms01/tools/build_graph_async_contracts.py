import argparse
import json
from pathlib import Path


BASE = Path(__file__).resolve().parents[1]
API_FILES = (
    BASE / "contracts/source/api_reference/openapi.json",
    BASE / "contracts/source/api_reference/core.openapi.json",
)

COMMAND_STATUSES = [
    "ACCEPTED",
    "RUNNING",
    "WAITING_FOR_DEPENDENCY",
    "RETRY_SCHEDULED",
    "SUCCEEDED",
    "REJECTED",
    "FAILED",
    "CANCELLED",
    "EXPIRED",
    "OUTCOME_UNKNOWN",
]
NONTERMINAL_STATUSES = [
    "ACCEPTED",
    "RUNNING",
    "WAITING_FOR_DEPENDENCY",
    "RETRY_SCHEDULED",
    "OUTCOME_UNKNOWN",
]
UNSUCCESSFUL_TERMINAL_STATUSES = [
    "REJECTED",
    "FAILED",
    "CANCELLED",
    "EXPIRED",
]
GRAPH_SELECTION_QUERY_IDS = [
    "action-relevant-dependencies.v1",
    "action-relevant-dependencies.v2",
    "action-relevant-dependencies.v3",
]


def command_status_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "command_id": {
                "type": "string",
                "minLength": 1,
                "maxLength": 200,
                "pattern": r"\S",
            },
            "status": {
                "enum": COMMAND_STATUSES,
                "description": (
                    "Durable command lifecycle. The 202 admission receipt says PENDING, "
                    "while the persisted command starts as ACCEPTED. The current worker "
                    "uses ACCEPTED, RUNNING, RETRY_SCHEDULED, SUCCEEDED, and FAILED; "
                    "the other allowed lifecycle values are retained for broader "
                    "reconciliation. OUTCOME_UNKNOWN is nonterminal."
                ),
            },
            "terminal": {
                "type": "boolean",
                "description": (
                    "True only for SUCCEEDED, REJECTED, FAILED, CANCELLED, or EXPIRED. "
                    "OUTCOME_UNKNOWN remains nonterminal pending reconciliation."
                ),
            },
            "domain_completion": {
                "type": "boolean",
                "description": (
                    "True only for SUCCEEDED. Admission and every non-success outcome "
                    "have domain_completion=false."
                ),
            },
            "advisory_result": {
                "$ref": "#/components/schemas/GraphAdvisoryResult",
            },
        },
        "required": [
            "command_id",
            "status",
            "terminal",
            "domain_completion",
        ],
        "allOf": [
            {
                "if": {
                    "properties": {"status": {"const": "SUCCEEDED"}},
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "terminal": {"const": True},
                        "domain_completion": {"const": True},
                    }
                },
            },
            {
                "if": {
                    "properties": {"status": {"enum": UNSUCCESSFUL_TERMINAL_STATUSES}},
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "terminal": {"const": True},
                        "domain_completion": {"const": False},
                    }
                },
            },
            {
                "if": {
                    "properties": {"status": {"enum": NONTERMINAL_STATUSES}},
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "terminal": {"const": False},
                        "domain_completion": {"const": False},
                    }
                },
            },
            {
                "if": {"required": ["advisory_result"]},
                "then": {
                    "properties": {"status": {"const": "SUCCEEDED"}}
                },
            },
        ],
    }


def graph_advisory_result_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "kind": {
                "enum": [
                    "ACTION_RELEVANT_SUBGRAPH",
                    "GRAPH_DIFFERENCE_CLASSIFICATION",
                ]
            },
            "completeness": {
                "enum": ["INCOMPLETE", "INDETERMINATE"],
                "description": (
                    "This slice does not represent the complete UACRF graph ontology. "
                    "Use INDETERMINATE when missing freshness policy or bindings prevent "
                    "a justified classification."
                ),
            },
            "result_digest": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
            "summary": {
                "type": "string",
                "minLength": 1,
                "maxLength": 2000,
                "pattern": r"\S",
            },
            "advisory_only": {"const": True},
            "nodes": {
                "type": "array",
                "items": {"$ref": "#/components/schemas/GraphAdvisoryNode"},
                "maxItems": 1000,
                "description": "Selection only; at most 1,000 bounded graph nodes.",
            },
            "edges": {
                "type": "array",
                "items": {"$ref": "#/components/schemas/GraphAdvisoryEdge"},
                "maxItems": 5000,
                "description": "Selection only; at most 5,000 existing graph edges.",
            },
            "unsupported_domains": {
                "type": "array",
                "items": {"type": "string", "minLength": 1, "maxLength": 256},
                "minItems": 1,
                "maxItems": 8,
                "description": "Fixed unsupported-domain labels for this bounded slice.",
            },
            "classification": {
                "enum": ["target-changing", "widening", "narrowing", "indeterminate"],
            },
            "selection_digest": {
                "type": "string",
                "pattern": "^[a-f0-9]{64}$",
            },
            "changes": {
                "type": "array",
                "items": {"$ref": "#/components/schemas/GraphAdvisoryChange"},
                "maxItems": 100,
                "description": "Difference classification only; at most 100 changes.",
            },
            "inventory_reconciliation": {
                "$ref": "#/components/schemas/GraphInventoryReconciliation",
            },
            "slo_objectives": {
                "$ref": "#/components/schemas/GraphSloObjectiveSnapshot",
                "description": (
                    "Selection only; v3 carries the point-in-time current S05 active-objective set "
                    "for the S07 case workload as owner-derived context, not graph edges."
                ),
            },
            "objective_set_change": {
                "$ref": "#/components/schemas/GraphObjectiveSetChange",
                "description": (
                    "Classification only; a changed objective-set digest forces an "
                    "indeterminate result and is not converted into graph widening or narrowing."
                ),
            },
        },
        "required": [
            "kind",
            "completeness",
            "result_digest",
            "summary",
            "advisory_only",
        ],
        "allOf": [
            {
                "if": {
                    "properties": {"kind": {"const": "ACTION_RELEVANT_SUBGRAPH"}},
                    "required": ["kind"],
                },
                "then": {
                    "required": ["nodes", "edges", "unsupported_domains"],
                    "allOf": [{"$ref": "#/components/schemas/GraphAdvisorySelection"}],
                    "properties": {"completeness": {"const": "INCOMPLETE"}},
                    "not": {
                        "anyOf": [
                            {"required": ["classification"]},
                            {"required": ["selection_digest"]},
                            {"required": ["changes"]},
                            {"required": ["objective_set_change"]},
                        ]
                    },
                },
            },
            {
                "if": {
                    "properties": {
                        "kind": {"const": "GRAPH_DIFFERENCE_CLASSIFICATION"}
                    },
                    "required": ["kind"],
                },
                "then": {
                    "required": [
                        "classification",
                        "selection_digest",
                        "changes",
                        "unsupported_domains",
                    ],
                    "not": {
                        "anyOf": [
                            {"required": ["nodes"]},
                            {"required": ["edges"]},
                            {"required": ["inventory_reconciliation"]},
                            {"required": ["slo_objectives"]},
                        ]
                    },
                },
            },
        ],
        "description": (
            "Public status projection of the retained worker result. Selection exposes "
            "at most 1,000 nodes and 5,000 edges; classification exposes at most 100 "
            "changes. Internal s04_summary_coverage and evidence summaries are not "
            "returned by this endpoint. The result does not create graph edges, "
            "authorize an action, or establish that a recommendation was adopted."
        ),
    }


def graph_advisory_selection_schema():
    return {
        "type": "object",
        "properties": {
            "inventory_reconciliation": {
                "$ref": "#/components/schemas/GraphInventoryReconciliation",
            },
            "slo_objectives": {
                "$ref": "#/components/schemas/GraphSloObjectiveSnapshot",
            },
        },
        "description": (
            "Additional, optional fields for an action-relevant subgraph selection. "
            "The S05 objective snapshot is exposed only for query v3."
        ),
    }


def graph_slo_objective_snapshot_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "workload_id": {
                "type": "string",
                "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$",
            },
            "purpose": {
                "enum": [
                    "topology.graph.subgraph-selection",
                    "topology.graph.difference-classification",
                ],
            },
            "objectives": {
                "type": "array",
                "maxItems": 100,
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "id": {"type": "string", "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$"},
                        "metric": {"type": "string", "pattern": r"^[a-z][a-z0-9_.-]{0,99}$"},
                        "comparator": {"enum": ["GTE", "LTE"]},
                        "target": {"type": "number"},
                        "unit": {"type": "string", "pattern": r"^[A-Za-z0-9_./%-]{1,30}$"},
                        "window_seconds": {"type": "integer", "minimum": 1},
                        "revision": {"type": "integer", "minimum": 1},
                        "content_digest": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
                    },
                    "required": [
                        "id", "metric", "comparator", "target", "unit", "window_seconds",
                        "revision", "content_digest",
                    ],
                },
            },
            "set_digest": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        },
        "required": ["workload_id", "purpose", "objectives", "set_digest"],
        "description": (
            "Bounded point-in-time S05 owner receipt. The set digest binds workload ID and the "
            "metric-ordered objective IDs, revisions and content digests; it is not "
            "a graph relationship or a measurement evaluation."
        ),
    }


def graph_objective_set_change_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "workload_id": {
                "type": "string",
                "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$",
            },
            "authorized_set_digest": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
            "current_set_digest": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
            "changed": {"type": "boolean"},
        },
        "required": [
            "workload_id", "authorized_set_digest", "current_set_digest", "changed",
        ],
        "description": (
            "Digest comparison of S05 active-objective context. If changed, the "
            "classification is indeterminate; objective semantics are not interpreted as graph deltas."
        ),
    }


def graph_inventory_reconciliation_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "status": {"enum": ["COMPARABLE", "INDETERMINATE"]},
            "service_membership_count": {
                "type": ["integer", "null"],
                "minimum": 0,
                "description": "Exact S02 service membership count when known; otherwise null.",
            },
            "graph_workload_node_count": {
                "type": "integer",
                "minimum": 0,
                "description": "Exact workload node count in the pinned accepted graph.",
            },
            "matched_membership_count": {
                "type": ["integer", "null"],
                "minimum": 0,
                "description": "Exact membership count matched to graph workload nodes when known; otherwise null.",
            },
            "catalog_membership_missing_from_graph_count": {
                "type": ["integer", "null"],
                "minimum": 0,
                "description": "Exact membership count missing from the graph when known; otherwise null.",
            },
            "graph_workload_nodes_outside_service_membership_count": {
                "type": ["integer", "null"],
                "minimum": 0,
                "description": (
                    "Exact graph workload node count outside service membership when known; "
                    "this is not an inventory defect."
                ),
            },
            "advisory_only": {"type": "boolean", "const": True},
            "limitation": {
                "type": "string",
                "description": (
                    "S02 membership is not a complete provider inventory; graph-only nodes "
                    "are not classified as defects; no freshness or completeness claim is made."
                ),
            },
        },
        "required": [
            "status",
            "service_membership_count",
            "graph_workload_node_count",
            "matched_membership_count",
            "catalog_membership_missing_from_graph_count",
            "graph_workload_nodes_outside_service_membership_count",
            "advisory_only",
            "limitation",
        ],
    }


def graph_advisory_node_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "node_type": {"type": "string", "minLength": 1, "maxLength": 100, "pattern": r"\S"},
            "node_id": {
                "type": "string",
                "minLength": 1,
                "maxLength": 200,
                "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$",
            },
        },
        "required": ["node_type", "node_id"],
    }


def graph_advisory_edge_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "source_type": {"type": "string", "minLength": 1, "maxLength": 100, "pattern": r"\S"},
            "source_id": {
                "type": "string",
                "minLength": 1,
                "maxLength": 200,
                "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$",
            },
            "relationship_type": {
                "type": "string",
                "minLength": 1,
                "maxLength": 100,
                "pattern": r"\S",
            },
            "target_type": {"type": "string", "minLength": 1, "maxLength": 100, "pattern": r"\S"},
            "target_id": {
                "type": "string",
                "minLength": 1,
                "maxLength": 200,
                "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$",
            },
        },
        "required": [
            "source_type",
            "source_id",
            "relationship_type",
            "target_type",
            "target_id",
        ],
    }


def graph_advisory_change_schema():
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "change_type": {
                "enum": [
                    "dependency_target_changed",
                    "dependency_added",
                    "dependency_removed",
                    "failure_domain_membership_added",
                    "failure_domain_membership_removed",
                    "data_read_added",
                    "data_read_removed",
                    "data_read_target_changed",
                ]
            },
            "before": {"$ref": "#/components/schemas/GraphAdvisoryEdge"},
            "after": {"$ref": "#/components/schemas/GraphAdvisoryEdge"},
        },
        "required": ["change_type"],
        "anyOf": [{"required": ["before"]}, {"required": ["after"]}],
        "allOf": [
            {
                "if": {
                    "properties": {
                        "change_type": {
                            "enum": [
                                "dependency_target_changed",
                                "data_read_target_changed",
                            ]
                        }
                    },
                    "required": ["change_type"],
                },
                "then": {"required": ["before", "after"]},
            },
            {
                "if": {
                    "properties": {
                        "change_type": {
                            "enum": [
                                "dependency_added",
                                "failure_domain_membership_added",
                                "data_read_added",
                            ]
                        }
                    },
                    "required": ["change_type"],
                },
                "then": {
                    "required": ["after"],
                    "not": {"required": ["before"]},
                },
            },
            {
                "if": {
                    "properties": {
                        "change_type": {
                            "enum": [
                                "dependency_removed",
                                "failure_domain_membership_removed",
                                "data_read_removed",
                            ]
                        }
                    },
                    "required": ["change_type"],
                },
                "then": {
                    "required": ["before"],
                    "not": {"required": ["after"]},
                },
            },
        ],
    }


def error_response(description):
    return {
        "description": description,
        "content": {
            "application/json": {
                "schema": {"$ref": "#/components/schemas/Error"}
            }
        },
    }


def command_status_operation():
    return {
        "get": {
            "operationId": "command_status",
            "summary": "Read durable command status.",
            "description": (
                "Supporting authenticated same-origin status contract returned by a "
                "command receipt; it is not one of the 74 canonical operations. "
                "Applies current caller scope and disclosure checks. The receipt's "
                "PENDING status is admission-only; the durable command starts as "
                "ACCEPTED. A successful advisory result does not create graph edges "
                "or authorize execution. The S07 assessment-case receipt retains its "
                "business purpose; the topology operation purpose is bound by the "
                "S01 delegation for the exact case ID and need not equal the receipt "
                "purpose."
            ),
            "security": [{"bearerAuth": []}],
            "x-implementation-status": "IMPLEMENTED_LOCAL_RUNTIME_NOT_LIVE_ACCEPTED",
            "parameters": [
                {
                    "name": "id",
                    "in": "path",
                    "required": True,
                    "description": "Command ID from the admission receipt.",
                    "schema": {
                        "type": "string",
                        "minLength": 1,
                        "maxLength": 200,
                        "pattern": r"\S",
                    },
                }
            ],
            "responses": {
                "200": {
                    "description": "Current retained command lifecycle status.",
                    "content": {
                        "application/json": {
                            "schema": {"$ref": "#/components/schemas/CommandStatus"}
                        }
                    },
                },
                "401": error_response("Authentication required"),
                "403": error_response("Current scope or capability denied"),
                "404": error_response("Command absent or undisclosable"),
                "429": error_response("Rate limit; honor Retry-After"),
                "503": error_response("Required store or dependency unavailable"),
            },
        }
    }


def update_request_schema(schemas, name):
    request = schemas[name]
    request["properties"]["case_id"]["description"] = (
        "Must be checked against a current S07 assessment_case receipt for this "
        "operation; caller-supplied case identity is not proof of current case state."
    )
    if name == "canonical_027_request":
        request["properties"]["service_id"]["description"] = (
            "Service identity for the comparison and current S07 case check. This "
            "request has no catalog revision reference; S06 must not infer one from "
            "mutable current metadata or claim a determinate classification without "
            "the exact retained selection context."
        )
        return

    request["properties"]["query_id"] = {
        "enum": GRAPH_SELECTION_QUERY_IDS,
        "description": (
            "Versioned allowlist ID. v1 covers bounded dependency selection; v2 "
            "additionally selects READS_FROM relationships for data-read "
            "classification; v3 preserves v2 and adds the point-in-time current S05 active "
            "objectives for the S07 case workload as owner-derived context only. "
            "A changed S05 set forces classification to indeterminate and is not "
            "interpreted as graph widening or narrowing. None is complete "
            "framework-wide action-relevant selection."
        ),
    }
    request["properties"]["query_parameters_ref"] = {
        "type": "string",
        "minLength": 1,
        "maxLength": 2048,
        "pattern": r"^catalog://service/[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}/revision/[1-9][0-9]{0,15}$",
        "description": (
            "Opaque S02 catalog reference exactly shaped as "
            "catalog://service/{service_id}/revision/{resource_version}, where the "
            "version is a positive decimal revision token. Never dereference "
            "arbitrary URLs; bind it to the request service_id. Obtain the signed "
            "S02 service receipt and the exact retained revision from "
            "GET /internal/v1/services/{id}/revisions/{version} using the same "
            "resource/purpose-scoped delegation. Verify the response version and "
            "service ID and the JCS SHA-256 of the full snapshot against both "
            "content_digest values. Use snapshot owned_agent_workload_ids only as candidate "
            "roots, intersected with agent_workload nodes in the accepted graph; "
            "catalog membership never creates graph nodes or edges. Service revisions "
            "are append-only and are not fabricated for existing services; if no "
            "retained revision exists, fail closed instead of substituting mutable "
            "current metadata."
        ),
    }
    request["properties"]["query_parameters_digest"]["description"] = (
        "Must equal both the S02 owner receipt content_digest and exact immutable "
        "revision response content_digest for this service resource_version. Both "
        "bind the full retained snapshot, including sorted owned_agent_workload_ids when "
        "available; a legacy snapshot without membership has unknown, not empty, "
        "membership and yields incomplete coverage."
    )
    request["properties"]["service_id"]["description"] = (
        "Catalog service identity bound to query_parameters_ref and the S02 receipt."
    )
    request["properties"]["purpose"]["description"] = (
        "Caller-supplied rationale only. S01 delegation and owner reads use the fixed "
        "closed operation purpose topology.graph.subgraph-selection."
    )


def update_graph_operation(document, path, operation_id, purpose, scope_description):
    operation = document["paths"][path]["post"]
    description = operation["description"]
    for generated_clause in (
        " Current case and catalog/evidence provenance are checked",
        " The exact S02 service revision and current S07 case are checked",
        " The current S07 case is checked",
    ):
        description = description.split(generated_clause, 1)[0]
    description = description.replace(
        "Read status through the separately proposed command-status operation.",
        "Read status through GET /v1/commands/{id}.",
    )
    description = description.replace(scope_description, "").rstrip()
    if path == "/v1/graphs/subgraphs:select":
        proof_checks = (
            "The exact S02 service revision and current S07 case are checked by their "
            "owners; bounded S04 qualification summaries are optional supporting context. "
            "Query v3 additionally reads the point-in-time current S05 active-objective set for the "
            "case workload through a resource-scoped delegation; this owner-derived "
            "context is not a graph relationship."
        )
    else:
        proof_checks = (
            "The current S07 case is checked by its owner and bounded S04 qualification "
            "summaries are optional supporting context; this request does not bind an "
            "S02 service revision. The S07 receipt retains its business purpose; the "
            "S01 delegation binds the topology operation purpose to the exact case ID, "
            "without requiring those purpose values to match. For a retained v3 "
            "selection, S06 re-reads the point-in-time current S05 active-objective set for the same "
            "case workload; if its digest changed, classification is indeterminate."
        )
    if path == "/v1/graphs/subgraphs:select":
        proof_checks += (
            " The S07 receipt retains its business purpose; the S01 delegation binds "
            "the topology operation purpose to the exact case ID, without requiring "
            "those purpose values to match."
        )
    operation["description"] = (
        description.rstrip()
        + " "
        + scope_description
        + " "
        + proof_checks
        + " S04 observed "
        + "time is retained but `QUALIFIED` is not freshness certification; where "
        + "freshness matters and no query policy threshold exists, report INCOMPLETE "
        + "or INDETERMINATE. S01 delegation "
        + f"and owner-read purpose is the closed ID {purpose}. Results are advisory "
        + "only, never create graph edges, and do not authorize execution."
    )
    if path == "/v1/graphs/subgraphs:select":
        operation["description"] += (
            " For the supported dependency slice, S06 intersects the S02 snapshot's "
            "owned_agent_workload_ids with agent_workload nodes in the exact accepted graph "
            "revision and follows only existing accepted S06 edges. Catalog membership "
            "never creates graph nodes or edges. Missing revision, "
            "membership mapping, or graph roots is reported as incomplete; broader "
            "UACRF ontology coverage remains incomplete."
            " The selection revision target is keyed by service_id and query_id; "
            "changing graph, catalog, case, or evidence context is retained in the "
            "source binding and advances expected_revision for that target."
        )
    else:
        operation["description"] += (
            " This request has no immutable service-revision reference; selection_digest "
            "must resolve to a retained S06 selection for the same service and authorized "
            "graph revision. Missing or mismatched selection context is rejected; supported "
            "deltas are classified only within the retained selection scope, with broader "
            "coverage remaining incomplete or indeterminate. The difference revision target "
            "is keyed by service_id and authorized_graph_revision_id; current graph, "
            "retained selection, case, and evidence context is retained in the source "
            "binding, and each subsequent comparison advances expected_revision for "
            "that target."
        )
    operation["x-owner-read-purpose"] = operation_id


def apply_contracts(document):
    schemas = document["components"]["schemas"]
    update_request_schema(schemas, "canonical_027_request")
    update_request_schema(schemas, "canonical_028_request")
    schemas["GraphAdvisoryResult"] = graph_advisory_result_schema()
    schemas["GraphAdvisorySelection"] = graph_advisory_selection_schema()
    schemas["GraphInventoryReconciliation"] = graph_inventory_reconciliation_schema()
    schemas["GraphSloObjectiveSnapshot"] = graph_slo_objective_snapshot_schema()
    schemas["GraphObjectiveSetChange"] = graph_objective_set_change_schema()
    schemas["GraphAdvisoryNode"] = graph_advisory_node_schema()
    schemas["GraphAdvisoryEdge"] = graph_advisory_edge_schema()
    schemas["GraphAdvisoryChange"] = graph_advisory_change_schema()
    schemas["CommandStatus"] = command_status_schema()
    document["paths"]["/v1/commands/{id}"] = command_status_operation()
    update_graph_operation(
        document,
        "/v1/graphs/differences:classify",
        "topology.graph.difference-classification",
        "topology.graph.difference-classification",
        "Classification is an advisory comparison of authorized versus current graph "
        "context; it changes no graph revision or relationships.",
    )
    update_graph_operation(
        document,
        "/v1/graphs/subgraphs:select",
        "topology.graph.subgraph-selection",
        "topology.graph.subgraph-selection",
        "The user-approved action-relevant scope spans bounded dependencies, failure "
        "domains, SLOs, journeys, customer segments, impacts, controls, fallbacks, and "
        "recovery paths. Query v1 supports bounded dependency selection; query v2 "
        "additionally selects READS_FROM relationships for data-read classification; "
        "query v3 preserves v2 and adds the point-in-time current S05 active objectives for the "
        "S07 case workload as owner-derived context only. A changed objective-set "
        "digest makes classification indeterminate; objective changes are not treated "
        "as graph widening or narrowing. "
        "Coverage of the broader framework selection remains incomplete.",
    )
    return document


def render(path):
    document = json.loads(path.read_text())
    return json.dumps(apply_contracts(document), indent=2) + "\n"


def generate_outputs():
    for path in API_FILES:
        path.write_text(render(path))


def check_outputs():
    stale = [path.relative_to(BASE).as_posix() for path in API_FILES if path.read_text() != render(path)]
    if stale:
        raise SystemExit("Stale generated contract outputs: " + ", ".join(stale))


def main():
    parser = argparse.ArgumentParser(description="Generate the MS01 S06 graph async contracts.")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail if generated contract outputs are stale instead of writing them.",
    )
    args = parser.parse_args()
    if args.check:
        check_outputs()
    else:
        generate_outputs()


if __name__ == "__main__":
    main()
