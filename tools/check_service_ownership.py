from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
OWNERS = {
    "identity": {
        "module": "services/identity",
        "artifact": "identity-service",
        "database": "uacrf_identity",
        "jdbc_env": "IDENTITY_JDBC_URL",
        "user_env": "IDENTITY_DB_USER",
        "password_env": "IDENTITY_DB_PASSWORD",
        "name_env": "IDENTITY_DB_NAME",
        "admin_user_env": "IDENTITY_DB_ADMIN_USER",
        "admin_password_env": "IDENTITY_DB_ADMIN_PASSWORD",
        "delegation_public_env": "UACRF_DELEGATION_PUBLIC_KEY_BASE64",
        "delegation_private_env": "UACRF_DELEGATION_PRIVATE_KEY_BASE64",
        "delegation_key_id_env": "UACRF_DELEGATION_KEY_ID",
        "compose_service": "identity-db",
    },
    "catalog": {
        "module": "services/estate-catalog",
        "artifact": "estate-catalog-service",
        "database": "uacrf_catalog",
        "jdbc_env": "CATALOG_JDBC_URL",
        "user_env": "CATALOG_DB_USER",
        "password_env": "CATALOG_DB_PASSWORD",
        "name_env": "CATALOG_DB_NAME",
        "admin_user_env": "CATALOG_DB_ADMIN_USER",
        "admin_password_env": "CATALOG_DB_ADMIN_PASSWORD",
        "delegation_public_env": "UACRF_DELEGATION_PUBLIC_KEY_BASE64",
        "delegation_private_env": None,
        "delegation_key_id_env": "UACRF_DELEGATION_KEY_ID",
        "compose_service": "catalog-db",
    },
    "connections": {
        "module": "services/connections",
        "artifact": "connections-service",
        "database": "uacrf_connections",
        "jdbc_env": "CONNECTIONS_JDBC_URL",
        "user_env": "CONNECTIONS_DB_USER",
        "password_env": "CONNECTIONS_DB_PASSWORD",
        "name_env": "CONNECTIONS_DB_NAME",
        "admin_user_env": "CONNECTIONS_DB_ADMIN_USER",
        "admin_password_env": "CONNECTIONS_DB_ADMIN_PASSWORD",
        "delegation_public_env": "UACRF_DELEGATION_PUBLIC_KEY_BASE64",
        "delegation_private_env": None,
        "delegation_key_id_env": "UACRF_DELEGATION_KEY_ID",
        "compose_service": "connections-db",
    },
}


