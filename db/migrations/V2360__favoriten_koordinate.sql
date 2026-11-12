-- WP-M2-36 / UI-05: Favoritenorte tragen eine validierte Koordinate und einen optionalen Bezug auf einen Haltepunkt.
-- Entscheidung für den Bau (Offener Punkt 31, Schnittlinie M1/M2): `passenger_preference` (WP-M1-29) bleibt Eigentum von WP-M1-29; M2 ergänzt nur die Spalte `point`
-- und lockert die Form: ein FAVORITE braucht Bezeichnung und Koordinate, der Haltepunkt ist optional. Ein Favorit bleibt als Ort nutzbar, auch wenn sein Haltepunkt
-- später deaktiviert wird (Deaktivierung ist eine neue Revision in stop_revision, die Referenz bleibt gültig). Die Koordinate liegt als WGS84-Punkt vor
-- (Längengrad, Breitengrad im Wertebereich) und ist nie 0/0.
SET search_path = taktovia, public;

ALTER TABLE passenger_preference ADD COLUMN point geometry(Point, 4326);

-- Altbestand (M1: Favorit nur mit Haltepunkt): Koordinate der jüngsten Haltepunktrevision übernehmen. FORCE RLS gilt auch für den Eigentümer, deshalb kurz aufheben.
ALTER TABLE passenger_preference NO FORCE ROW LEVEL SECURITY;
UPDATE passenger_preference p SET point = (SELECT s.point FROM stop_revision s WHERE s.tenant_id = p.tenant_id AND s.stop_id = p.stop_id ORDER BY s.revision DESC LIMIT 1)
 WHERE p.kind = 'FAVORITE';
ALTER TABLE passenger_preference FORCE ROW LEVEL SECURITY;

ALTER TABLE passenger_preference DROP CONSTRAINT passenger_preference_shape;
ALTER TABLE passenger_preference
  ADD CONSTRAINT passenger_preference_shape CHECK (
    (kind = 'FAVORITE'             AND label IS NOT NULL AND point IS NOT NULL AND channel IS NULL) OR
    (kind = 'PREFERRED_STOP'       AND label IS NULL     AND point IS NULL     AND stop_id IS NOT NULL AND channel IS NULL) OR
    (kind = 'NOTIFICATION_CHANNEL' AND label IS NULL     AND point IS NULL     AND stop_id IS NULL     AND channel IS NOT NULL)),
  ADD CONSTRAINT passenger_preference_point_valid CHECK (point IS NULL OR (
    ST_X(point) BETWEEN -180 AND 180 AND ST_Y(point) BETWEEN -90 AND 90 AND NOT (ST_X(point) = 0 AND ST_Y(point) = 0)));
