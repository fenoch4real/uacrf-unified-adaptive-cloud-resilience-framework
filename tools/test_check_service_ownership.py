import tempfile
import unittest
from pathlib import Path

from tools.check_service_ownership import OWNERS, check_service_ownership


class ServiceOwnershipTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        modules = "\n".join(
            f"<module>{owner['module']}</module>" for owner in OWNERS.values()
        )
        (self.root / "pom.xml").write_text(
            f"<project xmlns='http://maven.apache.org/POM/4.0.0'>"
            f"<modules>{modules}</modules></project>"
        )
        self._write_compose()
        self._write(
            self.root / "config/postgres/10-restrict-application-role.sql",
            "CREATE ROLE admin SUPERUSER;\n"
            "ALTER ROLE app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n",
        )

        for owner in OWNERS.values():
            service = self.root / owner["module"]
            (service / "pom.xml").parent.mkdir(parents=True, exist_ok=True)
            (service / "pom.xml").write_text(
                "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
                f"<artifactId>{owner['artifact']}</artifactId>"
                "<dependencies><dependency><groupId>org.uacrf</groupId>"
                "<artifactId>delegation-auth</artifactId></dependency>"
                "</dependencies></project>"
            )
            config = (
                f"spring.datasource.url=${{{owner['jdbc_env']}:"
                f"jdbc:postgresql://localhost:5432/{owner['database']}}}\n"
                f"spring.datasource.username=${{{owner['user_env']}:app}}\n"
                f"spring.datasource.password=${{{owner['password_env']}}}\n"
                f"uacrf.delegation.public-key-base64="
                f"${{{owner['delegation_public_env']}}}\n"
                f"uacrf.delegation.key-id=${{{owner['delegation_key_id_env']}}}\n"
            )
            if owner["delegation_private_env"]:
                config += (
                    "uacrf.delegation.private-key-base64="
                    f"${{{owner['delegation_private_env']}}}\n"
                )
            self._write(service / "src/main/resources/application.properties", config)
            self._write(
                service / "src/main/resources/db/migration/V1__schema.sql",
                f"CREATE SCHEMA {owner['database']};",
            )

    def tearDown(self):
        self.temporary_directory.cleanup()

    def _write(self, path: Path, text: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def _write_compose(self):
        sections = ["services:"]
        for owner in OWNERS.values():
            sections.extend(
                [
                    f"  {owner['compose_service']}:",
                    "    environment:",
                    f"      POSTGRES_DB: ${{{owner['name_env']}:local}}",
                    f"      POSTGRES_USER: ${{{owner['admin_user_env']}:local}}",
                    f"      POSTGRES_PASSWORD: ${{{owner['admin_password_env']}:local}}",
                    f"      APP_DB_USER: ${{{owner['user_env']}:local}}",
                    f"      APP_DB_PASSWORD: ${{{owner['password_env']}:local}}",
                    "    volumes:",
                    "      - ./config/postgres/10-restrict-application-role.sql:"
                    "/docker-entrypoint-initdb.d/10-restrict-application-role.sql:ro",
                ]
            )
        sections.append("volumes:")
        (self.root / "compose.yaml").write_text("\n".join(sections) + "\n")

    def test_owner_specific_configs_pass(self):
        self.assertEqual([], check_service_ownership(self.root))

    def test_foreign_schema_reference_is_rejected(self):
        identity = self.root / OWNERS["identity"]["module"]
        source = identity / "src/main/java/IdentityRepository.java"
        self._write(source, "SELECT * FROM uacrf_catalog.service;")

        errors = check_service_ownership(self.root)

        self.assertTrue(any("another owner's database schema" in error for error in errors))

    def test_compose_foreign_credentials_are_rejected(self):
        compose_path = self.root / "compose.yaml"
        compose = compose_path.read_text().replace(
            "APP_DB_PASSWORD: ${IDENTITY_DB_PASSWORD:local}",
            "APP_DB_PASSWORD: ${CATALOG_DB_PASSWORD:local}",
        )
        compose_path.write_text(compose)

        errors = check_service_ownership(self.root)

        self.assertTrue(any("another owner's credentials" in error for error in errors))

    def test_application_database_role_must_be_non_superuser(self):
        hardening_path = (
            self.root / "config/postgres/10-restrict-application-role.sql"
        )
        hardening_path.write_text("ALTER ROLE app SUPERUSER;\n")

        errors = check_service_ownership(self.root)

        self.assertTrue(
            any("not restricted for NOSUPERUSER" in error for error in errors)
        )

    def test_catalog_must_not_receive_delegation_private_key(self):
        catalog = self.root / OWNERS["catalog"]["module"]
        config_path = catalog / "src/main/resources/application.properties"
        config_path.write_text(
            config_path.read_text()
            + "uacrf.delegation.private-key-base64="
            "${UACRF_DELEGATION_PRIVATE_KEY_BASE64}\n"
        )

        errors = check_service_ownership(self.root)

        self.assertTrue(any("must not receive the delegation private key" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
