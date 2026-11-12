-- WP-M3-21: Regelfunktion der Altschäden (Anzeige, Quittierung); die Tabelle known_defect_acknowledgement hat ihre Rechte in R__runtime_grants_m3_02.sql.
GRANT EXECUTE ON FUNCTION taktovia.inspection_known_defects(uuid, uuid) TO taktovia_runtime;
