-- Rauchtest-Teil: Rollenattribute der Laufzeitverbindung (WP-M0-35). NICHT gegen eine Umgebung ausgefuehrt.
-- Aufruf unter der Runtime-Rolle; Ergebnis muss 0 Zeilen liefern, sonst Rauchtest fehlgeschlagen.
SELECT 'runtime hat Superuser/BYPASSRLS' AS verstoss
  FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)
UNION ALL
SELECT 'runtime besitzt Tabelle ' || schemaname || '.' || tablename
  FROM pg_tables WHERE tableowner = current_user;
