# Einspielwerkzeug für Stammdaten (WP-M5-25, LH-6.20-QAT-005)

Spielt Bediengebiet, Tarif, Betreiber, Fahrzeuge und Benutzer (dazu Dienste, Haltepunkte, Fahrer, Konfigurationserstwerte) vor Betriebsstart datenkonsistent ein und
führt ein **Migrationsprotokoll**. Verantwortlich: Rolle Plattform. Vertrag: [LH-6.20-QAT-005](../../docs/umsetzungsplan/vertraege/LH-6.20-QAT-005.md).

- **Nur fachliche Schreibwege:** das Werkzeug spricht ausschließlich die HTTP-API der Anwendung (`/v1/organizations`, `/v1/services`, `/v1/service-areas`, `/v1/stops`, `/v1/tariffs`,
  `/v1/operator/vehicle-master`, `/v1/memberships`, `/v1/drivers`, `/v1/config/revisions`). Kein SQL, kein Datenbankzugang. Es gibt keinen Importendpunkt (Kap. 05 §5.7), und es wird keiner erfunden.
- **Rechte:** je Rolle ein Bearer-Token des Zielmandanten (kein Superuser): `administrator` (tenant_admin), `planer` (planner: Tarif prüfen/veröffentlichen, Konfiguration freigeben; Vier-Augen gegenüber
  dem Anlegenden), `disponent` (dispatcher: Fahrzeugstamm), `personalplanung` (staff_planning: Fahrerstamm). Vor dem ersten Schreiben fragt das Werkzeug `/v1/me` je Zugang ab und bricht
  ab (`MANDANT_ABWEICHUNG`, Protokoll `NICHT_GESTARTET`), wenn ein Zugang nicht zum Mandanten der Quelle gehört. Alle Schreibzugriffe erscheinen in `audit_event` des Zielmandanten.
- **Aufruf:** `python tools/stammdaten_import/cli.py --quelle stammdaten.json --api <Basis-URL> --zugang-datei zugaenge.json --freigeber <Kennung> [--trockenlauf | --fortsetzen]`
  (`--build`, `--migrationsstand`, `--ausfuehrender` optional; Standard für den Ausführenden ist die Kennung des Administrator-Tokens). Ausgabe: `migrationsprotokoll.json` und Zustandsdatei.
- **Modi:** *Trockenlauf* (prüft die Quelle lokal und liest den Serverstand; schreibt nichts), *Lauf*, *Fortsetzen* (nach Unterbrechung: nur mit unveränderter Quelle, Prüfsumme). Lauf-Zustände:
  `LAEUFT`, `ABGEBROCHEN`, `ABGESCHLOSSEN` (Trockenlauf: `TROCKENLAUF`).
- **Idempotenz je Datensatz:** vor jedem Schreiben wird der Datensatz über seinen natürlichen Schlüssel auf dem Server gesucht (Betreiber `federation_ref`, Dienst Name + Betreiber, Gebiet Dienst + Beginn +
  Zonenrolle, Haltepunkt aus Mandant + Quellschlüssel abgeleitete stabile `stop_id`, Tarif Dienst + Klasse + Beginn, Fahrzeug Kennzeichen (`vehicle_label`), Benutzer Aussteller + Subject, Fahrer Mitgliedschaft,
  Konfiguration Werte + Beginn); nur Fehlendes wird angelegt. Zusätzlich trägt jedes Schreiben einen deterministischen `Idempotency-Key` (ohne Ziffern, weil die Anwendung kartenartige Ziffernfolgen abweist).
  Ein zweiter Lauf derselben Quelle ändert keine Zeile. Weicht ein vorhandener Datensatz ab (anderer Betreiber, andere Rolle, andere Betriebszeiten), wird er nicht überschrieben, sondern mit `ABWEICHEND_VORHANDEN` gemeldet.
- **Reihenfolge:** Betreiber (Rechtsträger + Organisation), Dienste (mit Betriebszeiten), Bediengebiete, Haltepunkte, Tarife (Entwurf, Prüfung, Veröffentlichung), Fahrzeuge (Stamm + Dienstzuordnung),
  Benutzer (Mitgliedschaft mit Rolle), Fahrer (Stamm + Qualifikationen), Konfigurationserstwerte (Entwurf, Wirkungsanalyse, Validierung, Freigabe, Veröffentlichung). Abweichung vom Vertragstext: Benutzer vor Fahrer,
  weil der Fahrerstamm eine Mitgliedschaft braucht.
