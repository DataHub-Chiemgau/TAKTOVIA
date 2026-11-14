-- WP-M4-66: Anbindung des ETA-Grundmodells an die Angebotsbildung. Jedes Angebot trägt genau eine Modell-/Konfigurationsversion (aus einer Lesetransaktion der Prognose gelesen),
-- die Sicherheitsreserve der Fenster und, wenn das Grundmodell keine Prognose liefern konnte, den Rückfallgrund (Fenster dann aus der Planungsannahme, ohne Modellversion).
SET search_path = taktovia, public;

ALTER TABLE ride_offer
  ADD COLUMN eta_model_version   integer CHECK (eta_model_version IS NULL OR eta_model_version >= 1),
  ADD COLUMN eta_config_version  text    CHECK (eta_config_version IS NULL OR length(eta_config_version) BETWEEN 1 AND 64),
  ADD COLUMN eta_buffer_seconds  integer NOT NULL DEFAULT 0 CHECK (eta_buffer_seconds >= 0),
  ADD COLUMN eta_fallback_reason text    CHECK (eta_fallback_reason IS NULL OR length(eta_fallback_reason) BETWEEN 1 AND 64);
-- Modell- und Konfigurationsversion gehören zusammen; ein Rückfallgrund schließt ein ETA-Fenster aus
ALTER TABLE ride_offer ADD CONSTRAINT ride_offer_eta_version_pair CHECK ((eta_model_version IS NULL) = (eta_config_version IS NULL) OR eta_fallback_reason IS NOT NULL);
