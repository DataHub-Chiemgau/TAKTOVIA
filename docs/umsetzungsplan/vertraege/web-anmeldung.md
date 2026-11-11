# Detailvertrag Web-Anmeldung und Mandantenwechsel (WP-M1-44, Enabler)

Legt fest, was das WP als „Detailverträge vorab“ nennt: Sitzungs- und Tokenhaltung im Browser, Verhalten je Tab, Texte der Zustände. Gilt für `web/operator` und `web/passenger`; Baustein: `web/api-client/src/auth/`, Darstellung: `web/design-system` (`AuthStateView`, `TenantSwitcher`, `SessionList`).

## Anmeldung

- **OIDC Authorization Code mit PKCE (S256)**, öffentlicher Client (`operator-web`, `passenger-web` laut Realm-Vertrag [idp-realm.md](idp-realm.md)). `state` und `code_verifier` werden je Anmeldung zufällig erzeugt (Web Crypto), nur die Challenge verlässt den Browser. Rückkehr: `state` muss zum tabgebundenen Wert passen, sonst wird der Code nie eingelöst (`ended/error`); Code und Verifier sind einmalig.
- **Zweiter Faktor:** `operator-web` fordert bereits bei der Anmeldung `acr_values=loa2` (Administratorrollen); `passenger-web` nicht. Verlangt der Server mit 403 und `WWW-Authenticate: … insufficient_user_authentication` die höhere Stufe, endet die Anmeldung im Zustand `mfaRequired`; die Schaltfläche „Mit zweitem Faktor anmelden“ startet den Ablauf mit `loa2` (RFC 9470).
- **Konfiguration:** `VITE_OIDC_ISSUER` (Realm-URL), `VITE_OIDC_CLIENT_ID`, `VITE_API_ORIGIN`; `connect-src` der CSP enthält genau die Herkunft der API und des Anmeldedienstes.

## Sitzungs- und Tokenhaltung im Browser

| Was | Wo | Warum |
|---|---|---|
| Zugriffs- und Erneuerungstoken | **nur Arbeitsspeicher** des Tabs (kein Web-Speicher, keine Cookies, `credentials: 'omit'`) | Diebstahl über Speicher/XSS-Persistenz vermeiden; Neuladen meldet über die SSO-Sitzung des Anbieters neu an |
| PKCE-Zustand (`state`, Verifier, Rücksprungpfad) | `sessionStorage` des Tabs, bis zur Rückkehr, danach entfernt | übersteht die Weiterleitung, ist einmalig und tabgebunden |
| Mandantenbindung (`context_token`) | `sessionStorage` des Tabs | rechtefrei (Signatur, Bindung an Konto und `sid`, Ablauf); überlebt Neuladen desselben Tabs |
| Erneuerung | vor Ablauf (30 s Rand) per Refresh-Grant; scheitert sie, endet die Sitzung | Tokenlaufzeit ≤ 5 min (OP-13) |

`localStorage` und Cookies sind im Quelltext verboten (Test `design-system/src/security.test.ts`); `sessionStorage` nur in `AuthProvider.tsx` (Ausnahme dieser Tabelle). Der Laufzeittest `auth.test.tsx` belegt, dass nach der Anmeldung kein Token im Speicher steht.

## Verhalten je Tab (Kontextbindung)

- Jeder Tab hat eigene Anmeldung, eigenen Speicher und eigene Mandantenbindung. Es gibt **keinen** tabübergreifenden Zustand (kein `localStorage`, kein `BroadcastChannel`). Tab 1 bleibt in A, Tab 2 wechselt nach B; jede Anfrage trägt die Bindung ihres Tabs (`X-Taktovia-Context`), der Server bestimmt den Mandanten aus Mitgliedschaft und Bindung.
- **Mandantenwähler** (Kopfbereich, Daten aus `/v1/me.tenants`): bewusster Wechsel (Auswahl plus „Wechseln“), `POST /v1/me/tenant-switch`, Bindung setzen, Sitzung neu laden. Die Anzeige ist keine Rechtequelle.
- **Zurücksetzen beim Wechsel:** der gesamte Bildschirmbaum wird abgebaut (Schlüssel = Mandant), Listen, Auswahl und Formulare des alten Mandanten sind weg. **Ungespeicherte Eingaben** werden nie in den neuen Mandanten übernommen: Ein Dialog nennt die betroffenen Formulare, „Wechseln und Eingaben aufbewahren“ legt sie im Arbeitsspeicher unter dem alten Mandanten ab, „Hier bleiben“ ändert nichts. Beim Zurückwechseln stellt das Formular den Entwurf wieder her (Hinweis „Entwurf wiederhergestellt“). Entwürfe werden nie persistiert.
- **Manipulierte Bindung** im Speicher: der Server antwortet 403, `getMe` wird einmal ohne Bindung wiederholt (Standardmandant); es entstehen keine Rechte.

## Zustände und Texte

| Zustand | Auslöser | Titel (Katalog `auth.*`) | Aktion |
|---|---|---|---|
| `signedOut` | kein Token | „Anmeldung erforderlich“ | Anmelden |
| `signingIn` | Weiterleitung/Rückkehr | „Anmeldung läuft“ | – |
| `expired` | Token abgelaufen, Erneuerung unmöglich | „Sitzung abgelaufen“ | Anmelden |
| `revoked` | 401 trotz gültig aussehendem Token (Sitzung beendet/gesperrt, Konto gesperrt), Erneuerung scheitert oder bringt erneut 401 | „Sitzung beendet oder gesperrt“ | Anmelden |
| `mfaRequired` | 403 `insufficient_user_authentication` | „Zweiter Faktor erforderlich“ | Mit zweitem Faktor anmelden |
| `error` | Rückkehr mit Fehler/falschem `state` | „Anmeldung fehlgeschlagen“ | Anmelden |
| keine Mitgliedschaft | `/v1/me` ergibt 403 | „Keine Mitgliedschaft“ | Abmelden |

Alle Zustände zeigen keine Daten und keine Einzelheiten des Servers. Abmelden: eigene Sitzung am Server sperren (`DELETE /v1/me/sessions/{id}`), Anmeldung des Tabs beenden, zum Abmelden beim Anbieter leiten; scheitert der Serveraufruf, gilt die lokale Abmeldung trotzdem. „Alle Geräte abmelden“: `POST /v1/me/sessions/revoke-all`.

## Nachweis und Grenzen

Ausgeführt: Vitest mit lokalem OIDC-Ersatz (PKCE-Prüfung wie ein Anbieter) und Server-Ersatz mit dem dokumentierten Verhalten; das echte Serververhalten (401 bei gesperrter Sitzung, Bindung, 403 ohne Mitgliedschaft) prüfen die Backend-Tests gegen die echte Anwendung. **Nicht ausgeführt:** Browser-Ende-zu-Ende-Lauf mit echtem Keycloak und echtem Backend (AT-18/PH-AT-001 auf UI-Ebene im M1-Gate, WP-M1-45), Step-up im echten Keycloak.
