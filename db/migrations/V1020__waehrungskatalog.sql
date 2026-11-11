SET search_path = taktovia, public;
-- WP-M1-02 / Kap. 05 §5.1: Waehrungskatalog fuer die serverseitige Pruefung von `exponent` (Money/SignedMoney).
-- Globale Tabelle ohne tenant_id; die Runtime-Rolle liest nur, Pflege ausschliesslich per Migration.
-- Exponent = Anzahl Nachkommastellen der kleinsten Einheit nach ISO 4217. Weitere Waehrungen folgen per Vorwaertsmigration.
INSERT INTO currency(code, exponent) VALUES
 ('EUR', 2), ('CHF', 2), ('USD', 2), ('GBP', 2), ('PLN', 2), ('CZK', 2), ('DKK', 2), ('SEK', 2), ('NOK', 2), ('HUF', 2),
 ('JPY', 0), ('KRW', 0), ('BHD', 3), ('KWD', 3)
ON CONFLICT (code) DO NOTHING;
