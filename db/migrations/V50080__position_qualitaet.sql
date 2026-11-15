-- WP-M5-08 (LH-6.19-NFR-005): Qualitätsflag je Positionsmeldung. position_sample trug Messzeit (Gerät, `measured_at`) und Empfangszeit (Server, `received_at`) bereits getrennt und NOT NULL (V3020);
-- neu ist die Pflichtangabe `quality`. Wertebereich (Detailvertrag LH-6.19-NFR-005, OP-11 offen, daher vorläufig):
--   GOOD          Messzeit plausibel, Genauigkeit bis zur Schwelle (Konfiguration `taktovia.stafftime.position-low-accuracy-meters`, Planungsdefault 100 m);
--   LOW_ACCURACY  Messzeit plausibel, Genauigkeit schlechter als die Schwelle: wird als gemessener Punkt geführt und angezeigt, aber mit dem Hinweis geringer Genauigkeit;
--   UNUSABLE      nicht verwendbar: Messzeit in der Zukunft über die Toleranz hinaus (grobe Uhrenabweichung des Geräts; GPS-Messzeit ist keine vertrauenswürdige Serverzeit, Kap. 01 §1.5).
--                 Der Punkt bleibt als Nachweis der Abweichung gespeichert, wird aber nie als „letzte Position“ geliefert und nie als frisch angezeigt.
-- Das Flag hat KEINEN Standardwert: ein Schreibpfad, der es nicht setzt, wird abgewiesen (NOT NULL). Bestehende Zeilen wurden bis hierher nach Messzeit/Toleranz angenommen und gelten als GOOD
-- (der Standardwert gilt nur für die Umstellung und wird sofort wieder entfernt; UPDATE auf die Tabelle ist per Trigger verboten, daher kein Nachziehen je Zeile).
SET search_path = taktovia, public;

ALTER TABLE position_sample ADD COLUMN quality text NOT NULL DEFAULT 'GOOD';
ALTER TABLE position_sample ALTER COLUMN quality DROP DEFAULT;
ALTER TABLE position_sample ADD CONSTRAINT position_sample_quality_domain CHECK (quality IN ('GOOD', 'LOW_ACCURACY', 'UNUSABLE'));
