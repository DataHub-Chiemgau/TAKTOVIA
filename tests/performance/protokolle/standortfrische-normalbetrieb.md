# Messprotokoll Standortfrische (WP-M5-08, LH-6.19-NFR-005)

- Zielwert: frische Positionen jünger als 30 s im Normalbetrieb (**vorläufig, D-05 offen**)
- Messpunkt: Standortalter der Fahrzeugliste (UI-07), vom Server gegen seine Uhr berechnet; Erfassung = Messzeit des Geräts, Empfang = Serverempfangszeit
- Normalbetrieb: Fahrzeuge mit laufendem Positionsstrom (Meldeintervall des Lastprofils, Standard 15 s); Planungsannahme bis D-05/OP-11

## Standortalter je Stufe (Normalbetrieb)

| Stufe | Beobachtungen | ohne Position | Alter p50 s | Alter p95 s | Alter max s | Anteil > 30 s | Überschreitungen | Erfassung→Empfang p95 s | als veraltet gekennzeichnet |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Normalbetrieb (Referenz) | 6223 | 147 | 7.0 | 14.0 | 28.0 | 0.0 | 0 | 0.121 | 0 |

## Lücken im Normalbetrieb

1 Lücken (Abstand aufeinanderfolgender Messzeiten eines Fahrzeugs > 30 s), größte 30.1 s.

## Unterbrochene Ströme (künstlich veraltet)

10 Fahrzeuge ohne Meldung ab Aufwärmende; 80 von 80 Beobachtungen über der Schwelle waren als veraltet gekennzeichnet; Messzeit unverändert (keine Fortschreibung) bei 10 von 10 Fahrzeugen.

## Reihenfolge und Datentrennung

Rückgang der angezeigten Messzeit: 0 (Fahrzeuge mit verspäteter älterer Meldung: 10). Fremde Fahrzeuge in der Anzeige je Mandant: {'A': 0, 'B': 0}.

## Negativproben unter Last (Zähler)

```
{'pos_probe_fremdes_fahrzeug_404': 10, 'pos_probe_verspaetet_200': 50}
```

Grenzen: Generator, Anwendung, PostgreSQL und Test-Issuer teilen sich eine Maschine; Werte gelten für diese Umgebung, nicht als Abnahme (D-05 offen). Sendeintervall und Schwelle sind Planungsannahmen (D-05 GPS-Frequenz, OP-11).
