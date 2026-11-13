"""AT-59 „Standortdaten minimieren“ im P1-Mindestumfang (WP-M5-20, OP-13): GPS-Stempeln ohne Freigabe und Medienbilder.

Datenbankteil unter der Runtime-Rolle auf der Zwei-Mandanten-Fixture; HTTP-, Dienst- und Bildprüfungen werden über ihre JUnit-Ergebnisse verknüpft (./gradlew build zuerst; ein fehlendes oder
rotes Ergebnis macht das Szenario rot). **Nicht Teil der Prüfung (nicht ausgeführt):** die Standortberechtigung der App beim Stempeln (kein Flutter in der Umgebung, `mobile/` fragt keine Standortrechte
an), das standortgestützte Stempeln selbst (P2, WP-M7d-40) und die EXIF-Minimierung per Browser/Gerät (P2-Lieferung WP-M7c-27)."""
import sys
import uuid
from pathlib import Path

import psycopg

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent / "m1_gate"))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at.scenario import scenario  # noqa: E402
from m1_gate.scenarios import BACKEND_RESULTS, backend  # noqa: E402,F401

KEYS = ["ENABLER:WP-M5-20"]


@scenario("AT-59", "1.0.0", KEYS, "Standortdaten minimieren (P1-Mindestumfang)")
def at_59(ctx):
    a = ctx.ids["A"]["tenant"]
    ctx.use_role("driver", "A", ctx.identities["A"]["driver"] if "driver" in ctx.identities["A"] else ctx.identities["A"]["dispatcher"])
    with atdb.tx(ctx.db_name, a) as c:
        who = c.execute("SELECT current_user, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user").fetchone()
        ctx.check("Rolle der Prüfungen: Runtime, kein Superuser, kein BYPASSRLS", ("taktovia_runtime", False, False), tuple(who))
        # 1. Zeitereignis: kein Standortfeld (Struktur), kein standortgestütztes Stempeln in P1
        cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='time_event' AND column_name ~* '(lat|lon|geo|location|position|gps|coord)'")]
        ctx.check("time_event hat kein Standortfeld", [], cols)
        # 2. Konfiguration: Geozone standardmäßig aus, Freigabeverweis als eigener Parameter
        row = c.execute("SELECT default_value::text, value_type, allowed_levels::text FROM taktovia.policy_schema WHERE parameter='clock_in_geofence_enabled' AND schema_version='1'").fetchone()
        ctx.check("clock_in_geofence_enabled: Standardwert false, boolean, nur Mandantenebene", ("false", "boolean", '["TENANT"]'), tuple(row) if row else None)
        ref = c.execute("SELECT value_type FROM taktovia.policy_schema WHERE parameter='clock_in_geofence_release_ref' AND schema_version='1'").fetchone()
        ctx.check("Freigabeverweis clock_in_geofence_release_ref ist als Parameter vorhanden", ("string",), tuple(ref) if ref else None)
        # 3. position_sample nur während einer zugeordneten Schicht: Schicht ist Pflicht und Fremdschlüssel; eine Position ohne bzw. mit unbekannter Schicht wird abgewiesen
        notnull = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='position_sample' AND column_name='shift_id'").fetchone()[0]
        ctx.check("position_sample.shift_id ist Pflicht", "NO", notnull)
        vehicle = c.execute("SELECT id FROM taktovia.vehicle LIMIT 1").fetchone()
        ctx.check("Fixture enthält ein Fahrzeug (Voraussetzung der Prüfung)", True, vehicle is not None)
        c.execute("SAVEPOINT s")
        outcome = "ANGENOMMEN"
        try:
            c.execute("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,created_by) VALUES (%s,%s,%s,%s,ST_SetSRID(ST_MakePoint(13.7,51.05),4326)::geography,5,now(),'user:at59')",
                      (a, uuid.uuid4(), vehicle[0] if vehicle else uuid.uuid4(), uuid.uuid4()))
        except psycopg.errors.ForeignKeyViolation as e:
            outcome = "ABGEWIESEN (Fremdschlüssel Schicht)" if "shift" in str(e).lower() else f"ABGEWIESEN aus anderem Grund: {e}"
        except psycopg.errors.Error as e:
            outcome = f"ANDERER FEHLER: {type(e).__name__}"
        c.execute("ROLLBACK TO SAVEPOINT s")
        ctx.check("Position ohne zugeordnete Schicht: Datenbank weist ab (Fremdschlüssel auf die Schicht)", "ABGEWIESEN (Fremdschlüssel Schicht)", outcome)
    backend(ctx,
            # Stempeln ohne Standort, Koordinaten verworfen, nirgends gespeichert, nie geloggt (Kriterien 1, 6; negativ)
            ("LocationMinimizationApiTest", "stempeln_ohne_standort_kein_standortfeld_in_zeitereignis_und_antwort"),
            ("LocationMinimizationApiTest", "koordinaten_trotz_deaktivierter_geozone_werden_verworfen_nirgends_gespeichert_und_nie_geloggt"),
            ("LocationMinimizationApiTest", "unbekannte_standortfelder_ausserhalb_des_dokumentierten_blocks_werden_mit_422_abgewiesen_und_nichts_gespeichert"),
            # Freigabe der Geozone und Wirksamkeitszeitpunkt (Kriterium 2, konkurrierend/Sicherheit)
            ("LocationMinimizationApiTest", "aktivierung_der_geozone_ohne_dokumentierte_freigabe_wird_von_der_validierung_abgewiesen"),
            ("LocationMinimizationApiTest", "konfigurationswechsel_der_geozone_wirkt_erst_ab_dem_wirksamkeitszeitpunkt"),
            # Bilder: Metadatenvergleich, Nachweiskraft (Kriterien 3, 7)
            ("ImageMetadataMinimizerTest", "jpegMitGpsExifUndVorschauSamtGpsIstNachDerBereinigungOhneStandortUndGeraetemetadaten"),
            ("ImageMetadataMinimizerTest", "pngMitExifChunkUndTextChunkIstNachDerBereinigungSauber"),
            ("DefectEvidenceMetadataTest", "abrufbare_fassung_ist_im_metadatenvergleich_ohne_standort_und_geraetemetadaten"),
            ("ReceiptDeliveryVersionTest", "ansichtsfassung_ist_ohne_standort_und_geraetemetadaten_das_original_bleibt_mit_hash_unveraendert"),
            ("ReceiptViewApiTest", "ansicht_ist_ohne_standort_und_geraetemetadaten_das_original_bleibt_unveraendert_mit_hash"),
            # position_sample nur während zugeordneter Schicht (Kriterium 4)
            ("PositionCaptureTest", "position_ohne_dienst_wird_nicht_angenommen_oder_gespeichert"))
