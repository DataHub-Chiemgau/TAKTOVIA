"""Umgebungsschranke für Restores (WP-M5-14, Abnahme 4; Kap. 01 §1.7: keine Produktionsdaten in Staging/Development)."""

KNOWN_ENVIRONMENTS = ("development", "staging", "performance", "production")


class EnvironmentMismatch(Exception):
    """Restore in eine andere Umgebung als die Quellumgebung."""


def assert_restore_target(source_environment: str | None, target_environment: str | None) -> None:
    if source_environment not in KNOWN_ENVIRONMENTS:
        raise EnvironmentMismatch(f"Quellumgebung unbekannt oder nicht im Manifest: {source_environment!r}")
    if target_environment != source_environment:
        raise EnvironmentMismatch(f"Restore in Umgebung {target_environment!r} abgewiesen: das Backup stammt aus {source_environment!r} (Restore nur in die Quellumgebung)")
