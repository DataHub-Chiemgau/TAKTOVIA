-- WP-M1-33: Rechte für Aufbewahrungsregeln, Löschläufe und das Lösch-/Sperrjournal. Der Dateiname sortiert hinter R__runtime_grants.sql (REVOKE ALL).
-- Regel und Job sind änderbar (Zustand, Zähler), nie löschbar; das Journal ist append-only (Trigger erasure_journal_immutable) und ohne UPDATE/DELETE-Recht.
GRANT SELECT, INSERT, UPDATE ON taktovia.retention_rule, taktovia.erasure_job TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.erasure_journal TO taktovia_runtime;

-- Fahrgastkonto: Archiv- und Löschzustand (Spaltenrechte; die Ciphertext-Spalten bleiben dem Modulzugang vorbehalten, das Leeren beim Löschzustand
-- erledigt der Trigger passenger_private_erasure).
GRANT SELECT (archived_at, archive_until, erased_at) ON taktovia.passenger_private TO taktovia_runtime;
GRANT UPDATE (archived_at, archive_until, erased_at) ON taktovia.passenger_private TO taktovia_runtime;

-- Der Löschlauf entfernt Kontaktkanäle und Verifikationsaufgaben eines gelöschten Fahrgasts (Präferenzen: DELETE bereits seit WP-M1-29).
GRANT DELETE ON taktovia.passenger_contact, taktovia.contact_verification TO taktovia_runtime;

-- Das Ausführungsrecht der Wiederherstellungsfunktion liegt bei der Betriebsrolle, nie bei der Runtime-Rolle.
REVOKE ALL ON FUNCTION taktovia.replay_erasure_journal(jsonb) FROM taktovia_runtime;
