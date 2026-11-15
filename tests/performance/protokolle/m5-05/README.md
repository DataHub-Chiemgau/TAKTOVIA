# Nachweis WP-M5-05: Proben gegen die lokal gestartete Anwendung

Erzeugt von `tests/performance/availability/test_probe_gegen_anwendung.py` (`TAKTOVIA_PERF_RUN=1`, `TAKTOVIA_PROBE_BERICHT_OUT=<dieses Verzeichnis>`): echte HTTP-Proben (Anmeldung am lokalen JWT-Test-Issuer, Angebot,
Hold, Bestätigung, Storno; `/v1/me`; `/v1/operator/vehicles`) gegen das Spring-Jar mit Probemandant. Der Ausfall ist echt (Anwendungsprozess per SIGSTOP angehalten: 3 Minuten außerhalb, 2 Minuten innerhalb des
vorab eingetragenen Wartungsfensters), die **Zeitachse ist virtuell** (ein Lauf je virtuelle Minute). Ersatz für Staging und Keycloak; **kein** Produktivmonat (OP-02), Zielwerte vorläufig (D-05 offen).
