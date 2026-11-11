# NICHT AUSGEFÜHRT: In der Entwicklungsumgebung gibt es keinen Docker-Daemon (WP-M0-07 AK 1 offen bis CI).
# Build aus dem Repository-Wurzelverzeichnis:  docker build -f workers/routing/Dockerfile workers/routing
# Basisimage-Digest ist noch nicht festgeschrieben (Registry in der Umgebung nicht erreichbar);
# vor dem ersten CI-Build per `docker buildx imagetools inspect python:3.13-slim-bookworm` ergänzen.
FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app

# Abhängigkeiten reproduzierbar aus dem Lockfile mit Hashes (Laufzeit, ohne dev)
COPY requirements.lock ./
RUN pip install --require-hashes --no-deps -r requirements.lock

COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-deps .

# OR-Tools-Selbsttest beim Build: Import und Trivialproblem (WP-M0-07 AK 2)
RUN python -m taktovia_routing.selfcheck

RUN useradd --system --uid 10001 worker
USER 10001
EXPOSE 8081
# Kein Datenbankzugriff, keine DB-Variablen (WP-M0-07 AK 3)
CMD ["python", "-m", "taktovia_routing.health"]
