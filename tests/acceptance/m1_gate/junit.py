"""Liest JUnit-XML-Ergebnisse (Gradle) und Quelltextnamen der Web-Tests."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]


def load_backend(root: Path = REPO / "backend") -> dict:
    """{(Klassenkurzname, Testname ohne Klammern): 'PASSED' | 'FAILED' | 'SKIPPED'} aus allen `build/test-results/test/TEST-*.xml`."""
    out = {}
    for f in root.glob("**/build/test-results/test/TEST-*.xml"):
        try:
            tree = ET.parse(f)
        except ET.ParseError:
            continue
        for tc in tree.getroot().iter("testcase"):
            cls = tc.get("classname", "").rsplit(".", 1)[-1]
            name = re.sub(r"\(\)$", "", tc.get("name", ""))
            status = "FAILED" if tc.find("failure") is not None or tc.find("error") is not None else "SKIPPED" if tc.find("skipped") is not None else "PASSED"
            key = (cls, name)
            if out.get(key) != "FAILED":
                out[key] = status
    return out


def web_test_defined(relative: str, title: str) -> bool:
    """Prüft, dass der Web-Test mit dem Titel in der Datei steht (Ausführung: npm test im Web-Job; hier nur Verknüpfung)."""
    p = REPO / relative
    return p.is_file() and title in p.read_text(encoding="utf-8")
