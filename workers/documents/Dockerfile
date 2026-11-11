# NICHT AUSGEFÜHRT: In der Entwicklungsumgebung gibt es keinen Docker-Daemon (WP-M0-07 AK 1 offen bis CI).
# Build:  docker build -f workers/documents/Dockerfile workers/documents
# Basisimage-Digest ist noch nicht festgeschrieben (siehe workers/routing/Dockerfile).
FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app

# Keine Laufzeitabhängigkeiten außerhalb der Standardbibliothek; Lockfile-Datei für die Laufzeit
# ist daher leer (OCR/Malware-Scan/KoSIT-Anbindung kommen mit M4).
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-deps .

RUN useradd --system --uid 10001 worker
USER 10001
EXPOSE 8082
# Kein Datenbankzugriff, keine DB-Variablen (WP-M0-07 AK 3)
CMD ["python", "-m", "taktovia_documents.health"]
