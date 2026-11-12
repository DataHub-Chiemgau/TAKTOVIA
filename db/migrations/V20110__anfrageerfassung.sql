-- WP-M2-11 / LH-6.4-BKG-001: Anfrageerfassung. `travel_attempt` (Kernschema V1) wird additiv um die für die Nachfrageauswertung nötigen Merkmale erweitert
-- (expand/contract, Altzeilen bleiben gültig). Der Versuch enthält nie exakte Adressen, Koordinaten oder Personenbezug: Orte nur als Zonen-/Gebietskennung
-- (`coarse_origin`/`coarse_destination`, OP-13 Arbeitsannahme), der Wiederholungsschutz nur als Hash.
SET search_path = taktovia, public;

ALTER TABLE travel_attempt
  ADD COLUMN service_id          uuid,
  ADD COLUMN time_kind           text    CONSTRAINT travel_attempt_time_kind CHECK (time_kind IN ('DEPARTURE', 'ARRIVAL')),
  ADD COLUMN booking_kind        text    CONSTRAINT travel_attempt_booking_kind CHECK (booking_kind IN ('IMMEDIATE', 'ADVANCE')),
  ADD COLUMN desired_at          timestamptz,
  ADD COLUMN passengers          integer CONSTRAINT travel_attempt_passengers CHECK (passengers BETWEEN 1 AND 99),
  ADD COLUMN wheelchair_spaces   integer CONSTRAINT travel_attempt_wheelchair CHECK (wheelchair_spaces BETWEEN 0 AND 20),
  -- RECORDED = Altzeile ohne Ergebnis; OFFERED = Zeitfenster geliefert; REJECTED = fachlich abgelehnt (Primärgrund Pflicht); TECHNICAL_ERROR = technischer Fehler, nie eine fachliche Ablehnung
  ADD COLUMN outcome             text    NOT NULL DEFAULT 'RECORDED' CONSTRAINT travel_attempt_outcome CHECK (outcome IN ('RECORDED', 'OFFERED', 'REJECTED', 'TECHNICAL_ERROR')),
  ADD COLUMN request_scope       char(64) CONSTRAINT travel_attempt_request_scope CHECK (request_scope IS NULL OR request_scope ~ '^[0-9a-f]{64}$'),
  ADD COLUMN request_fingerprint char(64) CONSTRAINT travel_attempt_request_fingerprint CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$'),
  -- Ergebnis der Anfrage (Zeitfenster und Ablehnungsgrund, nie Orte oder Personen): eine Wiederholung liefert genau dieses Ergebnis
  ADD COLUMN result              jsonb,
  ADD CONSTRAINT travel_attempt_service_fk FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id),
  ADD CONSTRAINT travel_attempt_rejection_consistent CHECK ((outcome = 'REJECTED') = (primary_rejection IS NOT NULL)),
  ADD CONSTRAINT travel_attempt_scope_pair CHECK ((request_scope IS NULL) = (request_fingerprint IS NULL)),
  ADD CONSTRAINT travel_attempt_coarse_short CHECK (coalesce(length(coarse_origin), 0) <= 64 AND coalesce(length(coarse_destination), 0) <= 64);

-- Technischer Retry (gleicher Schlüssel, gleicher Fingerprint) trifft diesen Eindeutigkeitsschlüssel: je Akteur und Idempotency-Key genau ein Versuch.
CREATE UNIQUE INDEX travel_attempt_request_scope_uq ON travel_attempt (tenant_id, request_scope) WHERE request_scope IS NOT NULL;
CREATE INDEX travel_attempt_by_created ON travel_attempt (tenant_id, created_at, id);
