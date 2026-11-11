# Detailvertrag Identität: Mitgliedschaft, Anfragekontext, Tenantwechsel, Mandanten-Lookup (WP-M1-06)

Zu `LH-I.4-MUL-006` und `LH-6.17-INT-002`. Dieser Vertrag legt fest, was die Verträge dieser Anforderungen als „Detailvertrag vorab“ offen ließen (Tenantwechsel-Operation, Token-/Sitzungsbindung, Lookup vor dem Tenantkontext). Alle Festlegungen sind **Entscheidungen des Plans für den Bau**; die fachliche Freigabe von OP-04 und OP-06 steht aus.

## Anfragekette (`backend/app`)

Correlation -> Header/Pfad/Herkunft -> **Token-Prüfung** (`AuthenticationFilter`, WP-M1-04) -> Quote -> **`RequestContextFilter`** -> Eingabeprüfung (inkl. `TenantClaimInspector` für den Body) -> Controller. Ohne Token 401. Der Filter bildet den unveränderlichen `platform.context.RequestContext` (Akteur, Mandant, Funktionsscopes, Dienste, Zweck, Vertragsrevision leer, Mitgliedschafts-IDs) oder lehnt ab:

| Lage | Antwort |
|---|---|
| keine aktive Mitgliedschaft im Mandanten | 403 `FORBIDDEN`, ohne Details; Sicherheitsereignis nur als Logzeile (kein Mandant) |
| Operation verlangt einen Scope, den die Rollen nicht liefern | 403 `FORBIDDEN`, ohne Details; `security.access.denied` im Mandanten des Akteurs |
| Operation ohne deklarierten Scope | 403 (deny-by-default) |
| `tenant_id` in Header (`X-Tenant-Id`, `Tenant-Id`, …), Query, Pfad oder Body, das vom aktiven Mandanten abweicht | **404** wie „nicht vorhanden“ (identischer Problem-Körper), `security.tenant.mismatch` im aktiven Mandanten; der Fremdwert wird nie gespeichert oder zurückgegeben |
| gefälschte/fremde/falsch gebundene Kontextbindung | 403, `security.tenant.mismatch` nicht nötig (nur Logzeile ohne Werte); bloßer Ablauf: 403 ohne Ereignis |
| Dienstkonto | Mandant = Bindung im Token (höchstens einer), Scopes = Token-Scopes ∩ Obergrenze des Adapters; keine Menschen-Endpunkte, kein Wechsel (WP-M1-04) |

Angaben des Clients über den Mandanten verleihen nie Rechte; die gleiche Mandanten-ID wie der aktive Mandant ist wirkungslos, aber unschädlich. Ausnahme ist nur die Auswahl-Operation `switchTenant` (`@TenantSelectionRequest`): dort ist `tenant_id` der **Wunsch**, den die Mitgliedschaftsprüfung entscheidet.

## Aktiver Mandant und Tenantwechsel

- **Ohne Auswahl:** Standardmandant = kleinste Mandanten-ID (Textform) unter den aktiven Mitgliedschaften des Subjects. Kein Mandant: 403.
- **`POST /v1/me/tenant-switch`** `{tenant_id, service_id?}`: prüft eine **aktuelle** Mitgliedschaft im Zielmandanten (Scope `tenant:switch`, Rollen des aktuellen Kontexts) und bei `service_id` die Dienstfreigabe in `membership.service_scope`. Erfolg: 200 mit `Me` des Zielmandanten, dazu `context_token` und `context_expires_at`, Auditereignis `identity.tenant.switched` im Zielmandanten. Verweigerung (keine Mitgliedschaft, nicht existierender Mandant, Dienst nicht freigegeben): **einheitlich 403** (nicht unterscheidbar) und `security.access.denied` im aktuellen Mandanten, ohne den Zielmandanten zu nennen. (Der frühere Beschreibungstext der Schema-Beschreibung „sonst 404“ widersprach `LH-6.17-INT-002` und wurde auf 403 korrigiert.)
- **Kontextbindung (`context_token`, Header `X-Taktovia-Context`):** vom Server signiert (HMAC-SHA256, `taktovia.identity.context-secret`), gebunden an Issuer, Subject und IdP-Sitzung (`sid`) des Tokens, mit Ablauf (`taktovia.identity.context-ttl-seconds`, Standard 3600, Planungsdefault). Sie hält nur die Auswahl fest und **verleiht keine Rechte**: Bei jeder Anfrage wird die Mitgliedschaft neu aus der Datenbank geprüft. Mehrere Tabs derselben Sitzung halten je ein eigenes Ticket und bleiben unabhängig. Ein neues Token derselben Sitzung (Refresh) behält die Bindung; ein anderes Konto oder eine andere Sitzung nicht.
- **Widerruf:** Nach `revoked_at` (oder Ablauf `valid_to`, gesperrtem Mandanten) endet der Zugriff mit der nächsten Anfrage. Zusätzlich prüft `ContextTransactions` in der **Fachtransaktion** erneut und sperrt die Mitgliedschaftszeilen bei schreibenden Anfragen mit `FOR SHARE`: ein gleichzeitiger Widerruf wartet auf laufende Anfragen, eine später beginnende sieht ihn. So ist „keine erfolgreiche schreibende Anfrage nach dem Commit des Widerrufs“ linearisiert.

