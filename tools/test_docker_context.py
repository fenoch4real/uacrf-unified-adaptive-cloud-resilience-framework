import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class DockerContextTests(unittest.TestCase):
    def test_private_docs_are_excluded_from_build_context(self):
        repository = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="uacrf-docker-context-") as directory:
            root = Path(directory)
            context = root / "context"
            context.mkdir()
            shutil.copyfile(repository / ".dockerignore", context / ".dockerignore")
            (context / "public.txt").write_text("public fixture\n")
            (context / "private-docs").mkdir()
            (context / "private-docs" / "fixture.txt").write_text("private fixture\n")

            def build(source: str, output: Path) -> subprocess.CompletedProcess[str]:
                return subprocess.run(
                    [
                        "docker",
                        "build",
                        "--quiet",
                        "--no-cache",
                        "--file",
                        "-",
                        "--output",
                        f"type=local,dest={output}",
                        str(context),
                    ],
                    input=f"FROM scratch\nCOPY {source} /fixture.txt\n",
                    text=True,
                    capture_output=True,
                    check=False,
                )

            public = build("public.txt", root / "public-output")
            self.assertEqual(0, public.returncode, public.stderr)
            self.assertEqual(
                "public fixture\n", (root / "public-output" / "fixture.txt").read_text()
            )

            private = build("private-docs/fixture.txt", root / "private-output")
            self.assertNotEqual(0, private.returncode, private.stderr)
            self.assertIn("not found", private.stderr.lower())
            self.assertIn("private-docs/fixture.txt", private.stderr)


if __name__ == "__main__":
    unittest.main()
