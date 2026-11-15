-- taktovia:no-transaction
-- WP-M5-11 / LH-6.19-NFR-002: nachgezogener Index aus den Messbefunden des Lastlaufs (tests/performance/test_standard_api_p95.py), expand/contract ohne buchungsblockierende Sperre:
-- CREATE INDEX CONCURRENTLY nimmt nur eine ShareUpdateExclusiveLock (Schreibzugriffe auf `booking` laufen weiter) und läuft deshalb ausserhalb einer Transaktion (Marker oben, db.py).
-- Die Datei ist idempotent (IF NOT EXISTS); ein abgebrochener Lauf hinterlässt einen ungültigen Index, den ein erneutes Migrieren NICHT ersetzt: `DROP INDEX CONCURRENTLY taktovia.booking_by_created`, dann erneut migrieren.
--
-- Befund (Auftragsübersicht `GET /v1/operator/bookings`, Seite 1 bis n, limit 100): die Seite sortiert nach (created_at, id) über mehrere Dienste (`service_id = ANY(...)`); der Index booking_by_service
-- (tenant_id, service_id, created_at, id) liefert über mehrere Dienste keine Gesamtordnung, der Plan las alle Buchungen der Dienste (Bitmap Scan, 1.500 Zeilen je Mandant im Lauf) und sortierte sie je
-- Seite (Top-N-Sort): 4,2 ms und 280 Puffer je Seite bei 4.500 Buchungen, wachsend mit dem Bestand. Mit dem Index liest der Plan die Seite in Schlüsselreihenfolge (101 Zeilen, 0,9 ms, 909 Puffer
-- inklusive der Verknüpfungen), unabhängig vom Bestand. Dieselbe Sortierung nutzt die Buchungsübersicht der Betriebsrollen (`GET /v1/bookings` ohne Fahrgastsicht).
CREATE INDEX CONCURRENTLY IF NOT EXISTS booking_by_created ON taktovia.booking (tenant_id, created_at, id) WHERE booking_no IS NOT NULL;
