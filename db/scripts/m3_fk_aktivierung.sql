-- WP-M3-02: Fremdschlüssel des Kernschemas, die erst nach der Überführung der Fixtures erzwungen werden können (Betrieb/Integration, Administratorrolle).
-- Warum nicht in V3020: die M2-Referenzregion (tests/acceptance/fixtures/m2_referenzregion/seed.sql), die Spike-Daten und mehrere Testfixtures legen Schichten mit
-- freien Fahrerkennungen und Freigaben mit freien Prüfverweisen an. WP-M3-02 Kriterium 6 verlangt, dass sie nach der Migration gültig bleiben.
-- Ablauf (expand/contract): 1. NOT VALID (prüft nur neue und geänderte Zeilen), 2. Altbestand bereinigen, 3. VALIDATE CONSTRAINT.
-- Getestet in db/tests/test_m3_02_tabellen.py::FremdschluesselAktivierung (in einer zurückgerollten Transaktion).
SET search_path = taktovia, public;
ALTER TABLE staff_shift ADD CONSTRAINT staff_shift_driver_fk FOREIGN KEY (tenant_id, driver_ref) REFERENCES driver(tenant_id, id) NOT VALID;
ALTER TABLE resource_release ADD CONSTRAINT resource_release_inspection_fk FOREIGN KEY (tenant_id, inspection_ref) REFERENCES inspection_run(tenant_id, id) NOT VALID;
-- nach Bereinigung des Altbestands:
-- ALTER TABLE staff_shift VALIDATE CONSTRAINT staff_shift_driver_fk;
-- ALTER TABLE resource_release VALIDATE CONSTRAINT resource_release_inspection_fk;
