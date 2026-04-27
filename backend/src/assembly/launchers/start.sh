#!/usr/bin/env bash
# ============================================================================
# MTGCollection launcher (Linux / macOS).
#
# Runs the packaged fat jar against PostgreSQL (the "prod" profile, which is
# the default inside MtgCollectionApplication). Override anything via env vars
# or CLI args, e.g.:
#
#   SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/mtgdb ./start.sh
#   ./start.sh --server.port=9090
#   SPRING_PROFILES_ACTIVE=h2 ./start.sh   # run against in-memory H2 instead
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] java not found on PATH. Install JDK 21+ (https://adoptium.net/) and retry." >&2
    exit 1
fi

exec java \
    -Djava.net.preferIPv4Stack=true \
    -Djava.net.preferIPv4Addresses=true \
    -jar mtgcollection.jar "$@"
