# Seed-Verfahren Mandanten A/B (WP-M0-22, Offene Punkte 10)

**Problem.** `tenant` erzwingt RLS (`FORCE ROW LEVEL SECURITY`, Policy `own_tenant`), und die Runtime-Rolle hat auf
`tenant` nur `SELECT` (Befund B-02). Mandanten kann sie nicht anlegen. Superuser und `BYPASSRLS` sind in Tests
ausgeschlossen.

**Verfahren (umgesetzt in `taktovia_at/seed.py`, ausgeführt und geprüft).**

1. Mandantenzeilen legt die Rolle `taktovia_migrator` an (Tabelleneigentümer, kein Superuser, kein BYPASSRLS). Vor dem
   `INSERT` setzt sie transaktionslokal `app.tenant_id` auf die neue Mandanten-ID. Die Policy `own_tenant`
   (`WITH CHECK id = current_tenant()`) lässt dann genau diese eine Zeile zu. Eine zweite Mandanten-ID lässt sie nicht zu.
2. Alle weiteren Daten (`legal_entity`, `organization`, `membership`, `passenger_private`, `vehicle`,
   `expense_document`, `cost_object`) legt die Runtime-Rolle unter dem Kontext des jeweiligen Mandanten an.
3. Der Seed ist idempotent (`ON CONFLICT DO NOTHING`) und liefert ein Protokoll mit den verwendeten Rollen
   (`tenant_creation_role`, `data_role`) und `privileged` (muss `false` sein). Der Rauchtest prüft das.

**Offen für M1.** Der Plattformverwaltungsweg für Mandanten (Anlegen, Auflisten) ist nicht festgelegt. Der Migrator-Weg gilt
nur für Testumgebungen und ist kein Vorschlag für den Betrieb.

**Datenstand.** `data_state_sha256` ist der SHA-256 über die Zeilen mit den Seed-IDs (aus `row_to_json`, sortiert).
Weitere Zeilen anderer Szenarien fließen nicht ein.

**Identitäten.** Mandanten-IDs und Benutzernamen folgen `infra/keycloak` (WP-M0-09). Als `membership.subject` steht bis zum
Import in Keycloak der Benutzername (`a.dispatcher`); die echte `sub` kennt erst ein laufender Keycloak. Das ist eine
Abweichung vom Endzustand und in M1 mit dem Tokenbezug zu ersetzen. Es gibt nur synthetische Daten; `contact_ciphertext` und
`support_ciphertext` bleiben leer.
