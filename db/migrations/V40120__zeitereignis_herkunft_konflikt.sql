-- WP-M4-12 / LH-6.24-TIM-003: Herkunft und Konfliktmarke am Zeitereignis. Die stabile Ereignis-ID `device_event_id` der App ist die Spalte `id` (PRIMARY KEY (tenant_id, id)): die
-- Deduplikation gilt dauerhaft und hängt nicht am 72-h-Response-Cache des Idempotenzregisters (Kap. 05 §5.1). Gerätezeit (`device_recorded_at`) und Serverempfangszeit (`received_at`) gibt es seit V40080.
-- `origin`: OFFLINE = in der lokalen Queue der App erfasst und nach Wiederverbindung übertragen, ONLINE = unmittelbar erfasst. `conflict_flag`: möglicher Konflikt (Klärung erst über den
-- Korrekturantrag TIM-007, M7); das Ereignis wird nie still korrigiert oder verworfen und bleibt wie alle Zeitereignisse unveränderlich.
SET search_path = taktovia, public;

ALTER TABLE time_event ADD COLUMN origin text NOT NULL DEFAULT 'ONLINE' CHECK (origin IN ('ONLINE', 'OFFLINE'));
ALTER TABLE time_event ADD COLUMN conflict_flag text CHECK (conflict_flag IS NULL OR conflict_flag IN ('CLOCK_SKEW', 'INVALID_ORDER'));
-- Nacherfassung ist nie offline und nie markiert (kein Gerät beteiligt).
ALTER TABLE time_event ADD CONSTRAINT time_event_backfill_online CHECK (source <> 'BACKFILL' OR (origin = 'ONLINE' AND conflict_flag IS NULL));
-- Zustandsprüfung „offene Arbeitszeit“ je Person und Zeitpunkt: Index über (Person, Ereigniszeit) existiert (time_event_by_person).
