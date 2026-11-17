"""Applies the accepted S10 policy-review decision to the proposed OpenAPI contract.

The patch is idempotent. Use --check to verify without writing.
"""
import argparse
import json
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
CONTRACT = BASE / "contracts/source/authorization_policy_design/openapi.proposed.json"
ID = {"type": "string", "minLength": 1, "maxLength": 200, "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$"}
HASH = {"type": "string", "pattern": "^[a-f0-9]{64}$"}
TIME = {"type": "string", "format": "date-time"}
COUNT = {"type": "integer", "minimum": 0, "maximum": 100}


def nullable(schema):
    return {"anyOf": [schema, {"type": "null"}]}


def obj(properties, required=None):
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        "required": list(properties) if required is None else required,
    }


VOTE = obj(
    {
        "reviewer_id": ID,
        "decision": {"enum": ["APPROVE", "REJECT"]},
        "reason": {"type": "string", "minLength": 1, "maxLength": 2000, "pattern": "\\S"},
        "reviewed_at": TIME,
        "key_id": nullable(ID),
        "trust_revision": nullable({"type": "integer", "minimum": 1}),
        "trust_digest": nullable(HASH),
        "assertion_digest": nullable(HASH),
        "currently_trusted": {"type": "boolean"},
    }
)
REVIEW = obj(
    {
        "required_approval_count": {"type": "integer", "minimum": 1, "maximum": 10},
        "approval_count": COUNT,
        "valid_approval_count": COUNT,
        "rejection_count": COUNT,
        "votes": {"type": "array", "maxItems": 100, "items": VOTE},
    }
)
SUMMARY_COUNTS = {
    "approval_count": COUNT,
    "valid_approval_count": COUNT,
    "rejection_count": COUNT,
}
HEADER = obj(
    {
        "alg": {"const": "RS256"},
        "kid": ID,
        "typ": {"const": "uacrf.policy-review+jws"},
    }
)
CLAIMS = obj(
    {
        "tenant_id": ID,
        "environment_id": ID,
        "policy_id": ID,
        "policy_revision": {"type": "integer", "minimum": 1, "maximum": 9223372036854775807},
        "service_id": ID,
        "policy_digest": HASH,
        "reviewer_id": ID,
        "decision": {"const": "APPROVE"},
        "reason": {"type": "string", "minLength": 1, "maxLength": 2000, "pattern": "\\S"},
    }
)
TRUST_KEY = obj(
    {
        "kid": ID,
        "algorithm": {"const": "RS256"},
        "public_key_base64": {"type": "string", "minLength": 1, "description": "Base64 X.509 SubjectPublicKeyInfo DER; RSA 2048-8192 bits; public key only."},
        "tenant_id": ID,
        "environment_id": ID,
        "service_id": ID,
        "reviewer_id": ID,
    }
)
TRUST_SET = obj(
    {
        "revision": {"type": "integer", "minimum": 1, "maximum": 9223372036854775807},
        "keys": {"type": "array", "maxItems": 1000, "items": TRUST_KEY},
    }
)
DECIDE_DESCRIPTION = (
    "Records one immutable reviewer vote on the exact current revision; it never creates an action permit, "
    "reservation or operational authorization. Requires an authenticated human S01 principal with "
    "authorization.policy.review for the policy's exact service (not '*'). The reviewer must be listed in "
    "eligible_approver_ids and must differ from proposed_by. expected_revision must equal the current "
    "revision (409 REVISION_CONFLICT otherwise). Each reviewer may vote once per revision (409 DUPLICATE_VOTE); "
    "retries with the same Idempotency-Key and content replay the original vote. "
    "APPROVE requires signed_assertion: a compact JWS (RS256) whose header is PolicyReviewAssertionHeader and "
    "whose payload is the RFC 8785 canonical JSON of PolicyReviewAssertionClaims. The signature is verified "
    "with the public key selected by kid from the operator-provisioned versioned PolicyTrustSet, which must "
    "be scoped to the same tenant, environment, service and reviewer; unknown or removed keys are rejected "
    "(422 ASSERTION_KEY_UNTRUSTED). The claims bind tenant, environment, policy id and revision, service, "
    "policy digest, reviewer, decision and reason exactly. REJECT requires signed_assertion=null and the same "
    "reviewer authorization. Quorum is max(1, definition.required_approver_count) distinct eligible approving "
    "reviewers; votes below quorum leave the revision DRAFT, reaching quorum makes it APPROVED, and any "
    "rejection makes it REJECTED; both outcomes are final (409 DECISION_FINAL). For a decided revision, "
    "reviewer_id, review_reason, reviewed_at and assertion fields describe the decisive vote and review.votes "
    "lists every vote. current_eligible is true only when the revision is APPROVED, is the current revision, "
    "is inside its effective/expiry window, and at least quorum approvals still verify against keys present "
    "and unchanged in the current trust set (review.valid_approval_count). A configured trust revision lower "
    "than one already recorded, or different content under a recorded revision, fails closed with 503."
)
ASSERTION_DESCRIPTION = (
    "Compact RS256 JWS; see the decide operation, PolicyReviewAssertionHeader and PolicyReviewAssertionClaims."
)


def is_policy(schema):
    properties = schema.get("properties", {}) if isinstance(schema, dict) else {}
    return {"assertion_digest", "signed_assertion", "material"} <= properties.keys()


def is_summary(schema):
    properties = schema.get("properties", {}) if isinstance(schema, dict) else {}
    return "assertion_digest" in properties and "signed_assertion" not in properties and "current_eligible" in properties


def patch(node):
    if isinstance(node, dict):
        if is_policy(node):
            node["properties"]["review"] = json.loads(json.dumps(REVIEW))
            if "review" not in node["required"]:
                node["required"].append("review")
        elif is_summary(node):
            node["properties"].update(json.loads(json.dumps(SUMMARY_COUNTS)))
            for name in SUMMARY_COUNTS:
                if name not in node["required"]:
                    node["required"].append(name)
        for value in list(node.values()):
            patch(value)
    elif isinstance(node, list):
        for value in node:
            patch(value)


def build():
    document = json.loads(CONTRACT.read_text())
    patch(document["paths"])
    schemas = document["components"]["schemas"]
    schemas["PolicyReviewAssertionHeader"] = HEADER
    schemas["PolicyReviewAssertionClaims"] = CLAIMS
    schemas["PolicyTrustSet"] = TRUST_SET
    operation = document["paths"]["/v1/authorization-policy-bundles/{id}:decide"]["post"]
    operation["description"] = DECIDE_DESCRIPTION
    body = operation["requestBody"]["content"]["application/json"]["schema"]
    body["properties"]["signed_assertion"]["description"] = ASSERTION_DESCRIPTION
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = build()
    if args.check:
        raise SystemExit(0 if CONTRACT.read_text() == expected else 1)
    CONTRACT.write_text(expected)


if __name__ == "__main__":
    main()