## Mitgliedschaft (OP-04)

`valid_from`, `valid_to` (zeitliche Begrenzung), `revision` (+1 je Änderung, `If-Match`), `created_at`. Aktiv = `revoked_at IS NULL AND valid_from <= now AND (valid_to IS NULL OR valid_to > now)` und Mandant `ACTIVE`. Identität (Issuer, Subject, Rolle) ist nach dem Erteilen unveränderlich, ein Widerruf endgültig (DB-Trigger `membership_guard`, auch für die Administration). **`service_scope = NULL` bedeutet keine Dienstfreigabe (fail-closed)**; eine Freigabe ist immer eine ausdrückliche Liste. Mehrere Mitgliedschaften (Rollen) eines Subjects in einem Mandanten werden zusammengefasst (Rollen vereinigt, Dienstlisten vereinigt).

## Lookup „Mandanten eines Subjects“ ohne Allzweck-Definer (OP-06)

Tabelle `membership_subject_index(tenant_id, issuer, subject)` mit RLS: **lesbar nur**, wenn der transaktionslokale Schlüssel `app.subject_key` (`issuer || E'\n' || subject`, gesetzt von `TenantTransactions.runForSubject` aus dem verifizierten Token) der Zeile entspricht; **schreibbar nur** im Tenantkontext des eigenen Mandanten (Trigger `membership_index_subject` beim Erteilen, nie Löschen). Der Index ist ein Kandidatenverzeichnis: Gültigkeit und Rolle prüft der Server danach je Mandant aus `membership`. Keine View, keine `SECURITY DEFINER`-Funktion (Test `test_kein_definer_und_keine_view`), kein Aufzählen fremder Mandanten (Tests mit fremdem/unbekanntem Subject, anderem Issuer, Präfix, ohne Schlüssel).

## Mehrfaktor-Hook

`MembershipRoleDirectory` liefert die Rollen aus Mitgliedschaften an `PrivilegedRoleDirectory` (WP-M1-04): eine privilegierte Rolle, die nur in `membership.role_code` steht, unterliegt derselben Pflicht zum zweiten Faktor; `security.auth.mfa_missing` wird auch in den Mandanten der Mitgliedschaften geschrieben.

## Ereignisse und Audit

`membership.granted` (Revision 1) und `membership.revoked` (Revision > 1) in der Outbox derselben Transaktion (Schemas `api/events/membership.*.v1.schema.json`); Audit `identity.membership.granted|revoked`, `identity.tenant.switched`, Sicherheitsereignisse `security.tenant.mismatch`, `security.access.denied`.

## Grenzen / offen

- OP-04 und OP-06 (fachliche Freigabe) sowie die Wahl des Standardmandanten bleiben Entscheidungen der Fachanalyse.
- Idempotenzregister (WP-M1-13): `Idempotency-Key` wird vom Vertrag verlangt, aber noch nicht serverseitig dedupliziert; doppeltes Erteilen wird durch UNIQUE als 409 abgewiesen, Wiederholen von `DELETE` liefert 409/412.
- Mandantenanlage und erste Administrator-Mitgliedschaft: WP-M1-05 (OP-36). Sitzungssperre: WP-M1-10 (bis dahin wirkt der Widerruf über die Mitgliedschaft und die kurze Tokenlaufzeit).
- Der Kontextbindungsschlüssel muss bei mehreren Instanzen konfiguriert sein (sonst zufällig je Instanz).
