"""Verschlüsselte, veränderungsgeschützte Backups mit Restore-Test (WP-M1-39, LH-6.18-SEC-010).

Module: crypto (AES-256-GCM, chunkweise, manipulationserkennend), store (schreibgeschützter Backupspeicher mit getrennter Berechtigungsdomäne),
dbops (konsistentes Backup, Journalausleitung, Restore in isolierte Datenbank, Prüfungen), cli (python -m taktovia_backup).
"""