- **Fehlerliste:** jeder abgelehnte Datensatz erscheint mit Objektart, Quellschlüssel, Grundcode (`GEOMETRIE_UNGUELTIG`, `HALTEPUNKT_AUSSERHALB_GEBIET`, `KENNZEICHEN_DOPPELT`, `TARIF_OHNE_GUELTIGKEIT`,
  `BENUTZER_OHNE_ROLLE`, `ABHAENGIG_ABGELEHNT`, `API_<Code der Anwendung>` u. a.) und kurzem Text ohne Datenwerte. Verweisende Datensätze werden als abhängig abgelehnt; alle übrigen werden unverändert geladen.
  Maßgeblich für die Geometrie bleibt die Anwendung (`ST_IsValid`, 422); der Haltepunkt-im-Gebiet-Test ist **nur** lokal (die Anwendung prüft ihn beim Anlegen nicht).
- **Migrationsprotokoll** (JSON, ein Artefakt je Lauf): Quelle (Dateiname, SHA-256, Format), Mandant, Build, Migrationsstand, Ausführender, Freigeber, je Objektart `soll`, `ist` (nach dem Lauf gegen den Serverstand
  geprüft), `angelegt`, `vorhanden`, `abgelehnt`, `differenz_erklaert` (Soll − Ist = abgelehnte Datensätze), Fehlerliste und Konsistenzregeln (keine verwaisten Referenzen, valides Polygon, Haltepunkt im Gebiet, Tarif mit
  Gültigkeit, Fahrzeug mit Betreiber/Dienst/Ressourcenvektor, Benutzer mit Rolle). Build und Migrationsstand gibt der Aufrufer an; das Werkzeug liest sie nicht aus der Datenbank.
- **Datenschutz:** Quelldateien mit Personendaten (Benutzerkennungen, Fahrername und -kontakt) und die Zugangsdatei liegen nur in der Zielumgebung, nicht im Repository und nicht in Logs. Protokoll und Logs nennen Zählwerte,
  Quellschlüssel und Kennungen, nie Kontaktdaten (zweite Sicherung: Schwärzung bekannter Werte, E-Mail-Adressen und Telefonnummern vor dem Schreiben). Quellschlüssel dürfen daher keine Personendaten enthalten.
  Die **Löschfrist der Quelldateien ist offen (D-11)** und vom Betreiber festzulegen; das Werkzeug löscht nichts selbst.

## Quellformat `taktovia-stammdaten/1` (vorläufig, OP-18)

Das Quellformat je Objektart ist im Pflichtenheft nicht festgelegt (OP-18); dies ist ein Arbeitsvorschlag als **eine JSON-Datei**. Beispiel: `tests/acceptance/fixtures/stammdaten_referenz/stammdaten.referenz.json`.
Oberste Felder: `format`, `mandant` (`tenant_id`, optional `issuer` als Standard für Benutzer) und die Listen `betreiber`, `dienste`, `bediengebiete`, `haltepunkte`, `tarife`, `fahrzeuge`, `benutzer`, `fahrer`,
`konfiguration`. Jeder Datensatz hat einen eindeutigen `key`; Verweise nennen den `key` des Ziels. Feldinhalte entsprechen den Request-Schemata der API (`api/*.yaml`) mit deutschen Verweisfeldern
(`betreiber`, `dienst`, `gebiet`, `benutzer`) und `kennzeichen` für `vehicle_label`.

## Nicht Teil des Werkzeugs

- **Anlegen von Konten im Identitätsdienst** (angelegt, eingeladen oder importiert: offen, OP-18): Benutzer werden als Mitgliedschaft mit Rolle zu einer vorhandenen Kennung (Aussteller + Subject) eingespielt.
- **Mandantenanlage** (Plattformbetrieb, `POST /v1/platform/tenants`) und **Schichten/Dienstpläne** (nicht in der Anforderung).
- **Fahrzeugfreigabe:** eingespielte Fahrzeuge sind bis zur Freigabe (Prüfung, M3) gesperrt (`dispatch_blocked`).
- Pilot-Echtdaten (D-05) und die Einspielung in die Produktionsumgebung (WP-M5-28/-29): dasselbe Werkzeug, eigenes Protokoll; nicht ausgeführt.

## Nachweis

`tests/acceptance/test_stammdaten_migration.py` (echte Anwendung als Prozess, lokaler JWT-Test-Issuer statt Keycloak) und `tools/test_stammdaten_import.py` (offline). Vorbereitung:
`cd backend && ./gradlew :app:bootJar`.
