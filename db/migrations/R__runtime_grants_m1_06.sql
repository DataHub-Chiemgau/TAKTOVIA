-- WP-M1-06: Rechte des Verzeichnisindex. Der Dateiname muss hinter R__runtime_grants.sql sortieren (diese Datei beginnt mit REVOKE ALL), daher R__runtime_grants_m1_06. Der Index wird nur angehängt und gelesen.
GRANT SELECT, INSERT ON taktovia.membership_subject_index TO taktovia_runtime;
