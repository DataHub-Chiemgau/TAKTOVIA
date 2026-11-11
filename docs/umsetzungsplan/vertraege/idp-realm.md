# IdP-Realm, Token-Claims und Ressourcenserver (WP-M1-04, Detailvertrag)

Ergänzt [LH-6.2-IAM-002](LH-6.2-IAM-002.md) und [LH-6.2-IAM-010](LH-6.2-IAM-010.md). Claims-Schema: [schemas/token-claims.v1.schema.json](schemas/token-claims.v1.schema.json).

## Planungswerte (Beschluss offen, im Code konfigurierbar)

| Gegenstand | Wert | Quelle/Offener Punkt |
|---|---|---|
| Access-Token-Laufzeit | 300 s (Realm), Backend verlangt `exp - iat <= 900 s` (`taktovia.idp.max-token-lifetime-seconds`) | OP-13 |
| SSO-Sitzung | Leerlauf 1800 s, Höchstdauer 36000 s | OP-13 |
| Stufe 2 (`loa2`) | `loa-max-age = 0`: bei Anforderung stets neu abfragen, gilt nicht über die SSO-Sitzung hinaus | Entwurf |
| Uhrabweichung | 30 s (`clock-skew-seconds`) | Entwurf |
| JWKS-Cache / Mindestabstand bei unbekannter `kid` / Karenz bei IdP-Ausfall | 300 s / 10 s / 3600 s (`jwks-cache-seconds`, `jwks-min-refresh-seconds`, `max-stale-seconds`) | Entwurf |
| Privilegierte Rollen | `tenant_admin`, `dispatcher`, `finance` (`taktovia.idp.privileged-roles`); Realm-Attribut `taktovia.mfa.privileged_roles` muss identisch sein (Test) | OP-11, vorläufig; mindestens Administrator |
| Passwort-Hash | Argon2id, 19 456 KiB Speicher, 2 Iterationen, Parallelität 1, 32 Byte (OWASP-Mindestwerte); Policy `hashAlgorithm(argon2)`, Länge ≥ 12, `notUsername`, `notEmail`, Historie 5 | IAM-002 |
| Passwortloser Weg | WebAuthn/Passkey (Passwordless-Policy, Nutzerverifikation erforderlich), als Alternative zum Passwort im ersten Faktor; Zielgruppe und Pflicht offen | OP-12 |

## Authentisierungsstärke

`acr = loa1` (ein Faktor) oder `loa2` (zweiter Faktor). Der IdP bildet das über `acr.loa.map {loa1:1, loa2:2}` und einen Step-up-Flow ab (Stufe 1: Benutzername plus Passwort oder Passkey; Stufe 2: TOTP oder Sicherheitsschlüssel). `operator-web` fordert per `default.acr.values=loa2` immer Stufe 2 an; `amr` ist optional und wird, wenn vorhanden, auf Widerspruchsfreiheit mit `acr` geprüft.

**Durchsetzung im Backend (`security.AuthenticationFilter`):** Eine Anfrage eines Menschen, dessen Token- oder Verzeichnisrollen (Verzeichnis = später `membership.role_code`) eine privilegierte Rolle enthalten, wird ohne `acr=loa2` mit 403 `FORBIDDEN` und `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="loa2"` (Step-up nach RFC 9470) abgewiesen und als Sicherheitsereignis `security.auth.mfa_missing` in jedem im Token genannten Mandanten (höchstens 5) protokolliert; ohne Mandantenhinweis bleibt es bei einer Logzeile (OP-14). Die Entscheidung folgt nur dem signierten Token; Header und der Hinweisclaim `mfa_required` zählen nicht. Die Prüfung läuft bei jeder Anfrage, auch für Pfade ohne Vertrag.

## Dienstkonten

Clients `adapter-<typ>` (Client Credentials, `principal_type=service`). Das Backend führt je Adapter eine Obergrenze der Scopes (`taktovia.idp.service-clients.<client>=<scope> …`); eine Operation ist nur zulässig, wenn der OpenAPI-Scope der Operation im Token **und** in der Obergrenze steht. Nicht freigegebener Adapter, Pfad ohne Vertrag oder ohne Scope, Menschen-Endpunkte (`/v1/me…`, `/v1/sessions…`) und Mandantenwahl (`X-Tenant-Id`, `tenant_id`) ergeben 403; ein Token mit `sid` (Menschen-Sitzung) oder mehr als einem Mandanten ist ungültig (401).

## Nicht Gegenstand / nicht ausgeführt

- Rechte je Rolle, Mandantenwahl, Mitgliedschaft (WP-M1-06, -07), Sitzungssperre (WP-M1-10), Web-Login (WP-M1-44). Menschen werden hier nur authentisiert; Funktionsscopes für Menschen prüft die Rechtematrix.
- **Kein Keycloak gestartet** (kein Docker): Realm-Import, Anmeldung mit PKCE, Step-up und Passkey sind nicht ausgeführt. Der Realm-Export wird als Datei geprüft (`IdpRealmExportTest`); Keycloak-Syntax (Flow-Struktur, `default.acr.values`, Argon2-Provideroptionen `--spi-password-hashing--argon2--…`) folgt der Dokumentation und ist beim ersten Import abzunehmen. Ersatz für den Nachweis des Ressourcenservers: lokaler JWT-Test-Issuer mit echten Schlüsseln, JWKS-Endpunkt und einem PKCE-Stand-in (`TestIdp`).
- `iat`-, `sid`- und `acr`-Claims sowie der `principal_type`-Mapper müssen im echten Keycloak-Token erscheinen; Abweichungen werden mit dem ersten Import behoben.
