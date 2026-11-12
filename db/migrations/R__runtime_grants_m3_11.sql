-- WP-M3-11: Pflichtschlüssel je Vorlagentyp; der Wächter checklist_template_guard ruft die Funktion unter der Runtime-Rolle auf.
GRANT EXECUTE ON FUNCTION taktovia.checklist_mandatory_keys(text) TO taktovia_runtime;
