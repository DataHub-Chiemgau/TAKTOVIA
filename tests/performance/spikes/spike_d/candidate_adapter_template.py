"""VORLAGE für den Wegwerf-Adapter zu einer realen PSP-Kandidaten-Sandbox (WP-M0-29). NICHT AUSGEFÜHRT: Es gibt keinen benannten Kandidaten
und keinen Sandbox-Zugang (Beistellung des Auftraggebers, D-03), und externe Hosts sind in der Entwicklungsumgebung nicht erreichbar.

Vorgehen nach Benennung eines Kandidaten:
  1. Diese Klasse ausfüllen (HTTP-Aufrufe der Sandbox-API, Abbildung der Anbieterzustände auf die Zustände des Ports, Fehlerabbildung auf
     Unknown/Rejected). Zugangsdaten ausschließlich aus Umgebungsvariablen (siehe unten), nie im Repository.
  2. python tests/performance/spikes/spike_d/conformance.py --adapter candidate_adapter_template:CandidateAdapter --label <Kandidat>
     (Umgebungsvariablen PSP_CANDIDATE_BASE_URL, PSP_CANDIDATE_API_KEY aus dem Secret-Verfahren des Auftraggebers; Testkonten und
     Testzahlungsmittel des Anbieters verwenden).
  3. Ergebnis in die Fähigkeitsmatrix und die Bewertung übernehmen (spike-d-psp-sandbox.md).
"""
import os

from .psp_port import PspPort


class CandidateAdapter(PspPort):
    name = "kandidat-unbenannt"

    def __init__(self):
        self.base_url = os.environ.get("PSP_CANDIDATE_BASE_URL")       # Sandbox-Basis-URL des Kandidaten
        self.api_key = os.environ.get("PSP_CANDIDATE_API_KEY")         # Testschlüssel; nie im Repository
        if not self.base_url or not self.api_key:
            raise RuntimeError("PSP_CANDIDATE_BASE_URL und PSP_CANDIDATE_API_KEY fehlen: Kandidat nicht benannt, nicht ausführbar")

    def _todo(self):
        raise NotImplementedError("Kandidatenadapter nicht implementiert")

    authorize = capture = void = refund = status_by_merchant_ref = status = lambda self, *a, **k: self._todo()
