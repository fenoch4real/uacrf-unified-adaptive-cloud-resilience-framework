import json
import unittest
from pathlib import Path

import build_graph_async_contracts as generator


class GraphAsyncContractTests(unittest.TestCase):
    def test_generation_is_idempotent_and_matches_both_api_documents(self):
        for path in generator.API_FILES:
            source = json.loads(path.read_text())
            expected = generator.apply_contracts(json.loads(json.dumps(source)))
            self.assertEqual(source, expected)

    def test_status_schema_distinguishes_admission_and_completion(self):
        schema = generator.command_status_schema()
        self.assertEqual(generator.COMMAND_STATUSES, schema["properties"]["status"]["enum"])
        self.assertEqual(
            ["command_id", "status", "terminal", "domain_completion"],
            schema["required"],
        )
        self.assertNotIn("PENDING", schema["properties"]["status"]["enum"])
        self.assertIn("OUTCOME_UNKNOWN", generator.NONTERMINAL_STATUSES)
        conditions = {
            tuple(
                condition["if"]["properties"]["status"].get(
                    "enum",
                    [condition["if"]["properties"]["status"].get("const")],
                )
            ): condition["then"]["properties"]
            for condition in schema["allOf"]
            if "status" in condition["if"].get("properties", {})
        }
        self.assertEqual(
            {"terminal": {"const": True}, "domain_completion": {"const": True}},
            conditions[("SUCCEEDED",)],
        )
        self.assertEqual(
            {"terminal": {"const": True}, "domain_completion": {"const": False}},
            conditions[tuple(generator.UNSUCCESSFUL_TERMINAL_STATUSES)],
        )
        self.assertEqual(
            {"terminal": {"const": False}, "domain_completion": {"const": False}},
            conditions[tuple(generator.NONTERMINAL_STATUSES)],
        )

    def test_status_response_models_only_bounded_public_projection(self):
        status = generator.command_status_schema()
        self.assertEqual(
            ["command_id", "status", "terminal", "domain_completion"],
            status["required"],
        )
        self.assertEqual(
            {
                "command_id",
                "status",
                "terminal",
                "domain_completion",
                "advisory_result",
            },
            set(status["properties"]),
        )
        self.assertNotIn("operation", status["properties"])
        self.assertNotIn("result", status["properties"])

        result = generator.graph_advisory_result_schema()
        self.assertEqual(
            {
                "kind",
                "completeness",
                "result_digest",
                "summary",
                "advisory_only",
                "nodes",
                "edges",
                "unsupported_domains",
                "classification",
                "selection_digest",
                "changes",
                "inventory_reconciliation",
                "slo_objectives",
                "objective_set_change",
            },
            set(result["properties"]),
        )
        self.assertNotIn("s04_summary_coverage", result["properties"])
        self.assertEqual(1000, result["properties"]["nodes"]["maxItems"])
        self.assertEqual(5000, result["properties"]["edges"]["maxItems"])
        self.assertEqual(100, result["properties"]["changes"]["maxItems"])
        self.assertEqual(
            "#/components/schemas/GraphSloObjectiveSnapshot",
            result["properties"]["slo_objectives"]["$ref"],
        )
        self.assertEqual(
            "#/components/schemas/GraphObjectiveSetChange",
            result["properties"]["objective_set_change"]["$ref"],
        )
        self.assertEqual(
            100,
            generator.graph_slo_objective_snapshot_schema()["properties"]["objectives"]["maxItems"],
        )
        self.assertIn(
            "indeterminate",
            generator.graph_objective_set_change_schema()["description"],
        )

        node = generator.graph_advisory_node_schema()
        edge = generator.graph_advisory_edge_schema()
        change = generator.graph_advisory_change_schema()
        self.assertEqual(["node_type", "node_id"], node["required"])
        self.assertEqual(
            ["source_type", "source_id", "relationship_type", "target_type", "target_id"],
            edge["required"],
        )
        self.assertEqual(
            ["dependency_target_changed", "dependency_added", "dependency_removed",
             "failure_domain_membership_added", "failure_domain_membership_removed",
             "data_read_added", "data_read_removed", "data_read_target_changed"],
            change["properties"]["change_type"]["enum"],
        )
        transition_requirements = {}
        for condition in change["allOf"]:
            change_type = condition["if"]["properties"]["change_type"]
            change_types = change_type.get("enum", [change_type.get("const")])
            transition_requirements.update(
                {
                    value: condition["then"]["required"]
                    for value in change_types
                }
            )
        self.assertEqual(
            ["after"],
            transition_requirements["data_read_added"],
        )
        self.assertEqual(
            ["before"],
            transition_requirements["data_read_removed"],
        )
        self.assertEqual(
            ["before", "after"],
            transition_requirements["data_read_target_changed"],
        )

    def test_selection_inventory_reconciliation_is_additive_and_bounded(self):
        names = {
            "status",
            "service_membership_count",
            "graph_workload_node_count",
            "matched_membership_count",
            "catalog_membership_missing_from_graph_count",
            "graph_workload_nodes_outside_service_membership_count",
            "advisory_only",
            "limitation",
        }
        for path in generator.API_FILES:
            schemas = json.loads(path.read_text())["components"]["schemas"]
            selection = schemas["GraphAdvisorySelection"]
            result = schemas["GraphAdvisoryResult"]
            prop = selection["properties"]["inventory_reconciliation"]
            self.assertEqual(prop, result["properties"]["inventory_reconciliation"])
            self.assertNotIn("inventory_reconciliation", result["required"])
            selection_rule, difference_rule = result["allOf"][:2]
            self.assertIn(
                {"$ref": "#/components/schemas/GraphAdvisorySelection"},
                selection_rule["then"]["allOf"],
            )
            self.assertIn(
                {"required": ["inventory_reconciliation"]},
                difference_rule["then"]["not"]["anyOf"],
            )
            reconciliation = schemas[prop["$ref"].split("/")[-1]]
            self.assertFalse(reconciliation["additionalProperties"])
            self.assertEqual(names, set(reconciliation["properties"]))
            self.assertEqual(names, set(reconciliation["required"]))
            properties = reconciliation["properties"]
            self.assertEqual(["COMPARABLE", "INDETERMINATE"], properties["status"]["enum"])
            for name in (
                "service_membership_count",
                "matched_membership_count",
                "catalog_membership_missing_from_graph_count",
                "graph_workload_nodes_outside_service_membership_count",
            ):
                self.assertEqual(["integer", "null"], properties[name]["type"])
                self.assertEqual(0, properties[name]["minimum"])
            self.assertEqual("integer", properties["graph_workload_node_count"]["type"])
            self.assertEqual(0, properties["graph_workload_node_count"]["minimum"])
            self.assertIn("pinned accepted graph", properties["graph_workload_node_count"]["description"])
            self.assertIn("not an inventory defect", properties[
                "graph_workload_nodes_outside_service_membership_count"
            ]["description"])
            self.assertEqual({"type": "boolean", "const": True}, properties["advisory_only"])
            limitation = properties["limitation"]
            self.assertEqual("string", limitation["type"])
            self.assertIn("not a complete provider inventory", limitation["description"])
            self.assertIn("not classified as defects", limitation["description"])
            self.assertIn("no freshness or completeness claim", limitation["description"])

    def test_both_requests_keep_required_fields_and_bound_source_refs(self):
        for path in generator.API_FILES:
            schemas = json.loads(path.read_text())["components"]["schemas"]
            difference = schemas["canonical_027_request"]
            self.assertEqual(
                [
                    "service_id",
                    "case_id",
                    "reason",
                    "expected_revision",
                    "authorized_graph_revision_id",
                    "current_graph_revision_id",
                    "selection_digest",
                ],
                difference["required"],
            )
            selection = schemas["canonical_028_request"]
            self.assertEqual(
                [
                    "service_id",
                    "case_id",
                    "graph_revision_id",
                    "reason",
                    "expected_revision",
                    "query_id",
                    "query_parameters_ref",
                    "query_parameters_digest",
                    "purpose",
                    "maximum_nodes",
                ],
                selection["required"],
            )
            self.assertEqual(
                generator.GRAPH_SELECTION_QUERY_IDS,
                selection["properties"]["query_id"]["enum"],
            )
            query_description = selection["properties"]["query_id"]["description"]
            self.assertIn("v1 covers bounded dependency selection", query_description)
            self.assertIn("v2 additionally selects READS_FROM", query_description)
            self.assertIn("v3 preserves v2", query_description)
            self.assertIn("active objectives", query_description)
            self.assertIn("forces classification to indeterminate", query_description)
            self.assertIn(
                r"^catalog://service/[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}/revision/[1-9][0-9]{0,15}$",
                selection["properties"]["query_parameters_ref"]["pattern"],
            )
            self.assertIn(
                "GET /internal/v1/services/{id}/revisions/{version}",
                selection["properties"]["query_parameters_ref"]["description"],
            )
            self.assertIn(
                "owned_agent_workload_ids",
                selection["properties"]["query_parameters_digest"]["description"],
            )
            self.assertIn(
                "exact retained selection context",
                difference["properties"]["service_id"]["description"],
            )
            self.assertEqual(
                "topology.graph.difference-classification",
                json.loads(path.read_text())["paths"]["/v1/graphs/differences:classify"]["post"][
                    "x-owner-read-purpose"
                ],
            )
            self.assertEqual(
                {"const": "INCOMPLETE"},
                schemas["GraphAdvisoryResult"]["allOf"][0]["then"]["properties"]["completeness"],
            )

    def test_existing_graph_post_paths_and_ids_are_unchanged(self):
        for path in generator.API_FILES:
            document = json.loads(path.read_text())
            paths = document["paths"]
            self.assertEqual("canonical_027", paths["/v1/graphs/differences:classify"]["post"]["operationId"])
            self.assertEqual("canonical_028", paths["/v1/graphs/subgraphs:select"]["post"]["operationId"])
            self.assertIn("/v1/commands/{id}", paths)
            self.assertIn("advisory only", paths["/v1/graphs/differences:classify"]["post"]["description"])
            self.assertIn(
                "selection_digest must resolve to a retained S06 selection",
                paths["/v1/graphs/differences:classify"]["post"]["description"],
            )
            self.assertIn(
                "Missing or mismatched selection context is rejected",
                paths["/v1/graphs/differences:classify"]["post"]["description"],
            )
            self.assertIn(
                "The difference revision target is keyed by service_id and authorized_graph_revision_id",
                paths["/v1/graphs/differences:classify"]["post"]["description"],
            )
            self.assertIn(
                "Catalog membership never creates graph nodes or edges",
                paths["/v1/graphs/subgraphs:select"]["post"]["description"],
            )
            self.assertIn(
                "The selection revision target is keyed by service_id and query_id",
                paths["/v1/graphs/subgraphs:select"]["post"]["description"],
            )
            self.assertIn(
                "This request has no immutable service-revision reference",
                paths["/v1/graphs/differences:classify"]["post"]["description"],
            )
            self.assertEqual(
                "topology.graph.subgraph-selection",
                paths["/v1/graphs/subgraphs:select"]["post"]["x-owner-read-purpose"],
            )
            selection_description = paths["/v1/graphs/subgraphs:select"]["post"]["description"]
            difference_description = paths["/v1/graphs/differences:classify"]["post"]["description"]
            self.assertIn("service_id and query_id", selection_description)
            self.assertIn("source binding", selection_description)
            self.assertIn("service_id and authorized_graph_revision_id", difference_description)
            self.assertIn("source binding", difference_description)
            self.assertIn(
                "without requiring those purpose values to match",
                selection_description,
            )
            self.assertIn(
                "without requiring those purpose values to match",
                difference_description,
            )
            status = paths["/v1/commands/{id}"]["get"]
            self.assertEqual(
                "IMPLEMENTED_LOCAL_RUNTIME_NOT_LIVE_ACCEPTED",
                status["x-implementation-status"],
            )
            self.assertEqual(
                "#/components/schemas/CommandStatus",
                status["responses"]["200"]["content"]["application/json"]["schema"]["$ref"],
            )
            receipt = document["components"]["schemas"]["CommandReceipt"]
            self.assertEqual("PENDING", receipt["properties"]["status"]["const"])
            self.assertEqual(False, receipt["properties"]["domain_completion"]["const"])


if __name__ == "__main__":
    unittest.main()
