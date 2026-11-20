import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SVG = "{http://www.w3.org/2000/svg}"
SEQUENCES = (
    "advisory_sequence",
    "billing_sequence",
    "event_delivery",
    "execution_overview",
    "execution_sequence",
    "migration",
    "observe_detect",
    "onboarding",
    "recovery_validation",
)


class SequenceLayoutTests(unittest.TestCase):
    def test_generated_sequence_arrows_clear_every_label(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "diagrams").mkdir()
            shutil.copy(ROOT / "SERVICE_CATALOG.json", root)
            script = root / "tools" / "build_diagrams.py"
            shutil.copy(ROOT / "tools" / "build_diagrams.py", script)
            subprocess.run([sys.executable, str(script)], check=True, capture_output=True)

            for name in SEQUENCES:
                with self.subTest(diagram=name):
                    diagram = ET.parse(root / "diagrams" / f"{name}.svg").getroot()
                    labels = [
                        float(text.attrib["y"])
                        for text in diagram.iter(f"{SVG}text")
                    ]
                    arrows = [
                        path.attrib["d"]
                        for path in diagram.iter(f"{SVG}path")
                        if path.attrib.get("marker-end") == "url(#arrow)"
                    ]
                    self.assertTrue(arrows)
                    for arrow in arrows:
                        match = re.fullmatch(
                            r"M[\d.]+,([\d.]+) L[\d.]+,([\d.]+)", arrow
                        )
                        self.assertIsNotNone(match, arrow)
                        arrow_y = float(match.group(1))
                        self.assertEqual(arrow_y, float(match.group(2)))
                        self.assertFalse(
                            any(baseline - 20 <= arrow_y <= baseline + 8 for baseline in labels),
                            f"{name}: arrow at y={arrow_y} intersects a text line",
                        )


if __name__ == "__main__":
    unittest.main()