def _properties(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def _compose_service(compose: str, name: str) -> str | None:
    match = re.search(
        rf"^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|^volumes:\n|\Z)",
        compose,
        re.MULTILINE | re.DOTALL,
    )
    return match.group(1) if match else None


def check_service_ownership(root: Path = ROOT) -> list[str]:
    errors = []
    root_pom = ET.parse(root / "pom.xml").getroot()
    declared_modules = {
        module.text
        for module in root_pom.findall("./m:modules/m:module", POM_NAMESPACE)
    }
    service_artifacts = {owner["artifact"] for owner in OWNERS.values()}
    compose = (root / "compose.yaml").read_text()
    role_hardening_path = root / "config/postgres/10-restrict-application-role.sql"
    if not role_hardening_path.is_file():
        errors.append("PostgreSQL application-role hardening script is missing")
    else:
        role_hardening = role_hardening_path.read_text()
        for privilege in (
            "NOSUPERUSER",
            "NOCREATEDB",
            "NOCREATEROLE",
            "NOREPLICATION",
            "NOBYPASSRLS",
        ):
            if privilege not in role_hardening:
                errors.append(
                    f"PostgreSQL application role is not restricted for {privilege}"
                )

    for owner in OWNERS.values():
        module = owner["module"]
        service_root = root / module
        if module not in declared_modules:
            errors.append(f"{module} is missing from the root Maven reactor")
            continue

        service_pom = ET.parse(service_root / "pom.xml").getroot()
        dependencies = {
            dependency.findtext("m:artifactId", namespaces=POM_NAMESPACE)
            for dependency in service_pom.findall(
                "./m:dependencies/m:dependency", POM_NAMESPACE
            )
            if dependency.findtext("m:groupId", namespaces=POM_NAMESPACE) == "org.uacrf"
        }
        peer_dependencies = (dependencies & service_artifacts) - {owner["artifact"]}
        if peer_dependencies:
            errors.append(f"{module} depends on another service: {sorted(peer_dependencies)}")

        config_path = service_root / "src/main/resources/application.properties"
        config = _properties(config_path)
        url = config.get("spring.datasource.url", "")
        username = config.get("spring.datasource.username", "")
        password = config.get("spring.datasource.password", "")
        if not url.startswith(f"${{{owner['jdbc_env']}:") or not url.endswith(
            f"/{owner['database']}}}"
        ):
            errors.append(f"{module} JDBC URL is not bound to its owner database")
        if not username.startswith(f"${{{owner['user_env']}:"):
            errors.append(f"{module} database username is not owner-specific")
        if password != f"${{{owner['password_env']}}}":
            errors.append(f"{module} database password is not owner-specific")
        if config.get("uacrf.delegation.public-key-base64") != (
            f"${{{owner['delegation_public_env']}}}"
        ):
            errors.append(f"{module} delegation verifier is not bound to the public key")
        if config.get("uacrf.delegation.key-id") != (
            f"${{{owner['delegation_key_id_env']}}}"
        ):
            errors.append(f"{module} delegation key id is not externally configured")
        private_key_property = "uacrf.delegation.private-key-base64"
        private_key_env = owner["delegation_private_env"]
        if private_key_env:
            if config.get(private_key_property) != f"${{{private_key_env}}}":
                errors.append(f"{module} signer is not bound to its private key")
        elif private_key_property in config:
            errors.append(f"{module} must not receive the delegation private key")
        if "uacrf.delegation.secret-base64" in config:
            errors.append(f"{module} still configures a shared delegation secret")

        foreigners = [
            candidate for candidate in OWNERS.values() if candidate is not owner
        ]
        for path in (
            root / module / "src/main/java",
            root / module / "src/main/resources/db/migration",
        ):
            files = list(path.rglob("*.java")) + list(path.rglob("*.sql"))
            for source in files:
                schemas = set(re.findall(r"\b(uacrf_[a-z0-9_]+)\.", source.read_text()))
                if any(foreign["database"] in schemas for foreign in foreigners):
                    errors.append(
                        f"{source.relative_to(root)} references another owner's database schema"
                    )

        block = _compose_service(compose, owner["compose_service"])
        if block is None:
            errors.append(f"Compose is missing {owner['compose_service']}")
            continue
        role_mount = (
            "./config/postgres/10-restrict-application-role.sql:"
            "/docker-entrypoint-initdb.d/10-restrict-application-role.sql:ro"
        )
        if role_mount not in block:
            errors.append(
                f"Compose {owner['compose_service']} does not apply database-role hardening"
            )
        expected_bindings = (
            ("POSTGRES_DB", owner["name_env"]),
            ("POSTGRES_USER", owner["admin_user_env"]),
            ("POSTGRES_PASSWORD", owner["admin_password_env"]),
            ("APP_DB_USER", owner["user_env"]),
            ("APP_DB_PASSWORD", owner["password_env"]),
        )
        for key, variable in expected_bindings:
            if f"{key}: ${{{variable}:" not in block:
                errors.append(
                    f"Compose {owner['compose_service']} does not use {variable}"
                )
        if any(
            foreign[key] in block
            for foreign in foreigners
            for key in (
                "name_env",
                "user_env",
                "password_env",
                "admin_user_env",
                "admin_password_env",
            )
        ):
            errors.append(
                f"Compose {owner['compose_service']} references another owner's credentials"
            )

    return errors


def main() -> int:
    errors = check_service_ownership()
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("S01/S02/S03 service ownership checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
